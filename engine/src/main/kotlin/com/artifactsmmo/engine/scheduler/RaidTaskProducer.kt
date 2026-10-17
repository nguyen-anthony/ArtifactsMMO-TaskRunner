package com.artifactsmmo.engine.scheduler

import com.artifactsmmo.client.RealtimeMessage
import com.artifactsmmo.client.models.Raid
import com.artifactsmmo.core.task.BankReserver
import com.artifactsmmo.core.task.RaidConfig
import com.artifactsmmo.core.task.disjointParties
import com.artifactsmmo.domain.queue.GroupRole
import com.artifactsmmo.domain.queue.GroupSlot
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.TaskFilter
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.task.MemberPlan
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.config.TypedConfigs
import com.artifactsmmo.engine.queue.TaskQueue
import com.artifactsmmo.engine.realtime.LIVE_STATUSES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

/** One party's co-op provisioning result. */
data class PartyPlan(
    val plans: Map<String, MemberPlan>,
    val winRate: Double = 1.0,
    /** Bank quantities this party will withdraw; later parties plan around them. */
    val bankDemand: Map<String, Int> = emptyMap(),
)

/** Co-op provisioning for a raid party (wraps CoopOptimizer in production). */
fun interface RaidPlanner {
    suspend fun plan(members: List<String>, monsterCode: String, tankOverride: String?, bankReserved: Map<String, Int>): PartyPlan
}

/**
 * Turns configured raids into queue groups ahead of their start time.
 *
 * Every [pollMillis] it reads /raids; for each enabled config whose raid is active, or starts
 * within `leadTimeMinutes`, it enqueues one group per configured party: initiator slot +
 * participant slots, all assigned, at the party's priority (default RAID), expiring when the raid window closes.
 * Parties are planned in order and each later party plans around the bank stock the earlier
 * ones will withdraw; a party below `minWinRate` is skipped. The dedupe key
 * `raid:<code>:<start>:<initiator>` means each party is queued once per raid instance, even
 * across restarts.
 *
 * The group stages early (provision + travel); the boss step itself waits for the start time.
 * On `raid_ended` (or when polling sees the raid finished) live groups for it are cancelled,
 * and the members' suspended work resumes via the normal queue.
 */
@OptIn(ExperimentalTime::class)
class RaidTaskProducer(
    private val queue: TaskQueue,
    private val configs: TypedConfigs,
    private val raids: suspend () -> List<Raid>,
    private val planner: RaidPlanner,
    private val messages: Flow<RealtimeMessage> = emptyFlow(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = 60_000,
    private val log: (String) -> Unit = {},
    /** Bank reservation ledger for the planned parties' withdrawals; null = not reserved. */
    private val reserver: BankReserver? = null,
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        launch {
            messages.collect { m ->
                if (m is RealtimeMessage.RaidEnded && m.raidCode.isNotEmpty()) safely { cancelRaid(m.raidCode, "raid_ended") }
            }
        }
        while (isActive) {
            safely { tick() }
            delay(pollMillis)
        }
    }

    suspend fun tick() {
        val enabled = configs.raids().filter { it.enabled && it.allParties.isNotEmpty() }
        if (enabled.isEmpty()) return
        val now = clock()
        for (raid in raids()) {
            val config = enabled.firstOrNull { it.raidCode == raid.code } ?: continue
            val instance = raid.activeInstance?.takeUnless { it.status.startsWith("finished") }
            val isActive = instance != null || raid.status == "active"
            val upcoming = raid.nextStartAt?.toEpochMilliseconds()
            if (!isActive) {
                // `status` describes the LATEST instance: between raids it reads finished_* while
                // next_start_at already points at the next one, so "finished" must not block staging.
                cancelRaid(raid.code, "raid finished", keepStartingFrom = upcoming)
                if (upcoming == null) continue
            }

            val start = instance?.startsAt?.toEpochMilliseconds() ?: upcoming ?: continue
            if (!isActive && start - now > config.leadTimeMinutes * 60_000L) continue
            log("Raid ${raid.code}: status=${raid.status} active=$isActive start=$start (in ${(start - now) / 1000}s) " +
                "leadTime=${config.leadTimeMinutes}m parties=${config.allParties.joinToString(" | ") { it.members.joinToString() }}")
            val end = raid.activeInstance?.endsAt?.toEpochMilliseconds() ?: (start + raid.schedule.durationHours * 3_600_000L)
            if (end <= now) continue
            enqueueRaid(raid, config, start, end)
        }
    }

    private suspend fun enqueueRaid(raid: Raid, config: RaidConfig, start: Long, end: Long) {
        val parties = disjointParties(config.allParties) { log("Raid ${raid.code}: skipped $it") }
        // Skip the (expensive, simulation-heavy) planning for parties already queued for this instance.
        val live = queue.list(TaskFilter(statuses = LIVE_STATUSES, source = TaskSource.RAID, limit = 200))
            .mapNotNull { it.dedupeKey }.toSet()
        val legacyKey = "raid:${raid.code}:$start" // single-party key used before multi-party configs
        val reserved = mutableMapOf<String, Int>()
        for ((index, party) in parties.withIndex()) {
            val members = party.members
            val dedupe = "raid:${raid.code}:$start:${party.initiatorName}"
            if (dedupe in live || (index == 0 && legacyKey in live)) continue

            val result = planner.plan(members, raid.monster, party.tankOverride, reserved.toMap())
            val plans = result.plans
            for ((name, p) in plans) {
                log("Raid ${raid.code}: plan $name equip=${p.equip.size} " +
                    "utilities=${p.utilities.joinToString { "${it.slot}=${it.itemCode}x${it.quantity}" }.ifEmpty { "none" }} " +
                    "reserves=${p.reservePotions} food=${p.foodCode ?: "none"}x${p.foodQuantity} keys=${p.transitionCosts + p.spareKeys}")
            }
            if (members.any { it !in plans }) log("Raid ${raid.code}: WARNING no plan for ${members.filter { it !in plans }}")
            if (result.winRate < config.minWinRate) {
                log("Raid ${raid.code}: party ${members.joinToString()} win rate ${(result.winRate * 100).toInt()}% < " +
                    "${(config.minWinRate * 100).toInt()}% — skipped")
                continue
            }
            result.bankDemand.forEach { (code, qty) -> reserved[code] = (reserved[code] ?: 0) + qty }
            val spec = TaskSpec.BossFight(
                monsterCode = raid.monster, monsterName = raid.name, plans = plans, raidCode = raid.code,
                scheduledStartAtMillis = start, scheduledEndAtMillis = end,
            )
            val priority = config.priorityFor(party)
            val group = queue.enqueueGroup(
                NewTask(type = spec.typeName, spec = spec.toJson(), source = TaskSource.RAID, priority = priority,
                    dedupeKey = dedupe, expiresAtMillis = end),
                members.map { GroupSlot(assignedCharacter = it) },
            )
            if (group != null) {
                log("Raid ${raid.code}: queued group ${group.parent.id} for ${members.joinToString()} (priority $priority)")
                if (result.bankDemand.isNotEmpty()) reserver?.reserve(BankReserver.taskOwner(group.parent.id), members.toSet(), result.bankDemand)
            }
            else log("Raid ${raid.code}: enqueueGroup returned null (dedupe=$dedupe)")
        }
    }

    /**
     * Cancels live groups for [raidCode]. With [keepStartingFrom], groups already staged for an
     * instance starting at or after that time (the upcoming raid) are kept.
     */
    suspend fun cancelRaid(raidCode: String, reason: String, keepStartingFrom: Long? = null) {
        val live = queue.list(TaskFilter(statuses = LIVE_STATUSES, source = TaskSource.RAID, limit = 200))
        for (t in live.filter { it.groupRole == GroupRole.GROUP }) {
            val spec = runCatching { TaskSpec.fromJson(t.spec) as? TaskSpec.BossFight }.getOrNull() ?: continue
            val upcoming = keepStartingFrom != null && (spec.scheduledStartAtMillis ?: Long.MIN_VALUE) >= keepStartingFrom
            if (spec.raidCode == raidCode && !upcoming) {
                queue.cancel(t.id, reason)
                reserver?.release(BankReserver.taskOwner(t.id))
                log("Raid $raidCode: cancelled group ${t.id} ($reason)")
            }
        }
    }

    private suspend fun safely(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("RaidTaskProducer: ${e.message}") }
    }
}
