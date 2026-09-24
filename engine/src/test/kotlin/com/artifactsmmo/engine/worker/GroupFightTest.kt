package com.artifactsmmo.engine.worker

import com.artifactsmmo.core.task.BossEncounterCoordinator
import com.artifactsmmo.core.task.StepResult
import com.artifactsmmo.core.task.TaskType
import com.artifactsmmo.domain.queue.GroupSlot
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.StopCondition
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.queue.TaskStatus
import com.artifactsmmo.domain.task.MemberPlan
import com.artifactsmmo.domain.task.TaskSpec
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GroupFightTest {

    /** Stand-in for the legacy boss step, using the real rendezvous coordinator. */
    private class FakeBossOps : BossOps {
        val fights = mutableListOf<Pair<String, List<String>>>()
        val provisioned = mutableListOf<String>()
        override suspend fun provision(character: String, plan: MemberPlan, status: (String) -> Unit) {
            provisioned += character; delay(2_000)
        }
        override suspend fun step(character: String, task: TaskType.BossFight, encounters: BossEncounterCoordinator,
                                  status: (String) -> Unit, previous: com.artifactsmmo.client.models.Character?): StepResult {
            return if (task.isInitiator) {
                try { encounters.awaitParticipants(character) } catch (_: ClosedReceiveChannelException) { return StepResult.Waiting }
                fights += character to task.participantNames
                delay(5_000) // fight cooldown
                StepResult.FightWon(10, 5)
            } else {
                try { encounters.signalReady(character) } catch (_: ClosedSendChannelException) { return StepResult.Waiting }
                delay(5_000)
                StepResult.Waiting
            }
        }
        override suspend fun restock(character: String, task: TaskType.BossFight, status: (String) -> Unit) = true
    }

    private class Settings : CharacterSettingsStore {
        override suspend fun get(name: String) = CharacterSettings(name)
        override suspend fun put(settings: CharacterSettings) {}
    }

    private class Harness(scope: TestScope) {
        val clock = { scope.testScheduler.currentTime }
        val queue = FakeTaskQueue(clock)
        val ops = FakeBossOps()
        val boss = BossFightExecutor(ops, queue, GroupCoordinator())
        val workers = listOf("alice", "bob", "carol").map { name ->
            CharacterWorker(
                character = name, queue = queue, executors = { boss }, settings = Settings(),
                view = { CharacterSnapshot(name, 30, emptyMap(), 10) }, awaitCooldown = {},
                config = WorkerConfig(idlePollMillis = 5_000, checkpointMillis = 0), clock = clock,
            )
        }
    }

    private fun bossTask(stop: StopCondition? = null) = NewTask(
        type = "boss_fight",
        spec = TaskSpec.BossFight("lich", plans = mapOf("alice" to MemberPlan(foodCode = "fish", foodQuantity = 5),
            "bob" to MemberPlan(), "carol" to MemberPlan())).toJson(),
        source = TaskSource.RAID, stopCondition = stop,
    )

    private fun TestScope.advance(ms: Long) { advanceTimeBy(ms); runCurrent() }

    @Test
    fun `initiator fights with the members who claimed the open slots, and the group completes together`() = runTest {
        val h = Harness(this)
        val g = h.queue.enqueueGroup(bossTask(StopCondition.Count(3)), listOf(GroupSlot("alice"), GroupSlot(), GroupSlot()))!!
        h.workers.forEach { it.start(backgroundScope) }
        advance(120_000)

        assertEquals(setOf("alice", "bob", "carol"), h.ops.provisioned.toSet())
        assertEquals(3, h.ops.fights.size)
        h.ops.fights.forEach { (who, participants) ->
            assertEquals("alice", who)
            assertEquals(setOf("bob", "carol"), participants.toSet())
        }
        val group = h.queue.group(g.parent.id)!!
        assertEquals(TaskStatus.COMPLETED, group.parent.status)
        assertTrue(group.slots.all { it.status == TaskStatus.COMPLETED })
        assertTrue(h.workers.all { it.status.value.taskId == null })
    }

    @Test
    fun `a member leaving for good disbands the whole group`() = runTest {
        val h = Harness(this)
        val g = h.queue.enqueueGroup(bossTask(), listOf(GroupSlot("alice"), GroupSlot("bob"), GroupSlot("carol")))!!
        h.workers.forEach { it.start(backgroundScope) }
        advance(30_000)
        assertTrue(h.ops.fights.isNotEmpty())

        h.queue.cancel(g.slots.first { it.assignedCharacter == "carol" }.id, "user stopped carol")
        advance(60_000)
        val group = h.queue.group(g.parent.id)!!
        assertEquals(TaskStatus.CANCELLED, group.parent.status)
        assertTrue(group.slots.all { it.status == TaskStatus.CANCELLED })
        assertTrue(h.workers.all { it.status.value.taskId == null })
    }

    @Test
    fun `group waits until every slot is claimed`() = runTest {
        val h = Harness(this)
        // Carol is busy with other work (fake: only alice and bob run).
        h.queue.enqueueGroup(bossTask(), listOf(GroupSlot("alice"), GroupSlot("bob"), GroupSlot("carol")))!!
        h.workers.take(2).forEach { it.start(backgroundScope) }
        advance(30_000)
        assertTrue(h.ops.fights.isEmpty())
        assertTrue(h.workers[0].status.value.message.startsWith("Waiting for group: 2/3"))
    }
}
