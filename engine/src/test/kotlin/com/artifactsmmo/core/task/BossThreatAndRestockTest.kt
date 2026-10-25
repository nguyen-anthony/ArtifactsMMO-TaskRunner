package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleEffect
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BossThreatAndRestockTest {

    // ── Support threat filtering (Pixie raid: Shuckle kept mithril rings, out-threatened Ront) ──

    @Test
    fun threatGearIsDroppedWhenAThreatFreeAlternativeExists() {
        val items = listOf(item("mithril_ring", threat = 10), item("gold_ring"))
        assertEquals(listOf("gold_ring"), withoutThreatIfPossible(items).map { it.code })
    }

    @Test
    fun threatGearIsKeptWhenItIsTheOnlyOption() {
        val items = listOf(item("mithril_ring", threat = 10))
        assertEquals(listOf("mithril_ring"), withoutThreatIfPossible(items).map { it.code })
    }

    @Test
    fun zeroThreatEffectIsNotThreat() {
        assertFalse(item("plain", threat = 0).hasThreat())
        assertTrue(item("taunt", threat = 4).hasThreat())
    }

    // ── Boss restock trigger (Pixie raid: Ront's health potions ran dry, air boost didn't) ──

    private val plan = listOf(
        GearOptimizer.UtilityEquipAction("utility1", "greater_health_potion", 100, "bank"),
        GearOptimizer.UtilityEquipAction("utility2", "air_boost_potion", 100, "bank"),
    )

    @Test
    fun restocksWhenOnePlannedPotionRunsDryWhileTheOtherHasStock() = runTest {
        val equipped = mapOf("utility1" to ("greater_health_potion" to 0), "utility2" to ("air_boost_potion" to 93))
        assertTrue(anyPlannedPotionExhausted(plan, { equipped.getValue(it) }, { 0 }, { 500 }))
    }

    @Test
    fun noRestockWhileInventoryStillHoldsARefill() = runTest {
        val equipped = mapOf("utility1" to ("" to 0), "utility2" to ("air_boost_potion" to 93))
        assertFalse(anyPlannedPotionExhausted(plan, { equipped.getValue(it) },
            { if (it == "greater_health_potion") 50 else 0 }, { 500 }))
    }

    @Test
    fun noRestockWhenBankCannotSupplyTheMissingPotion() = runTest {
        val equipped = mapOf("utility1" to ("" to 0), "utility2" to ("air_boost_potion" to 93))
        assertFalse(anyPlannedPotionExhausted(plan, { equipped.getValue(it) }, { 0 },
            { if (it == "greater_health_potion") 0 else 500 }))
    }

    @Test
    fun noRestockWithoutPlannedUtilities() = runTest {
        assertFalse(anyPlannedPotionExhausted(emptyList(), { "" to 0 }, { 0 }, { 500 }))
    }

    private fun item(code: String, threat: Int = 0) = Item(
        name = code, code = code, level = 40, type = "ring", subtype = "", description = "",
        effects = buildList {
            add(SimpleEffect("hp", 50, ""))
            if (threat != 0) add(SimpleEffect("threat", threat, ""))
        },
        tradeable = true
    )
}
