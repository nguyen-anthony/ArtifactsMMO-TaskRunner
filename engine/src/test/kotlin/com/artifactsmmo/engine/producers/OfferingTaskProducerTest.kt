package com.artifactsmmo.engine.producers

import com.artifactsmmo.core.task.OfferingConfig
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.config.ConfigStore
import com.artifactsmmo.engine.config.TypedConfigs
import com.artifactsmmo.engine.scheduler.OfferingTaskProducer
import com.artifactsmmo.engine.worker.FakeTaskQueue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfferingTaskProducerTest {

    private class MemConfigs : ConfigStore {
        val data = mutableMapOf<String, MutableMap<String, JsonElement>>()
        override suspend fun list(kind: String) = data[kind].orEmpty().toMap()
        override suspend fun put(kind: String, key: String, value: JsonElement) { data.getOrPut(kind) { mutableMapOf() }[key] = value }
        override suspend fun delete(kind: String, key: String) { data[kind]?.remove(key) }
    }

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun `queues one task per enabled character per UTC day, expiring at the rotation`() = runTest {
        var now = millis("2026-11-01T10:00:00Z")
        val queue = FakeTaskQueue { now }
        val configs = TypedConfigs(MemConfigs()).also {
            it.putOffering(OfferingConfig("bob", enabled = false))
            it.putOffering(OfferingConfig("carol", categories = listOf("combat"), priority = 40))
        }
        val producer = OfferingTaskProducer(queue, configs, { listOf("alice", "bob", "carol") }, clock = { now })

        producer.tick()
        producer.tick()
        val first = queue.list()
        assertEquals(listOf("alice", "carol"), first.map { it.assignedCharacter })
        assertTrue(first.all { it.source == TaskSource.SCHEDULE && it.expiresAtMillis == millis("2026-11-02T00:00:00Z") })
        assertEquals(listOf(OfferingConfig.DEFAULT_PRIORITY, 40), first.map { it.priority })
        assertEquals(TaskSpec.DailyOffering(listOf("combat")), TaskSpec.fromJson(first[1].spec))
        assertEquals("offering:alice:2026-11-01", first[0].dedupeKey)

        // Completed today: not re-created until the rotation.
        first.forEach { queue.complete(it.id, "done") }
        producer.tick()
        assertEquals(2, queue.list().size)

        now = millis("2026-11-02T00:00:30Z")
        producer.tick()
        assertEquals(listOf("offering:alice:2026-11-02", "offering:carol:2026-11-02"), queue.list().drop(2).map { it.dedupeKey })
    }
}
