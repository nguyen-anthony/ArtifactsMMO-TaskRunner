package com.artifactsmmo.engine.producers

import com.artifactsmmo.client.models.ActiveEvent
import com.artifactsmmo.client.models.EventSpawnMap
import com.artifactsmmo.client.models.MapContent
import com.artifactsmmo.client.models.MapInteraction
import com.artifactsmmo.client.models.Raid
import com.artifactsmmo.client.models.RaidSchedule
import com.artifactsmmo.core.task.EventConfig
import com.artifactsmmo.core.task.RaidConfig
import com.artifactsmmo.domain.queue.GroupRole
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.queue.TaskStatus
import com.artifactsmmo.domain.task.EventMap
import com.artifactsmmo.domain.task.MemberPlan
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.config.ConfigStore
import com.artifactsmmo.engine.config.TypedConfigs
import com.artifactsmmo.engine.realtime.EventTaskProducer
import com.artifactsmmo.engine.scheduler.RaidTaskProducer
import com.artifactsmmo.engine.scheduler.Schedule
import com.artifactsmmo.engine.scheduler.ScheduleRunner
import com.artifactsmmo.engine.scheduler.ScheduleStore
import com.artifactsmmo.engine.worker.FakeTaskQueue
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class ProducerTest {

    private class MemConfigs : ConfigStore {
        val data = mutableMapOf<String, MutableMap<String, JsonElement>>()
        override suspend fun list(kind: String) = data[kind].orEmpty().toMap()
        override suspend fun put(kind: String, key: String, value: JsonElement) { data.getOrPut(kind) { mutableMapOf() }[key] = value }
        override suspend fun delete(kind: String, key: String) { data[kind]?.remove(key) }
    }

    private fun event(code: String, type: String = "resource") = ActiveEvent(
        name = code, code = code,
        map = EventSpawnMap(mapId = 1, x = 3, y = 4, layer = "overworld",
            interactions = MapInteraction(MapContent(type, "magic_tree")), expiration = "2030-01-01T00:00:00Z"),
    )

    @Test
    fun `event spawn queues one task per planned character, replays are deduped, removal cancels`() = runTest {
        var now = 0L
        val queue = FakeTaskQueue { now }
        val configs = TypedConfigs(MemConfigs()).also { it.putEvent(EventConfig("magic_tree", enabled = true)) }
        val producer = EventTaskProducer(emptyFlow(), queue, configs, planner = { e, _ ->
            listOf("alice", "bob").map { it to TaskSpec.EventGather(e.code, "magic_tree", "woodcutting", EventMap(3, 4)) }
        })

        producer.onSpawn(event("magic_tree"))
        producer.onSpawn(event("magic_tree")) // reconnect replay
        val tasks = queue.list()
        assertEquals(listOf("alice", "bob"), tasks.map { it.assignedCharacter })
        assertTrue(tasks.all { it.source == TaskSource.EVENT && it.expiresAtMillis == Instant.parse("2030-01-01T00:00:00Z").toEpochMilliseconds() })
        assertTrue(producer.isActive("magic_tree"))

        producer.onSpawn(event("unconfigured"))
        assertEquals(2, queue.list().size)

        producer.onRemoved("magic_tree")
        assertTrue(queue.list().all { it.status == TaskStatus.CANCELLED })
        assertTrue(!producer.isActive("magic_tree"))
    }

    @Test
    fun `raid is queued once as a group inside the lead window and cancelled when it ends`() = runTest {
        var now = 0L
        val queue = FakeTaskQueue { now }
        val configs = TypedConfigs(MemConfigs()).also {
            it.putRaid(RaidConfig("lich_raid", enabled = true, initiatorName = "alice", participantNames = listOf("bob", "carol"), leadTimeMinutes = 5))
        }
        val start = 60 * 60_000L
        var planned = 0
        val raid = Raid("lich_raid", "Lich", "", "lich", RaidSchedule(startHourUtc = 1, startMinuteUtc = 0, durationHours = 2),
            status = "scheduled", nextStartAt = Instant.fromEpochMilliseconds(start))
        val producer = RaidTaskProducer(queue, configs, { listOf(raid) },
            planner = { members, _, _ -> planned++; members.associateWith { MemberPlan() } }, clock = { now })

        now = start - 10 * 60_000 // too early
        producer.tick()
        assertTrue(queue.list().isEmpty())

        now = start - 4 * 60_000
        producer.tick(); producer.tick()
        assertEquals(1, planned, "planning is skipped once the instance is queued")
        val rows = queue.list()
        val parent = rows.single { it.groupRole == GroupRole.GROUP }
        assertEquals("raid:lich_raid:$start", parent.dedupeKey)
        assertEquals(start + 2 * 3_600_000, parent.expiresAtMillis)
        val slots = rows.filter { it.groupRole != GroupRole.GROUP }
        assertEquals(listOf("alice", "bob", "carol"), slots.map { it.assignedCharacter })
        assertEquals(GroupRole.INITIATOR, slots.first().groupRole)
        val spec = TaskSpec.fromJson(parent.spec) as TaskSpec.BossFight
        assertEquals(start, spec.scheduledStartAtMillis)

        producer.cancelRaid("lich_raid", "raid_ended")
        assertTrue(queue.list().all { it.status == TaskStatus.CANCELLED })
    }

    @Test
    fun `schedule does not pile up while the previous run is live`() = runTest {
        var now = 0L
        val queue = FakeTaskQueue { now }
        val store = object : ScheduleStore {
            var next = 0L
            override suspend fun due(nowMillis: Long) = if (nowMillis >= next) listOf(
                Schedule(1, "copper", 60, NewTask("gather", TaskSpec.Gather("mining", "copper_rocks").toJson()), next)
            ) else emptyList()
            override suspend fun markRun(id: Long, ranAtMillis: Long, nextRunAtMillis: Long) { next = nextRunAtMillis }
        }
        val runner = ScheduleRunner(store, queue, clock = { now })
        runner.tick(); now = 60_000; runner.tick()
        assertEquals(1, queue.list().size)
        assertEquals(TaskSource.SCHEDULE, queue.list().single().source)
        queue.cancel(queue.list().single().id)
        now = 120_000; runner.tick()
        assertEquals(2, queue.list().size)
    }
}
