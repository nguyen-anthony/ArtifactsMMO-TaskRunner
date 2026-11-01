package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.CharacterSeasonalOfferings
import com.artifactsmmo.client.models.OfferingFavor
import com.artifactsmmo.client.models.OfferingRequirement
import com.artifactsmmo.client.models.SeasonalOffering
import com.artifactsmmo.client.models.SelectedOffering
import com.artifactsmmo.core.offering.AcquirePlan
import com.artifactsmmo.core.offering.AcquireStep
import com.artifactsmmo.core.task.StepResult
import com.artifactsmmo.domain.queue.QueuedTask
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.queue.TaskStatus
import com.artifactsmmo.domain.task.TaskSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class DailyOfferingExecutorTest {

    private val today = "2026-11-01"
    private val clock = object : Clock { override fun now() = Instant.parse("2026-11-01T12:00:00Z") }

    private fun offering(category: String, min: Int, max: Int) = SeasonalOffering(
        id = "${category}_$min", category = category, type = "resource", levelMin = min, levelMax = max,
        requirement = OfferingRequirement("${category}_item_$min", 5),
        favor = OfferingFavor("resource", "${category}_spot_$min"),
    )

    private inner class FakeOps(categories: List<String>) : OfferingOps {
        val offerings = categories.flatMap { listOf(offering(it, 10, 20), offering(it, 21, 30)) }
        val accepted = mutableListOf<String>()
        val completed = mutableListOf<String>()
        val worked = mutableListOf<AcquireStep>()
        /** requirement code -> plan; default = one gather step away. */
        val plans = mutableMapOf<String, AcquirePlan>()
        var levels = mapOf<String, Int>()
        var workResult: (AcquireStep) -> StepResult = { step ->
            plans[(step as AcquireStep.Gather).itemCode] = AcquirePlan.Ready
            StepResult.Gathered(1, emptyList())
        }
        var statusError: Int? = null
        var expiresAt = "2026-11-02T00:00:00Z"

        private fun byId(id: String) = offerings.first { it.id == id }

        override suspend fun status(character: String): CharacterSeasonalOfferings {
            statusError?.let { throw ArtifactsApiException(it, "error") }
            return CharacterSeasonalOfferings(
                date = today, expiresAt = expiresAt, offerings = offerings,
                selectedOfferings = accepted.map { id ->
                    val o = byId(id)
                    SelectedOffering(id, o.category, if (id in completed) "completed" else "accepted",
                        requirement = o.requirement, favor = o.favor)
                },
                completedCategories = completed.map { byId(it).category },
            )
        }

        override suspend fun accept(character: String, offeringId: String) {
            if (accepted.any { byId(it).category == byId(offeringId).category }) throw ArtifactsApiException(576, "selected")
            accepted += offeringId
        }

        override suspend fun complete(character: String, offeringId: String): Int {
            if (plans[byId(offeringId).requirement.code] != AcquirePlan.Ready) throw ArtifactsApiException(478, "missing")
            completed += offeringId
            return 100
        }

        override suspend fun level(character: String, category: String) = levels[category] ?: 1

        override suspend fun plan(character: String, code: String, quantity: Int, favor: OfferingFavor) =
            plans.getOrPut(code) { AcquirePlan.Next(AcquireStep.Gather(favor.targetCode, "mining", code), 100.0) }

        override suspend fun goToAltar(character: String, carryCode: String?, carryQuantity: Int, status: (String) -> Unit) = Unit

        override suspend fun work(character: String, step: AcquireStep, prepare: Boolean, status: (String) -> Unit): StepResult {
            worked += step
            return workResult(step)
        }
    }

    private fun ctx(spec: TaskSpec.DailyOffering = TaskSpec.DailyOffering()) = ExecContext(
        character = "Ront",
        task = QueuedTask(
            id = 1, type = spec.typeName, spec = spec.toJson(), priority = 70, source = TaskSource.SCHEDULE,
            status = TaskStatus.RUNNING, createdAtMillis = 0, updatedAtMillis = 0,
        ),
        spec = spec, state = RunState(), status = {},
    )

    private suspend fun runToEnd(executor: DailyOfferingExecutor, ctx: ExecContext): StepOutcome {
        repeat(100) {
            val outcome = executor.step(ctx)
            if (outcome !is StepOutcome.Continue) return outcome
        }
        error("executor did not finish")
    }

    @Test
    fun acceptsMatchingBracketsSourcesAndCompletesEveryCategory() = runTest {
        val ops = FakeOps(listOf("combat", "mining")).apply { levels = mapOf("combat" to 25, "mining" to 3) }
        val ctx = ctx()
        val outcome = runToEnd(DailyOfferingExecutor(ops, clock), ctx)

        assertIs<StepOutcome.Complete>(outcome)
        assertEquals(listOf("combat_21", "mining_10"), ops.accepted)
        assertEquals(listOf("combat_21", "mining_10"), ops.completed)
        assertEquals(2, ops.worked.size)
        assertEquals(2, ctx.state.tasksCompleted)
    }

    @Test
    fun fallsBackToCheapestFeasibleBracketAndSkipsImpossibleCategories() = runTest {
        val ops = FakeOps(listOf("combat", "mining")).apply {
            levels = mapOf("combat" to 25)
            plans["combat_item_21"] = AcquirePlan.Infeasible("can't beat it")
            plans["mining_item_10"] = AcquirePlan.Infeasible("too low")
            plans["mining_item_21"] = AcquirePlan.Infeasible("too low")
        }
        val outcome = runToEnd(DailyOfferingExecutor(ops, clock), ctx())

        assertEquals(listOf("combat_10"), ops.completed)
        assertTrue((outcome as StepOutcome.Complete).message.contains("skipped mining"))
    }

    @Test
    fun categoryFilterAndAlreadyCompletedCategoriesAreRespected() = runTest {
        val ops = FakeOps(listOf("combat", "mining", "fishing")).apply {
            accepted += "fishing_10"; completed += "fishing_10"
        }
        runToEnd(DailyOfferingExecutor(ops, clock), ctx(TaskSpec.DailyOffering(listOf("mining", "fishing"))))

        assertEquals(listOf("fishing_10", "mining_10"), ops.completed)
    }

    @Test
    fun resumesAnAcceptedOfferingWithoutReaccepting() = runTest {
        val ops = FakeOps(listOf("combat")).apply { accepted += "combat_21" }
        runToEnd(DailyOfferingExecutor(ops, clock), ctx())

        assertEquals(listOf("combat_21"), ops.accepted)
        assertEquals(listOf("combat_21"), ops.completed)
    }

    @Test
    fun repeatedDeathsSkipTheCategory() = runTest {
        val ops = FakeOps(listOf("combat")).apply {
            plans["combat_item_10"] = AcquirePlan.Next(AcquireStep.Fight("wolf", "combat_item_10"), 100.0)
            workResult = { StepResult.FightLost("dead") }
        }
        val outcome = runToEnd(DailyOfferingExecutor(ops, clock), ctx())

        assertEquals(DailyOfferingExecutor.DEATH_LIMIT, ops.worked.size)
        assertTrue(ops.completed.isEmpty())
        assertTrue((outcome as StepOutcome.Complete).message.contains("died"))
    }

    @Test
    fun targetedOfferingIgnoresBracketChoiceAndOnlyDoesThatOffering() = runTest {
        val ops = FakeOps(listOf("combat", "mining")).apply { levels = mapOf("combat" to 5) }
        val outcome = runToEnd(DailyOfferingExecutor(ops, clock), ctx(TaskSpec.DailyOffering(offeringId = "combat_21")))

        assertIs<StepOutcome.Complete>(outcome)
        assertEquals(listOf("combat_21"), ops.accepted)
        assertEquals(listOf("combat_21"), ops.completed)
    }

    @Test
    fun targetedOfferingResumesItsAcceptanceAndReportsConflicts() = runTest {
        val resumed = FakeOps(listOf("combat")).apply { accepted += "combat_21" }
        runToEnd(DailyOfferingExecutor(resumed, clock), ctx(TaskSpec.DailyOffering(offeringId = "combat_21")))
        assertEquals(listOf("combat_21"), resumed.completed)

        val conflict = FakeOps(listOf("combat")).apply { accepted += "combat_10" }
        val fail = DailyOfferingExecutor(conflict, clock).step(ctx(TaskSpec.DailyOffering(offeringId = "combat_21")))
        assertTrue((fail as StepOutcome.Fail).message.contains("combat_10 is already accepted"))

        val done = FakeOps(listOf("combat")).apply { accepted += "combat_21"; completed += "combat_21" }
        assertIs<StepOutcome.Complete>(DailyOfferingExecutor(done, clock).step(ctx(TaskSpec.DailyOffering(offeringId = "combat_21"))))

        val unknown = FakeOps(listOf("combat"))
        assertIs<StepOutcome.Fail>(DailyOfferingExecutor(unknown, clock).step(ctx(TaskSpec.DailyOffering(offeringId = "fishing_10"))))
    }

    @Test
    fun infeasibleTargetedOfferingFailsWithoutLockingTheCategory() = runTest {
        val ops = FakeOps(listOf("combat")).apply { plans["combat_item_21"] = AcquirePlan.Infeasible("can't beat it") }
        val fail = runToEnd(DailyOfferingExecutor(ops, clock), ctx(TaskSpec.DailyOffering(offeringId = "combat_21")))

        assertTrue((fail as StepOutcome.Fail).message.contains("can't beat it"))
        assertTrue(ops.accepted.isEmpty())
    }

    @Test
    fun membershipErrorFailsAndExpiredDayCompletes() = runTest {
        val noMember = FakeOps(listOf("combat")).apply { statusError = 451 }
        assertIs<StepOutcome.Fail>(DailyOfferingExecutor(noMember, clock).step(ctx()))

        val expired = FakeOps(listOf("combat")).apply { expiresAt = "2026-11-01T00:00:00Z" }
        assertIs<StepOutcome.Complete>(DailyOfferingExecutor(expired, clock).step(ctx()))
        assertTrue(expired.accepted.isEmpty())
    }
}
