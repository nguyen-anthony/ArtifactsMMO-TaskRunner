package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.SimpleItem
import kotlin.test.Test
import kotlin.test.assertEquals

class NpcTradePlanningTest {
    @Test
    fun `sell down to keeps the floor and zero sells everything`() {
        assertEquals(250, EventExecutor.sellDownTo(owned = 300, keep = 50))
        assertEquals(300, EventExecutor.sellDownTo(owned = 300, keep = 0))
        assertEquals(0, EventExecutor.sellDownTo(owned = 30, keep = 50))
    }

    @Test
    fun `batches are bounded by free quantity and free slots`() {
        val wanted = linkedMapOf("ash_wood" to 250, "birch_wood" to 40, "coconut" to 10)
        assertEquals(listOf(SimpleItem("ash_wood", 100)), EventExecutor.packBatch(wanted, freeQuantity = 100, freeSlots = 5))
        assertEquals(
            listOf(SimpleItem("ash_wood", 250), SimpleItem("birch_wood", 40), SimpleItem("coconut", 10)),
            EventExecutor.packBatch(wanted, freeQuantity = 500, freeSlots = 5),
        )
        assertEquals(listOf(SimpleItem("ash_wood", 250)), EventExecutor.packBatch(wanted, freeQuantity = 500, freeSlots = 1))
        assertEquals(emptyList(), EventExecutor.packBatch(wanted, freeQuantity = 0, freeSlots = 5))
    }
}
