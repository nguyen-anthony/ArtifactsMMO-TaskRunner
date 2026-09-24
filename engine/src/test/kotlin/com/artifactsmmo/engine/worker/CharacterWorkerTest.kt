package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.StopCondition
import com.artifactsmmo.domain.queue.TaskRequirements
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.queue.TaskStatus
import com.artifactsmmo.domain.task.TaskSpec
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CharacterWorkerTest {

    /**
     * Executor whose behaviour is chosen by the Gather spec's resourceCode:
     *  "three" completes after 3 steps, "forever" never ends, "boom" always throws,
     *  "fatal" throws a 452 (bad token).
     */
    private class ScriptedExecutor : TaskExecutor {
        val started = mutableListOf<Long>()
        val cleaned = mutableListOf<Long>()
        override suspend fun start(ctx: ExecContext) { started += ctx.taskId; ctx.state.prepared = true }
        override suspend fun step(ctx: ExecContext): StepOutcome {
            val mode = (ctx.spec as TaskSpec.Gather).resourceCode
            when (mode) {
                "boom" -> error("kaboom")
                "fatal" -> throw ArtifactsApiException(452, "token invalid")
            }
            ctx.state.gathers++
            return if (mode == "three" && ctx.state.gathers >= 3) StepOutcome.Complete("done")
            else StepOutcome.Continue(1_000)
        }
        override suspend fun cleanup(ctx: ExecContext) { cleaned += ctx.taskId }
    }

    private class MapSettings : CharacterSettingsStore {
        val map = mutableMapOf<String, CharacterSettings>()
        override suspend fun get(name: String) = map[name] ?: CharacterSettings(name)
        override suspend fun put(settings: CharacterSettings) { map[settings.name] = settings }
    }

    private class Harness(scope: TestScope) {
        val clock = { scope.testScheduler.currentTime }
        val queue = FakeTaskQueue(clock)
        val exec = ScriptedExecutor()
        val settings = MapSettings()
        val control = WorkerControl()
        var snapshot = CharacterSnapshot("alice", level = 10, skills = mapOf("mining" to 5), freeInventorySlots = 10)
        val worker = CharacterWorker(
            character = "alice", queue = queue, executors = { exec }, view = { snapshot }, settings = settings,
            awaitCooldown = {}, control = control,
            config = WorkerConfig(idlePollMillis = 5_000, checkpointMillis = 0, controlCheckMillis = 60_000),
            clock = clock,
        )
    }

    private fun gather(mode: String, priority: Int = 50, stop: StopCondition? = null, req: TaskRequirements = TaskRequirements()) =
        NewTask(
            type = "gather", spec = TaskSpec.Gather("mining", mode).toJson(), priority = priority,
            stopCondition = stop, requirements = req,
        )

    private fun TestScope.start(h: Harness) { h.worker.start(backgroundScope); runCurrent() }
    private fun TestScope.advance(ms: Long) { advanceTimeBy(ms); runCurrent() }

    @Test
    fun `claims a task, runs it to completion, then cleans up`() = runTest {
        val h = Harness(this)
        val t = h.queue.enqueue(gather("three"))!!
        start(h)
        advance(10_000)
        val done = h.queue.get(t.id)!!
        assertEquals(TaskStatus.COMPLETED, done.status)
        assertEquals(3, done.checkpoint!!["gathers"]!!.jsonPrimitive.intOrNull)
        assertEquals(listOf(t.id), h.exec.cleaned)
        assertEquals(WorkerState.IDLE, h.worker.status.value.state)
    }

    @Test
    fun `idle worker wakes immediately on a new task`() = runTest {
        val h = Harness(this)
        start(h)
        advance(1_000)
        val t = h.queue.enqueue(gather("forever"))!!
        advance(10) // far less than idlePollMillis
        assertEquals(TaskStatus.RUNNING, h.queue.get(t.id)!!.status)
    }

    @Test
    fun `enqueues and runs the filler when nothing else is available`() = runTest {
        val h = Harness(this)
        h.settings.put(CharacterSettings("alice", filler = gather("forever")))
        start(h)
        advance(100)
        val filler = h.queue.list().single()
        assertEquals(TaskSource.FILLER, filler.source)
        assertEquals("alice", filler.assignedCharacter)
        assertEquals(TaskStatus.RUNNING, filler.status)
    }

    @Test
    fun `higher priority work preempts, then the suspended task resumes from its checkpoint`() = runTest {
        val h = Harness(this)
        val low = h.queue.enqueue(gather("forever", priority = 10))!!
        start(h)
        advance(4_500) // a few gathers
        val high = h.queue.enqueue(gather("three", priority = 80))!!
        advance(1_500)
        assertEquals(TaskStatus.SUSPENDED, h.queue.get(low.id)!!.status)
        val savedGathers = h.queue.get(low.id)!!.checkpoint!!["gathers"]!!.jsonPrimitive.intOrNull!!
        assertTrue(savedGathers >= 3)
        assertTrue(h.exec.cleaned.isEmpty(), "suspend must not run cleanup")

        advance(10_000)
        assertEquals(TaskStatus.COMPLETED, h.queue.get(high.id)!!.status)
        assertEquals(TaskStatus.RUNNING, h.queue.get(low.id)!!.status)
        advance(3_000)
        assertTrue(h.worker.status.value.progress.gathers > savedGathers, "counters continue from the checkpoint")
    }

    @Test
    fun `cancelling the running task stops it and cleans up`() = runTest {
        val h = Harness(this)
        val t = h.queue.enqueue(gather("forever"))!!
        start(h)
        advance(2_500)
        h.queue.cancel(t.id)
        advance(1_500)
        assertEquals(listOf(t.id), h.exec.cleaned)
        assertNull(h.worker.status.value.taskId)
    }

    @Test
    fun `count stop condition completes the task`() = runTest {
        val h = Harness(this)
        val t = h.queue.enqueue(gather("forever", stop = StopCondition.Count(4)))!!
        start(h)
        advance(10_000)
        assertEquals(TaskStatus.COMPLETED, h.queue.get(t.id)!!.status)
        assertEquals(4, h.worker.status.value.progress.gathers)
    }

    @Test
    fun `repeated errors fail the task`() = runTest {
        val h = Harness(this)
        val t = h.queue.enqueue(gather("boom"))!!
        start(h)
        advance(120_000)
        val failed = h.queue.get(t.id)!!
        assertEquals(TaskStatus.FAILED, failed.status)
        assertTrue(failed.lastError!!.contains("consecutive errors"))
    }

    @Test
    fun `fatal API error pauses the worker and returns the task to the queue`() = runTest {
        val h = Harness(this)
        val t = h.queue.enqueue(gather("fatal"))!!
        start(h)
        advance(1_000)
        assertNotNull(h.control.fatalReason.value)
        assertEquals(TaskStatus.SUSPENDED, h.queue.get(t.id)!!.status)
        assertEquals(WorkerState.PAUSED, h.worker.status.value.state)
    }

    @Test
    fun `skips tasks the character does not meet requirements for`() = runTest {
        val h = Harness(this)
        val hard = h.queue.enqueue(gather("forever", priority = 90, req = TaskRequirements(minSkillLevels = mapOf("mining" to 30))))!!
        val easy = h.queue.enqueue(gather("forever", priority = 10))!!
        start(h)
        advance(100)
        assertEquals(TaskStatus.PENDING, h.queue.get(hard.id)!!.status)
        assertEquals(TaskStatus.RUNNING, h.queue.get(easy.id)!!.status)
    }

    @Test
    fun `disabled character claims nothing`() = runTest {
        val h = Harness(this)
        h.settings.put(CharacterSettings("alice", enabled = false))
        val t = h.queue.enqueue(gather("forever"))!!
        start(h)
        advance(10_000)
        assertEquals(TaskStatus.PENDING, h.queue.get(t.id)!!.status)
        assertEquals(WorkerState.DISABLED, h.worker.status.value.state)
    }
}
