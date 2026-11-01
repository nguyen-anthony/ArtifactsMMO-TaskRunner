package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolEffectsTest {
    @Test
    fun season9GatheringCodesAndLegacyCodesAreBothRecognized() {
        val newPick = tool("iron_pickaxe", 10, "mining_gathering" to -20)
        val oldPick = tool("copper_pickaxe", 1, "mining" to -10)
        assertEquals(20, ToolEffects.reduction(newPick, "mining"))
        assertEquals(10, ToolEffects.reduction(oldPick, "mining"))
        assertTrue(ToolEffects.isToolFor(newPick, "mining"))
        assertFalse(ToolEffects.isToolFor(newPick, "woodcutting"))
        assertEquals("mining", ToolEffects.skillOf(newPick))
    }

    @Test
    fun cookingToolsAreRecognizedByCookingCraftingEffect() {
        val knife = tool("gold_knife", 30, "cooking_crafting" to -40)
        assertEquals("cooking", ToolEffects.skillOf(knife))
        assertTrue(ToolEffects.isToolFor(knife, "cooking"))
        assertFalse(ToolEffects.isToolFor(knife, "fishing"))
    }

    @Test
    fun toolsRankByReductionBeforeLevel() {
        val spatula = tool("adamantite_spatula", 50, "cooking_crafting" to -60)
        val voidKnife = tool("voidstone_knife", 50, "cooking_crafting" to -70)
        val cleaver = tool("mithril_cleaver", 40, "cooking_crafting" to -50)
        val best = listOf(spatula, cleaver, voidKnife).maxWithOrNull(ToolEffects.comparator("cooking"))
        assertEquals("voidstone_knife", best?.code)
    }

    @Test
    fun combatWeaponsAndOtherCraftingEffectsAreNotTools() {
        val sword = Item(
            name = "sword", code = "sword", level = 10, type = "weapon", subtype = "", description = "",
            effects = listOf(SimpleEffect("attack_fire", 20, "")), tradeable = true
        )
        assertNull(ToolEffects.skillOf(sword))
        val doll = Item(
            name = "altar_doll", code = "altar_doll", level = 1, type = "artifact", subtype = "", description = "",
            effects = listOf(SimpleEffect("cooking_crafting", -10, "")), tradeable = true
        )
        assertNull(ToolEffects.skillOf(doll)) // artifact, not a weapon-slot tool
        assertTrue(ToolEffects.effectCodes("weaponcrafting").isEmpty())
    }

    private fun tool(code: String, level: Int, vararg effects: Pair<String, Int>) = Item(
        name = code, code = code, level = level, type = "weapon", subtype = "tool", description = "",
        effects = effects.map { SimpleEffect(it.first, it.second, "") }, tradeable = true
    )
}
