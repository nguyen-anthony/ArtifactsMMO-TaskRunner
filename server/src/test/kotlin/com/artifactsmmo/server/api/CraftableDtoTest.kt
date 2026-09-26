package com.artifactsmmo.server.api

import com.artifactsmmo.client.models.CraftInfo
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.core.task.ActionHelper
import kotlin.test.Test
import kotlin.test.assertEquals

class CraftableDtoTest {
    private fun item(code: String, level: Int, vararg ingredients: Pair<String, Int>) = Item(
        name = code, code = code, level = level, type = "weapon", subtype = "", description = "",
        craft = CraftInfo("weaponcrafting", level, ingredients.map { SimpleItem(it.first, it.second) }, 1),
        tradeable = true,
    )

    @Test
    fun keepsOrderAndZeroRecipesWithMissingIngredients() {
        val sword = item("iron_sword", 10, "iron_bar" to 6, "feather" to 2)
        val dagger = item("copper_dagger", 1, "copper_bar" to 6)
        val infos = listOf(
            ActionHelper.CraftableItemInfo(dagger, 5, dagger.craft!!.items, mapOf("copper_bar" to 30)),
            ActionHelper.CraftableItemInfo(sword, 0, sword.craft!!.items, mapOf("iron_bar" to 36, "feather" to 0),
                listOf(ActionHelper.NpcPurchaseInfo("merchant", "feather", "gold", 500, 0))),
        )
        val dtos = infos.toCraftableDtos()

        assertEquals(listOf("copper_dagger", "iron_sword"), dtos.map { it.code })
        val s = dtos[1]
        assertEquals(0, s.maxCraftable)
        assertEquals(listOf(0, 2), s.ingredients.map { it.missing })
        assertEquals(36, s.ingredients.first { it.code == "iron_bar" }.have)
        assertEquals("merchant", s.npcBuy.single().npc)
        assertEquals("weaponcrafting", s.skill)
        assertEquals(10, s.level)
    }
}
