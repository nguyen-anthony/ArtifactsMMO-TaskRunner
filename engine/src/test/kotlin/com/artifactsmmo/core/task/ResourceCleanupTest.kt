package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.CraftInfo
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceCleanupTest {
    private fun item(
        code: String, level: Int, type: String = "resource", subtype: String = "",
        skill: String? = null, yield: Int = 1, vararg ingredients: Pair<String, Int>,
    ) = Item(
        name = code, code = code, level = level, type = type, subtype = subtype, description = "",
        craft = if (skill == null) null else CraftInfo(skill, level, ingredients.map { SimpleItem(it.first, it.second) }, yield),
        tradeable = true,
    )

    private val items = listOf(
        item("feather", 1, subtype = "mob"),
        item("feather_coat", 10, "body_armor", skill = "gearcrafting", ingredients = arrayOf("feather" to 5)),
        item("satchel", 15, "bag", skill = "gearcrafting", ingredients = arrayOf("feather" to 2)),
        item("spruce_wood", 10, subtype = "woodcutting"),
        item("spruce_plank", 10, subtype = "plank", skill = "woodcutting", ingredients = arrayOf("spruce_wood" to 10)),
        item("spruce_bow", 15, "weapon", skill = "weaponcrafting", ingredients = arrayOf("spruce_plank" to 6)),
        item("sap", 20, subtype = "sap", skill = "woodcutting", yield = 2, ingredients = arrayOf("spruce_wood" to 4)),
        item("health_potion", 30, "utility", skill = "alchemy", ingredients = arrayOf("sap" to 1)),
        item("golden_egg", 1, subtype = "mob"),
        item("copper_ore", 1, subtype = "mining"),
        item("scorpion_meat", 50, subtype = "mob"),
        item("cooked_scorpion_meat", 50, "consumable", skill = "cooking", ingredients = arrayOf("scorpion_meat" to 1)),
    )
    private val content = ResourceContent(
        items,
        tasks = listOf(ItemTaskTarget("spruce_plank", 10, "woodcutting"), ItemTaskTarget("copper_ore", 1, "mining")),
        npcTrades = listOf(NpcTrade("fish_merchant", "Fish Merchant", "golden_egg", "gold", sellPrice = 40)),
    )
    private val skills = listOf("gearcrafting", "weaponcrafting", "woodcutting", "alchemy").associateWith { 30 }
    private val chars = listOf(CleanupHolder("a", 26, skills), CleanupHolder("b", 30, skills))
    private val bank = mapOf("feather" to 300, "spruce_wood" to 1000, "golden_egg" to 3, "copper_ore" to 50, "scorpion_meat" to 10)

    private fun analyze(holders: List<CleanupHolder> = chars, config: CleanupConfig = CleanupConfig(), protected: Map<String, String> = emptyMap()) =
        ResourceCleanup.analyze(content, holders, bank, config, protected).associateBy { it.code }

    @Test
    fun `low level drop whose recipes are outgrown is unused and bags don't count`() {
        val feather = analyze().getValue("feather")
        assertEquals(ResourceCleanup.UNUSED, feather.verdict)
        assertEquals(10, feather.maxRecipeLevel)
        assertTrue(feather.uses.isEmpty())
    }

    @Test
    fun `side product use plus task master target is thin with craft counts`() {
        val wood = analyze().getValue("spruce_wood")
        assertEquals(ResourceCleanup.THIN, wood.verdict)
        val potion = wood.uses.single { it.code == "health_potion" }
        assertEquals(listOf("spruce_wood", "sap", "health_potion"), potion.path)
        assertEquals(2.0, potion.perUnit) // 4 wood -> 2 sap, 1 sap per potion
        assertEquals(500, potion.crafts)
        val task = wood.taskTargets.single()
        assertEquals("spruce_plank", task.code)
        assertEquals(100, task.crafts)
        assertTrue(task.reason.startsWith("Task Master"))
    }

    @Test
    fun `task master target alone is thin, no recipe and no use is no_recipe with npc sell price`() {
        val rows = analyze()
        assertEquals(ResourceCleanup.THIN, rows.getValue("copper_ore").verdict)
        assertTrue(rows.getValue("copper_ore").uses.isEmpty())
        val egg = rows.getValue("golden_egg")
        assertEquals(ResourceCleanup.NO_RECIPE, egg.verdict)
        assertEquals(40, egg.npcSell.single().price)
    }

    @Test
    fun `crafting xp still counts while a skill is low`() {
        val novice = CleanupHolder("c", 26, skills + ("gearcrafting" to 5))
        assertEquals(ResourceCleanup.UNUSED, analyze().getValue("feather").verdict)
        assertNull(analyze(chars + novice)["feather"])
        // An untrained (level 1) skill is ignored: that character isn't crafting.
        val untrained = CleanupHolder("d", 26, skills + ("gearcrafting" to 1))
        assertEquals(ResourceCleanup.UNUSED, analyze(chars + untrained).getValue("feather").verdict)
    }

    @Test
    fun `widely used resources are omitted and thresholds or keep list protect`() {
        assertNull(analyze()["scorpion_meat"]) // a single recipe that's still current isn't thin
        assertNull(analyze(config = CleanupConfig(resourceThinUses = 0))["spruce_wood"])
        assertNull(analyze(chars.map { it.copy(level = 15) }, CleanupConfig(resourceXpWindow = 0, resourceThinUses = 1))["spruce_wood"])
        val rows = analyze(config = CleanupConfig(keep = listOf("feather")), protected = mapOf("spruce_bow" to "restock threshold"))
        assertEquals("keep list", rows.getValue("feather").protectedBy)
        assertEquals("feeds spruce_bow: restock threshold", rows.getValue("spruce_wood").protectedBy)
    }
}
