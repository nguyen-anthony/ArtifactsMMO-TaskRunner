package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.BossEncounterCoordinator
import com.artifactsmmo.core.task.FightingExecutor
import com.artifactsmmo.core.task.StepResult
import com.artifactsmmo.core.task.TaskType
import com.artifactsmmo.domain.queue.GroupRole
import com.artifactsmmo.domain.queue.GroupState
import com.artifactsmmo.domain.queue.TaskStatus
import com.artifactsmmo.domain.task.MemberPlan
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.queue.TaskQueue
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process rendezvous for group fights, keyed by the group (parent task) id.
 * Safe because only one backend instance runs workers (advisory instance lock).
 *
 * Wraps the legacy [BossEncounterCoordinator]: participants `signalReady` when positioned,
 * the initiator `awaitParticipants` then fires the fight with their names.
 */
class GroupCoordinator {
    val encounters = BossEncounterCoordinator()
    /** groupId -> initiator name currently registered with [encounters]. */
    private val registered = ConcurrentHashMap<Long, String>()
    private val provisionLocks = ConcurrentHashMap<Long, Mutex>()

    /** Members provision one at a time so they don't race for the same bank stacks. */
    fun provisionLock(groupId: Long): Mutex = provisionLocks.computeIfAbsent(groupId) { Mutex() }

    /** Registers the encounter once per formation (idempotent). */
    fun ensureRegistered(groupId: Long, initiator: String, participants: List<String>, monsterCode: String) {
        if (registered.putIfAbsent(groupId, initiator) == null) {
            encounters.registerEncounter(initiator, participants, monsterCode)
        }
    }

    /**
     * Tears down the encounter so anyone blocked in await/signal is released (they get a
     * closed-channel error, which the legacy step treats as "wait and retry").
     */
    fun clear(groupId: Long) {
        registered.remove(groupId)?.let { encounters.clearEncounter(it) }
    }

    fun forget(groupId: Long) {
        clear(groupId)
        provisionLocks.remove(groupId)
    }
}

/**
 * Runs one member slot of a boss/raid group (see `GroupRole`).
 *
 * Lifecycle per member:
 *  1. start: provision (gear, utilities, reserve potions, food, dungeon keys) — serialized
 *     per group. Happens as soon as the slot is claimed, before the group is formed.
 *  2. step: wait until every slot is claimed ("formed"); then the legacy boss step runs —
 *     initiator waits for participants' ready signals and calls fight(participants = …),
 *     participants move to the tile, signal, and wait out the shared fight cooldown.
 *  3. If any member leaves for good (failed/cancelled) the rest of the group is cancelled;
 *     if the initiator completes (e.g. its stop condition), the whole group completes.
 *     A preempted (suspended) member just un-forms the group; the others wait for it.
 */
/** The game actions a boss member performs; separated so group orchestration is testable. */
interface BossOps {
    suspend fun provision(character: String, plan: MemberPlan, status: (String) -> Unit)
    suspend fun step(character: String, task: TaskType.BossFight, encounters: BossEncounterCoordinator,
                     status: (String) -> Unit, previous: com.artifactsmmo.client.models.Character?): StepResult
    suspend fun restock(character: String, task: TaskType.BossFight, status: (String) -> Unit): Boolean
}

/** [BossOps] backed by the existing, tuned FightingExecutor/ActionHelper logic. */
class LegacyBossOps(private val helper: ActionHelper, private val fighting: FightingExecutor) : BossOps {
    override suspend fun provision(character: String, plan: MemberPlan, status: (String) -> Unit) {
        if (plan.equip.isNotEmpty()) {
            status("Equipping boss gear...")
            helper.retrieveAndEquipItems(character, plan.equip.map { ActionHelper.EquipAction(it.slot, it.itemCode, it.source) })
        }
        if (plan.utilities.isNotEmpty()) {
            status("Applying utility potions...")
            helper.retrieveAndEquipUtilities(character, plan.utilities.map {
                com.artifactsmmo.core.task.GearOptimizer.UtilityEquipAction(it.slot, it.itemCode, it.quantity, it.source)
            })
        }
        if (plan.reservePotions.isNotEmpty()) helper.withdrawReservePotions(character, plan.reservePotions)
        val food = plan.foodCode
        if (food != null && plan.foodQuantity > 0) {
            helper.bankWithdrawItems(character, listOf(SimpleItem(food, plan.foodQuantity)))
        }
        // Gold stays on the character; the transition consumes it directly.
        val keys = (plan.transitionCosts.entries + plan.spareKeys.entries)
            .filter { it.key != "gold" }
            .groupBy({ it.key }, { it.value })
            .mapValues { it.value.sum() }
        if (keys.isNotEmpty()) helper.bankWithdrawItems(character, keys.map { SimpleItem(it.key, it.value) })
    }

    override suspend fun step(character: String, task: TaskType.BossFight, encounters: BossEncounterCoordinator,
                              status: (String) -> Unit, previous: com.artifactsmmo.client.models.Character?) =
        fighting.executeBossStep(character, task, encounters, status, previous)

    override suspend fun restock(character: String, task: TaskType.BossFight, status: (String) -> Unit) =
        fighting.restockForBossFight(character, task, status)
}

class BossFightExecutor(
    private val ops: BossOps,
    private val queue: TaskQueue,
    private val coordinator: GroupCoordinator,
    private val deathLimit: Int = 3,
) : TaskExecutor {

    override suspend fun start(ctx: ExecContext) {
        if (ctx.state.prepared) return
        val spec = ctx.spec as TaskSpec.BossFight
        val plan = spec.plans[ctx.character]
        if (plan != null) {
            coordinator.provisionLock(groupId(ctx)).withLock {
                ctx.status("Provisioning for ${spec.monsterName}...")
                ops.provision(ctx.character, plan, ctx.status)
            }
        }
        ctx.state.prepared = true
    }

    override suspend fun step(ctx: ExecContext): StepOutcome {
        val spec = ctx.spec as TaskSpec.BossFight
        val gid = groupId(ctx)
        val group = queue.group(gid) ?: return StepOutcome.Fail("Group $gid no longer exists")

        leftGroup(group, ctx)?.let { return it }

        if (!group.isFormed) {
            // Someone is missing (not yet claimed, or preempted). Release anyone blocked.
            coordinator.clear(gid)
            val have = group.slots.count { it.claimedBy != null && !it.status.isTerminal }
            ctx.status("Waiting for group: $have/${group.slots.size} members")
            return StepOutcome.Continue(5_000)
        }

        val isInitiator = ctx.task.groupRole == GroupRole.INITIATOR
        val initiator = group.initiator!!.claimedBy!!
        val participants = group.participantNames
        if (isInitiator) coordinator.ensureRegistered(gid, initiator, participants, spec.monsterCode)

        val legacy = SpecMapper.bossFight(spec, ctx.character, initiator, isInitiator, participants)
        val prev = ctx.previousChar
        ctx.previousChar = null
        val s = ctx.state
        return when (val r = ops.step(ctx.character, legacy, coordinator.encounters, ctx.status, prev)) {
            is StepResult.FightWon -> {
                ctx.previousChar = r.character; s.fightsWon++; s.consecutiveDeaths = 0; StepOutcome.Continue()
            }
            is StepResult.FightLost -> {
                s.fightsLost++; s.consecutiveDeaths++
                if (s.consecutiveDeaths >= deathLimit) StepOutcome.Fail("Lost ${s.consecutiveDeaths} boss fights in a row")
                else StepOutcome.Continue(5_000)
            }
            is StepResult.NeedsRestock -> {
                s.bankTrips++
                if (restock(ctx, legacy)) StepOutcome.Continue()
                else StepOutcome.Fail("Restock failed — utility loadout could not be restored")
            }
            is StepResult.Rested -> StepOutcome.Continue()
            // Participants already waited out the fight cooldown; the initiator's Waiting means
            // "encounter cleared" or "raid staged but not open yet" — back off a little.
            is StepResult.Waiting -> StepOutcome.Continue(if (isInitiator) 3_000 else 0)
            is StepResult.Error -> { ctx.status("Error: ${r.message}"); StepOutcome.Continue(10_000) }
            else -> StepOutcome.Continue(3_000)
        }
    }

    private suspend fun restock(ctx: ExecContext, legacy: TaskType.BossFight): Boolean =
        coordinator.provisionLock(groupId(ctx)).withLock {
            ops.restock(ctx.character, legacy, ctx.status)
        }

    /** Non-null when the group has ended or broken and this member should stop. */
    private suspend fun leftGroup(group: GroupState, ctx: ExecContext): StepOutcome? {
        if (group.parent.status.isTerminal) return StepOutcome.Complete("Group ended")
        val initiator = group.initiator
        if (initiator != null && initiator.id != ctx.taskId && initiator.status == TaskStatus.COMPLETED) {
            queue.completeGroup(group.parent.id, "initiator finished")
            return StepOutcome.Complete("Initiator finished")
        }
        val gone = group.slots.firstOrNull {
            it.id != ctx.taskId && (it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED)
        }
        if (gone != null) {
            queue.cancel(group.parent.id, "member slot ${gone.id} ${gone.status.dbValue}")
            return StepOutcome.Complete("Group disbanded (${gone.claimedBy ?: "a member"} left)")
        }
        return null
    }

    /** Group ends for everyone once any member ends for good. */
    override suspend fun cleanup(ctx: ExecContext) {
        val gid = groupId(ctx)
        val group = runCatching { queue.group(gid) }.getOrNull()
        if (group != null && !group.parent.status.isTerminal) {
            val mine = group.slots.firstOrNull { it.id == ctx.taskId }
            if (mine?.status == TaskStatus.COMPLETED && ctx.task.groupRole == GroupRole.INITIATOR) {
                queue.completeGroup(gid, "initiator completed")
            } else if (mine?.status != TaskStatus.COMPLETED) {
                queue.cancel(gid, "${ctx.character} left the group (${mine?.status?.dbValue})")
            }
        }
        coordinator.forget(gid)
        // Boss loot is banked per loop by the legacy code; nothing else to clean up.
    }

    /** Suspend/stop: release anyone blocked on this member's signal. */
    override fun onStop(ctx: ExecContext) = coordinator.clear(groupId(ctx))

    private fun groupId(ctx: ExecContext): Long =
        requireNotNull(ctx.task.groupId) { "boss_fight task ${ctx.taskId} is not part of a group" }
}
