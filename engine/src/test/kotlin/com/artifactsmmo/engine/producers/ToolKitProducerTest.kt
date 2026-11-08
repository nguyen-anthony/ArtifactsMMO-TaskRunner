package com.artifactsmmo.engine.producers

import com.artifactsmmo.client.models.CraftInfo
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleEffect
import com.artifactsmmo.core.task.ToolHolder
import com.artifactsmmo.core.task.ToolKitConfig
import com.artifactsmmo.core.task.ToolKitPlan
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.config.ConfigStore
import com.artifactsmmo.engine.config.TypedConfigs
import com.artifactsmmo.engine.scheduler.ToolKitProducer
import com.artifactsmmo.engine.worker.FakeTaskQueue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ToolKitProducerTest {
    private class MemConfigs : ConfigStore {
        val data = mutableMapOf<String, MutableMap<String, JsonElement>>()
        override suspend fun list(kind: String) = data[kind].orEmpty().toMap()
        override suspend fun put(kind: String, key: String, value: JsonElement) { data.getOrPut(kind) { mutableMapOf() }[key] = value }
        override suspend fun delete(kind: String, key: String) { data[kind]?.remove(key) }
    }

    private fun tool(code: String, level: Int, value: Int) = Item(
        name = code, code = code, level = level, type = "weapon", subtype = "tool", description = "",
        effects = listOf(SimpleEffect("cooking_crafting", value, "")), tradeable = true,
        craft = CraftInfo(skill = "weaponcrafting", level = level),
    )

    @Test
    fun queuesSyncsForCharactersThatCanChangeAndRecyclesBankSurplus() = runTest {
        val queue = FakeTaskQueue { 0L }
        val configs = TypedConfigs(MemConfigs())
        val holders = listOf(
            ToolHolder("A", mapOf("cooking" to 10), mapOf("ash_rolling_pin" to 1)),
            ToolHolder("B", mapOf("cooking" to 10), mapOf("iron_knife" to 1)),
        )
        var published: ToolKitPlan? = null
        val producer = ToolKitProducer(
            queue, configs, holders = { holders },
            tools = { listOf(tool("ash_rolling_pin", 1, -10), tool("iron_knife", 10, -20)) },
            bank = { mapOf("iron_knife" to 1, "ash_rolling_pin" to 2) },
            onPlan = { published = it },
        )

        producer.tick()
        producer.tick()
        assertNotNull(published)
        val tasks = queue.list()
        val sync = tasks.single { it.type == "toolkit_sync" }
        assertEquals("A", sync.assignedCharacter)
        assertEquals(ToolKitProducer.syncKey("A"), sync.dedupeKey)
        val recycle = tasks.single { it.type == "bank_recycle" }
        // Three pins are redundant, but only the two in the bank can be recycled now.
        assertEquals(TaskSpec.BankRecycle("ash_rolling_pin", 2, "weaponcrafting", "ash_rolling_pin"), TaskSpec.fromJson(recycle.spec))
        assertEquals(ToolKitConfig().recyclePriority, recycle.priority)

        configs.putToolKit(ToolKitConfig(enabled = false))
        producer.tick()
        assertNull(published)
    }
}
