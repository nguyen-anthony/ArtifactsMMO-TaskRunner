package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.CharacterSeasonalOfferings
import com.artifactsmmo.client.models.OfferingFavor
import com.artifactsmmo.client.models.SeasonalOffering
import com.artifactsmmo.client.models.SelectedOffering
import com.artifactsmmo.core.offering.AcquirePlan
import com.artifactsmmo.core.offering.AcquireStep
import com.artifactsmmo.core.offering.BracketChooser
import com.artifactsmmo.core.task.StepResult
import com.artifactsmmo.domain.task.TaskSpec
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Instant

/** Game operations used by [DailyOfferingExecutor]; see [LegacyOfferingOps]. */
interface OfferingOps {
    suspend fun status(character: String): CharacterSeasonalOfferings
    suspend fun accept(character: String, offeringId: String)
    /** Returns the XP reward. */
    suspend fun complete(character: String, offeringId: String): Int
    /** Combat level for `combat`, else the skill level. */
    suspend fun level(character: String, category: String): Int
    /** [level] for several categories with a single character refresh. */
    suspend fun levels(character: String, categories: List<String>): Map<String, Int> =
        categories.associateWith { level(character, it) }
    suspend fun plan(character: String, code: String, quantity: Int, favor: OfferingFavor): AcquirePlan
    /** Walk to the altar, first moving [carryQuantity] of [carryCode] into the inventory if set. */
    suspend fun goToAltar(character: String, carryCode: String? = null, carryQuantity: Int = 0, status: (String) -> Unit)
    /** One unit of work toward [step]; [prepare] = first step for this target (gear/tool setup). */
    suspend fun work(character: String, step: AcquireStep, prepare: Boolean, status: (String) -> Unit): StepResult
}

/**
 * Seasonal Altar daily offerings (Season 9). For each of today's categories (optionally
 * filtered by the spec): pick a bracket (own level bracket if obtainable, else the cheapest
 * obtainable), accept it at the altar (the favor applies immediately, boosting the sourcing
 * work), source the requirement via the acquisition planner, then hand it in.
 *
 * All state lives on the server (`GET /my/{name}/offerings`) and the planner re-runs every
 * step, so restarts resume naturally. A category is skipped for the rest of the run when no
 * bracket is obtainable, after [DEATH_LIMIT] consecutive deaths, or after
 * [NO_PROGRESS_LIMIT] fruitless steps.
 *
 * With [TaskSpec.DailyOffering.offeringId] set (manual assignment) only that offering is done;
 * anything that would have skipped it (or another bracket already accepted) fails the task.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class DailyOfferingExecutor(
    private val ops: OfferingOps,
    private val clock: Clock = Clock.System,
) : TaskExecutor {

    private class Run {
        var status: CharacterSeasonalOfferings? = null
        val skipped = linkedMapOf<String, String>()
        val completed = mutableListOf<String>()
        var preparedKey: String? = null
        var deaths = 0
        var noProgress = 0
    }

    private val runs = ConcurrentHashMap<Long, Run>()

    override suspend fun step(ctx: ExecContext): StepOutcome {
        val spec = ctx.spec as TaskSpec.DailyOffering
        val run = runs.getOrPut(ctx.taskId) { Run() }
        val name = ctx.character

        val st = run.status ?: try {
            ops.status(name).also { run.status = it }
        } catch (e: ArtifactsApiException) {
            return when (e.errorCode) {
                451 -> StepOutcome.Fail("Seasonal offerings require a membership")
                575 -> StepOutcome.Complete("No seasonal offerings available")
                else -> throw e
            }
        }
        if (clock.now() >= Instant.parse(st.expiresAt)) {
            run.status = null
            return StepOutcome.Complete("Offerings for ${st.date} expired. ${summary(run)}")
        }

        if (spec.offeringId != null) return targeted(ctx, run, st, spec.offeringId!!)

        val today = st.categories
        val wanted = spec.categories.ifEmpty { today }.filter { it in today }
        val category = wanted.firstOrNull { it !in st.completedCategories && it !in run.skipped }
            ?: return StepOutcome.Complete(summary(run))

        val selected = st.selectedOfferings.firstOrNull { it.category == category && !it.isCompleted }
            ?: return chooseAndAccept(ctx, run, st, category)

        return progress(ctx, run, selected)
    }

    /** Manual assignment of one offering: no bracket choice, and anything blocking it fails the task. */
    private suspend fun targeted(ctx: ExecContext, run: Run, st: CharacterSeasonalOfferings, offeringId: String): StepOutcome {
        val offering = st.offerings.firstOrNull { it.id == offeringId }
            ?: return StepOutcome.Fail("Offering $offeringId is not in today's rotation (${st.date})")
        val category = offering.category
        run.skipped[category]?.let { return StepOutcome.Fail("Gave up on $offeringId: $it") }
        val selected = st.selectedOfferings.firstOrNull { it.category == category }
        return when {
            selected?.offeringId == offeringId && selected.isCompleted ->
                StepOutcome.Complete(if (category in run.completed) summary(run) else "Offering $offeringId was already completed today")
            category in st.completedCategories || selected?.isCompleted == true ->
                StepOutcome.Fail("The $category offering was already completed today (${selected?.offeringId ?: "another bracket"})")
            selected != null && selected.offeringId != offeringId ->
                StepOutcome.Fail("$offeringId cannot be accepted: ${selected.offeringId} is already accepted for $category today")
            selected != null -> progress(ctx, run, selected)
            else -> {
                val req = offering.requirement
                when (val plan = ops.plan(ctx.character, req.code, req.quantity, offering.favor)) {
                    // Don't lock the category on an offering we cannot finish.
                    is AcquirePlan.Infeasible -> StepOutcome.Fail("$offeringId is not obtainable: ${plan.reason}")
                    else -> accept(ctx, run, offering)
                }
            }
        }
    }

    private suspend fun progress(ctx: ExecContext, run: Run, selected: SelectedOffering): StepOutcome {
        val req = selected.requirement
        val category = selected.category
        return when (val plan = ops.plan(ctx.character, req.code, req.quantity, selected.favor)) {
            AcquirePlan.Ready -> handIn(ctx, run, selected.offeringId, category, req.code, req.quantity)
            is AcquirePlan.Infeasible -> skip(ctx, run, category, plan.reason)
            is AcquirePlan.Next -> work(ctx, run, category, plan.step)
        }
    }

    private suspend fun chooseAndAccept(ctx: ExecContext, run: Run, st: CharacterSeasonalOfferings, category: String): StepOutcome {
        val name = ctx.character
        val level = ops.level(name, category)
        val candidates = st.offerings.filter { it.category == category }.map {
            BracketChooser.Candidate(it, it.levelMin, it.levelMax, ops.plan(name, it.requirement.code, it.requirement.quantity, it.favor))
        }
        val choice = BracketChooser.choose(level, candidates)
        if (choice == null) {
            val reasons = candidates.mapNotNull { (it.plan as? AcquirePlan.Infeasible)?.reason }.distinct()
            return skip(ctx, run, category, "no obtainable bracket (${reasons.joinToString("; ").ifEmpty { "none offered" }})")
        }
        return accept(ctx, run, choice.offering)
    }

    private suspend fun accept(ctx: ExecContext, run: Run, offering: SeasonalOffering): StepOutcome {
        val name = ctx.character
        val category = offering.category
        ctx.status("Accepting $category offering ${offering.levelMin}-${offering.levelMax}: " +
            "${offering.requirement.quantity}x ${offering.requirement.code}")
        ops.goToAltar(name, status = ctx.status)
        try {
            ops.accept(name, offering.id)
        } catch (e: ArtifactsApiException) {
            when (e.errorCode) {
                451 -> return StepOutcome.Fail("Seasonal offerings require a membership")
                // Already selected / completed / rotated: the refreshed status sorts it out.
                574, 575, 576 -> ctx.status("Accept skipped (${e.errorCode}): ${e.message}")
                else -> throw e
            }
        }
        run.status = null
        return StepOutcome.Continue()
    }

    private suspend fun handIn(
        ctx: ExecContext, run: Run, offeringId: String, category: String, code: String, quantity: Int,
    ): StepOutcome {
        val name = ctx.character
        ctx.status("Handing in ${quantity}x $code for the $category offering...")
        ops.goToAltar(name, code, quantity, ctx.status)
        try {
            val xp = ops.complete(name, offeringId)
            run.completed += category
            ctx.state.tasksCompleted++
            ctx.status("Completed the $category offering (+$xp XP)")
        } catch (e: ArtifactsApiException) {
            when (e.errorCode) {
                451 -> return StepOutcome.Fail("Seasonal offerings require a membership")
                478 -> if (++run.noProgress >= NO_PROGRESS_LIMIT) {
                    return skip(ctx, run, category, "hand-in keeps failing: ${e.message}")
                }
                574, 575, 577 -> ctx.status("Hand-in skipped (${e.errorCode}): ${e.message}")
                else -> throw e
            }
        }
        run.status = null
        return StepOutcome.Continue()
    }

    private suspend fun work(ctx: ExecContext, run: Run, category: String, step: AcquireStep): StepOutcome {
        val key = when (step) {
            is AcquireStep.Gather -> "gather:${step.resourceCode}"
            is AcquireStep.Fight -> "fight:${step.monsterCode}"
            is AcquireStep.Craft -> "craft:${step.itemCode}"
        }
        val prepare = run.preparedKey != key
        if (prepare) {
            run.preparedKey = key
            run.deaths = 0
            run.noProgress = 0
        }
        return when (val r = ops.work(ctx.character, step, prepare, ctx.status)) {
            is StepResult.FightLost -> {
                ctx.state.fightsLost++
                if (++run.deaths >= DEATH_LIMIT) skip(ctx, run, category, "died $DEATH_LIMIT times to ${(step as? AcquireStep.Fight)?.monsterCode}")
                else StepOutcome.Continue(5_000)
            }
            is StepResult.TooManyDeaths -> skip(ctx, run, category, "too many deaths to ${r.monsterName}")
            is StepResult.Error, StepResult.OutOfMaterials, is StepResult.NeedsRestock,
            StepResult.TaskMasterNoViableTask, StepResult.EventExpired -> {
                if (r is StepResult.Error) ctx.status("Error: ${r.message}")
                if (++run.noProgress >= NO_PROGRESS_LIMIT) skip(ctx, run, category, "no progress toward $key")
                else StepOutcome.Continue(5_000)
            }
            is StepResult.Waiting -> StepOutcome.Continue(3_000)
            else -> {
                run.noProgress = 0
                when (r) {
                    is StepResult.FightWon -> { run.deaths = 0; ctx.state.fightsWon++ }
                    is StepResult.Gathered -> ctx.state.gathers++
                    is StepResult.Crafted -> ctx.state.crafted += r.count
                    is StepResult.CraftedAndBanked -> ctx.state.crafted += r.craftCount
                    else -> Unit
                }
                StepOutcome.Continue()
            }
        }
    }

    private fun skip(ctx: ExecContext, run: Run, category: String, reason: String): StepOutcome {
        run.skipped[category] = reason
        run.preparedKey = null
        ctx.status("Skipping the $category offering: $reason")
        return StepOutcome.Continue()
    }

    private fun summary(run: Run): String = buildString {
        append("Offerings done")
        if (run.completed.isNotEmpty()) append("; completed ${run.completed.joinToString()}")
        if (run.skipped.isNotEmpty()) append("; skipped ${run.skipped.entries.joinToString { "${it.key} (${it.value})" }}")
    }

    override fun onStop(ctx: ExecContext) {
        runs.remove(ctx.taskId)
    }

    companion object {
        const val DEATH_LIMIT = 3
        const val NO_PROGRESS_LIMIT = 5
    }
}
