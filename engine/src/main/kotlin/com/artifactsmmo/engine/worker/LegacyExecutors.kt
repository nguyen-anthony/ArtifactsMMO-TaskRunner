package com.artifactsmmo.engine.worker

import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.BankExecutor
import com.artifactsmmo.core.task.CraftingExecutor
import com.artifactsmmo.core.task.EventExecutor
import com.artifactsmmo.core.task.FightingExecutor
import com.artifactsmmo.core.task.GatheringExecutor
import com.artifactsmmo.core.task.StepResult
import com.artifactsmmo.core.task.TaskMasterExecutor
import com.artifactsmmo.core.task.TaskType
import com.artifactsmmo.domain.task.TaskSpec

/**
 * Adapts the existing, tuned executors (Gathering/Fighting/Crafting/TaskMaster/Bank) to the
 * worker's [TaskExecutor] contract. The per-step game logic is unchanged; what moved here
 * from CharacterTaskRunner is the one-time setup and the StepResult → StepOutcome mapping.
 *
 * Behaviour change vs the old runner: results that used to "revert to the previous task"
 * now simply Complete/Fail the task — the queue then hands out whatever is next (including
 * a suspended task, which resumes from its checkpoint).
 */
class LegacyExecutors(
    private val helper: ActionHelper,
    private val gathering: GatheringExecutor = GatheringExecutor(helper),
    private val fighting: FightingExecutor = FightingExecutor(helper),
    private val crafting: CraftingExecutor = CraftingExecutor(helper),
    private val taskMaster: TaskMasterExecutor = TaskMasterExecutor(helper, gathering, fighting),
    private val bank: BankExecutor = BankExecutor(helper),
    private val event: EventExecutor = EventExecutor(helper, fighting),
    /** Is this event still live? (fed by the realtime listener; defaults to "yes"). */
    private val eventActive: (String) -> Boolean = { true },
    /** Needed for boss/raid groups; null = boss fights unsupported. */
    private val bossFights: BossFightExecutor? = null,
) : ExecutorRegistry {

    private val executor = Adapter()

    override fun forSpec(spec: TaskSpec): TaskExecutor? =
        if (spec is TaskSpec.BossFight) bossFights else executor

    private inner class Adapter : TaskExecutor {

        override suspend fun start(ctx: ExecContext) {
            if (ctx.state.prepared) return
            when (val task = legacy(ctx)) {
                is TaskType.Fight -> {
                    // Equip plan chosen in the UI; best-effort like the old runner.
                    runCatching {
                        if (task.equipActions.isNotEmpty()) {
                            ctx.status("Equipping gear...")
                            helper.retrieveAndEquipItems(ctx.character, task.equipActions)
                        }
                        if (task.utilityActions.isNotEmpty()) {
                            ctx.status("Equipping utilities...")
                            helper.retrieveAndEquipUtilities(ctx.character, task.utilityActions)
                        }
                    }.onFailure { rethrowCancellation(it); ctx.status("Gear retrieval error: ${it.message}") }
                }
                is TaskType.EventFight -> runCatching {
                    if (task.equipActions.isNotEmpty()) {
                        ctx.status("Equipping gear for event...")
                        helper.retrieveAndEquipItems(ctx.character, task.equipActions)
                    }
                    if (task.utilityActions.isNotEmpty()) {
                        ctx.status("Applying utility potions for event...")
                        helper.retrieveAndEquipUtilities(ctx.character, task.utilityActions)
                    }
                }.onFailure { rethrowCancellation(it); ctx.status("Event gear error: ${it.message}") }
                is TaskType.Gather -> runCatching { gathering.prepareGatherTask(ctx.character, task, ctx.status) }
                    .onFailure { rethrowCancellation(it); ctx.status("Bank prep error: ${it.message}") }
                else -> Unit
            }
            ctx.state.prepared = true
        }

        override suspend fun step(ctx: ExecContext): StepOutcome {
            val task = legacy(ctx)
            val prev = ctx.previousChar
            val result = when (task) {
                is TaskType.Gather -> gathering.executeStep(ctx.character, task, ctx.status, prev)
                is TaskType.Fight -> fighting.executeStep(ctx.character, task, ctx.status, prev)
                is TaskType.Craft -> crafting.executeStep(ctx.character, task, ctx.status, prev)
                is TaskType.TaskMaster -> taskMaster.executeStep(ctx.character, task, ctx.status, prev)
                is TaskType.BankWithdraw -> bank.executeBankWithdraw(ctx.character, task, ctx.status)
                is TaskType.BankRecycle -> bank.executeBankRecycle(ctx.character, task, ctx.status)
                is TaskType.InventoryDeposit -> bank.executeInventoryDeposit(ctx.character, task, ctx.status)
                is TaskType.InventoryRecycle -> bank.executeInventoryRecycle(ctx.character, task, ctx.status)
                is TaskType.BulkBankWithdraw -> bank.executeBulkBankWithdraw(ctx.character, task, ctx.status)
                is TaskType.BulkInventoryDeposit -> bank.executeBulkInventoryDeposit(ctx.character, task, ctx.status)
                is TaskType.EventGather -> event.executeGatherStep(ctx.character, task, { eventActive(task.eventCode) }, ctx.status, prev)
                is TaskType.EventNpc -> event.executeNpcStep(ctx.character, task, ctx.status)
                is TaskType.EventFight -> event.executeEventFightStep(ctx.character, task, { eventActive(task.eventCode) }, ctx.status, prev)
                else -> return StepOutcome.Fail("Unsupported task type ${ctx.spec.typeName}")
            }
            return map(ctx, task, result)
        }

        override suspend fun cleanup(ctx: ExecContext) {
            runCatching { LegacyCleanup(helper, ctx.character).run(legacy(ctx)) { ctx.status("Cleanup: $it") } }
                .onFailure { rethrowCancellation(it); ctx.status("Cleanup error: ${it.message}") }
        }

        private fun legacy(ctx: ExecContext): TaskType = SpecMapper.toLegacy(ctx.spec, craftedSoFar = ctx.state.crafted)

        private suspend fun map(ctx: ExecContext, task: TaskType, r: StepResult): StepOutcome {
            val s = ctx.state
            ctx.previousChar = null
            return when (r) {
                is StepResult.Gathered -> { ctx.previousChar = r.character; s.gathers++; StepOutcome.Continue() }
                is StepResult.FightWon -> {
                    ctx.previousChar = r.character; s.fightsWon++; s.consecutiveDeaths = 0; StepOutcome.Continue()
                }
                is StepResult.FightLost -> {
                    s.fightsLost++; s.consecutiveDeaths++
                    if (s.consecutiveDeaths < DEATH_LIMIT) return StepOutcome.Continue(5_000)
                    if (task is TaskType.TaskMaster) {
                        // Same as before: blacklist the monster, cancel the in-game task, keep going.
                        s.consecutiveDeaths = 0
                        val code = runCatching { helper.refreshCharacter(ctx.character).task }.getOrDefault("")
                        taskMaster.blacklistMonster(code)
                        taskMaster.cancelCurrentMonsterTask(ctx.character, task.type, ctx.status)
                        StepOutcome.Continue()
                    } else StepOutcome.Fail("Stopped after ${s.consecutiveDeaths} consecutive deaths (${r.message})")
                }
                is StepResult.Crafted -> { s.crafted += r.count; s.recycled += r.recycled; StepOutcome.Continue() }
                is StepResult.Banked -> { s.bankTrips++; StepOutcome.Continue() }
                is StepResult.CraftedAndBanked -> { s.crafted += r.craftCount; s.bankTrips++; StepOutcome.Continue() }
                is StepResult.Rested -> StepOutcome.Continue()
                is StepResult.Waiting -> StepOutcome.Continue(3_000)
                is StepResult.Error -> { ctx.status("Error: ${r.message}"); StepOutcome.Continue(10_000) }
                is StepResult.OutOfMaterials -> StepOutcome.Complete("Out of materials")
                is StepResult.CraftTaskComplete -> StepOutcome.Complete("Crafting target reached")
                is StepResult.QuickTaskComplete -> StepOutcome.Complete("Done")
                is StepResult.TaskMasterTaskCompleted -> { s.tasksCompleted++; StepOutcome.Continue() }
                is StepResult.TaskMasterTaskCancelled -> StepOutcome.Continue()
                is StepResult.TaskMasterNoViableTask -> StepOutcome.Fail("No viable task-master task")
                is StepResult.TooManyDeaths -> { s.consecutiveDeaths = 0; StepOutcome.Continue() }
                is StepResult.EventExpired -> StepOutcome.Complete("Event ended")
                is StepResult.NeedsRestock -> StepOutcome.Fail("Restock is only supported for boss fights")
            }
        }
    }

    private companion object {
        const val DEATH_LIMIT = 3
    }
}

internal fun rethrowCancellation(t: Throwable) {
    if (t is kotlinx.coroutines.CancellationException) throw t
}
