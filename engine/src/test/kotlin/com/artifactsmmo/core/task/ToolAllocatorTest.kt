package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.CraftInfo
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolAllocatorTest {
    private val copper = tool("copper_pickaxe", 1, "mining_gathering", -10)
    private val iron = tool("iron_pickaxe", 10, "mining_gathering", -20)
    private val pin = tool("ash_rolling_pin", 1, "cooking_crafting", -10)
    private val knife = tool("iron_knife", 10, "cooking_crafting", -20)
    private val tools = listOf(copper, iron, pin, knife)

    private fun holder(name: String, mining: Int = 20, cooking: Int = 20, vararg tools: String) =
        ToolHolder(name, mapOf("mining" to mining, "cooking" to cooking), tools.groupingBy { it }.eachCount())

    @Test
    fun priorityCharactersClaimScarceBetterToolsFirst() {
        val holders = listOf(
            holder("A", tools = arrayOf("copper_pickaxe")),
            holder("B", tools = arrayOf("copper_pickaxe")),
            holder("C", tools = arrayOf("copper_pickaxe", "iron_pickaxe")),
        )
        val config = ToolKitConfig(skills = listOf("mining"), perSkillPriority = mapOf("mining" to listOf("B", "C")))
        val plan = ToolAllocator.allocate(tools, config, holders, bank = mapOf("iron_pickaxe" to 1))

        assertEquals(mapOf("A" to "copper_pickaxe", "B" to "iron_pickaxe", "C" to "iron_pickaxe"),
            plan.assignments.mapValues { it.value["mining"] })
        assertEquals(ToolKitActions(mapOf("copper_pickaxe" to 1), listOf("iron_pickaxe")), plan.actions["B"])
        assertEquals(ToolKitActions(mapOf("copper_pickaxe" to 1)), plan.actions["C"])
        assertTrue(plan.actions.getValue("A").isEmpty())
        // A still needs one copper; the other two are redundant.
        assertEquals(mapOf("copper_pickaxe" to 2), plan.surplus)
    }

    @Test
    fun skillLevelGatesTheClaimAndKeepsToolsSomeoneMayGrowInto() {
        val holders = listOf(
            holder("A", mining = 5, tools = arrayOf("copper_pickaxe")),
            holder("B", tools = arrayOf("iron_pickaxe")),
        )
        val bank = mapOf("iron_pickaxe" to 1, "copper_pickaxe" to 3)
        val plan = ToolAllocator.allocate(tools, ToolKitConfig(skills = listOf("mining"), priority = listOf("A", "B")), holders, bank)

        assertEquals("copper_pickaxe", plan.assignedTool("A", "mining"))
        assertEquals("iron_pickaxe", plan.assignedTool("B", "mining"))
        // The spare iron is better than A's copper, so it's kept; extra coppers are recyclable minus spares.
        assertEquals(mapOf("copper_pickaxe" to 3), plan.surplus)
        val withSpare = ToolAllocator.allocate(tools, ToolKitConfig(skills = listOf("mining"), spares = 1), holders, bank)
        assertEquals(mapOf("copper_pickaxe" to 2), withSpare.surplus)
    }

    @Test
    fun replacedCookingToolsBecomeSurplusAndUnmanagedSkillsAreLeftAlone() {
        val names = listOf("A", "B", "C", "D", "E")
        val holders = names.map { holder(it, tools = arrayOf("ash_rolling_pin", "copper_pickaxe")) }
        val plan = ToolAllocator.allocate(tools, ToolKitConfig(skills = listOf("cooking")), holders, mapOf("iron_knife" to 5))

        assertTrue(names.all { plan.assignedTool(it, "cooking") == "iron_knife" })
        assertTrue(plan.actions.values.all { it == ToolKitActions(mapOf("ash_rolling_pin" to 1), listOf("iron_knife")) })
        assertEquals(mapOf("ash_rolling_pin" to 5), plan.surplus)
        assertNull(plan.assignedTool("A", "mining"))
        assertTrue(!plan.manages("A", "mining") && plan.manages("A", "cooking"))
    }

    @Test
    fun nonRecyclableToolsAreNeverSurplus() {
        val holders = listOf(holder("A", tools = arrayOf("iron_pickaxe")))
        val plan = ToolAllocator.allocate(tools, ToolKitConfig(skills = listOf("mining")), holders,
            mapOf("copper_pickaxe" to 2), recyclable = { it.code != "copper_pickaxe" })
        assertTrue(plan.surplus.isEmpty())
    }

    private fun tool(code: String, level: Int, effect: String, value: Int) = Item(
        name = code, code = code, level = level, type = "weapon", subtype = "tool", description = "",
        effects = listOf(SimpleEffect(effect, value, "")), tradeable = true,
        craft = CraftInfo(skill = "weaponcrafting", level = level),
    )
}
