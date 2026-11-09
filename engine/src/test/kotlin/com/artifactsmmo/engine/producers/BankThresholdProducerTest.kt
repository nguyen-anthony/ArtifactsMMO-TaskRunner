package com.artifactsmmo.engine.producers

import com.artifactsmmo.client.models.CraftInfo
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Resource
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.core.task.BankThreshold
import com.artifactsmmo.core.task.ThresholdAction
import com.artifactsmmo.core.task.ThresholdSettings
import com.artifactsmmo.core.task.ThresholdState
import com.artifactsmmo.domain.queue.StopCondition
import com.artifactsmmo.domain.task.CraftMode
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.config.ConfigStore
import com.artifactsmmo.engine.config.TypedConfigs
import com.artifactsmmo.engine.scheduler.BankThresholdProducer
import com.artifactsmmo.engine.worker.FakeTaskQueue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BankThresholdProducerTest {
    private class MemConfigs : ConfigStore {
        val data = mutableMapOf<String, MutableMap<String, JsonElement>>()
        override suspend fun list(kind: String) = data[kind].orEmpty().toMap()
        override suspend fun put(kind: String, key: String, value: JsonElement) { data.getOrPut(kind) { mutableMapOf() }[key] = value }
        override suspend fun delete(kind: String, key: String) { data[kind]?.remove(key) }
    }

    private val cookedShrimp = Item(
        name = "Cooked Shrimp", code = "cooked_shrimp", level = 10, type = "consumable", subtype = "food",
        description = "", tradeable = true,
        craft = CraftInfo(skill = "cooking", level = 10, items = listOf(SimpleItem("shrimp", 1)), quantity = 1),
    )
    private val spot = Resource("Shrimp Spot", "shrimp_spot", "fishing", 10, emptyList())

    private var now = 0L
    private val queue = FakeTaskQueue { now }
    private val configs = TypedConfigs(MemConfigs())
    private var bank = mapOf("cooked_shrimp" to 24, "shrimp" to 300)
    private var reserves: Map<String, Int> = emptyMap()
    private val producer = BankThresholdProducer(
        queue, configs, bank = { bank },
        itemOf = { if (it == "cooked_shrimp") cookedShrimp else null },
        resourcesDropping = { if (it == "shrimp") listOf(spot) else emptyList() },
        onReserves = { reserves = it },
        clock = { now },
    )

    @Test
    fun craftRuleQueuesAtMostWhatTheBankCanCraftAndOnlyOnce() = runTest {
        configs.putThreshold(BankThreshold("cooked_shrimp", minQuantity = 100, action = ThresholdAction.CRAFT, batch = 500))

        val status = producer.tick().getValue("cooked_shrimp")
        producer.tick()

        assertEquals(ThresholdState.QUEUED, status.state)
        val task = queue.list().single()
        assertEquals(TaskSpec.Craft("cooking", "cooked_shrimp", "Cooked Shrimp", CraftMode.BANK, 300), TaskSpec.fromJson(task.spec))
        assertEquals(ThresholdSettings().defaultPriority, task.priority)
        assertEquals(mapOf("cooking" to 10), task.requirements.minSkillLevels)
        assertEquals(BankThresholdProducer.key("cooked_shrimp"), task.dedupeKey)
        assertEquals(mapOf("cooked_shrimp" to 100), reserves)
    }

    @Test
    fun craftRuleWithoutIngredientsIsBlockedAndFailedTasksBackOff() = runTest {
        configs.putThreshold(BankThreshold("cooked_shrimp", minQuantity = 100, action = ThresholdAction.CRAFT, batch = 500, priority = 55))
        bank = mapOf("cooked_shrimp" to 24)
        assertEquals(ThresholdState.BLOCKED, producer.tick().getValue("cooked_shrimp").state)
        assertTrue(queue.list().isEmpty())

        bank = mapOf("cooked_shrimp" to 24, "shrimp" to 10)
        producer.tick()
        val task = queue.list().single()
        assertEquals(55, task.priority)
        queue.fail(task.id, "boom", retry = false)

        now += 10 * 60_000L
        assertEquals(ThresholdState.BACKOFF, producer.tick().getValue("cooked_shrimp").state)
        now += 30 * 60_000L
        assertEquals(ThresholdState.QUEUED, producer.tick().getValue("cooked_shrimp").state)
        assertEquals(2, queue.list().size)
    }

    @Test
    fun gatherRuleQueuesGatherTaskWithCountStopAndHealthyItemsStayOk() = runTest {
        configs.putThreshold(BankThreshold("shrimp", minQuantity = 1000, action = ThresholdAction.GATHER, batch = 2000, reserve = false))
        configs.putThreshold(BankThreshold("cooked_shrimp", minQuantity = 10, action = ThresholdAction.CRAFT, batch = 500))

        val statuses = producer.tick()

        assertEquals(ThresholdState.OK, statuses.getValue("cooked_shrimp").state)
        val task = queue.list().single()
        assertEquals(TaskSpec.Gather("fishing", "shrimp_spot", "Shrimp Spot"), TaskSpec.fromJson(task.spec))
        assertEquals(StopCondition.Count(2000), task.stopCondition)
        assertEquals(mapOf("fishing" to 10), task.requirements.minSkillLevels)
        assertEquals(mapOf("cooked_shrimp" to 10), reserves)
    }
}
