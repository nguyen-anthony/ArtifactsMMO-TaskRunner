package com.artifactsmmo.domain.task

import kotlin.test.Test
import kotlin.test.assertEquals

class TaskSpecTest {
    @Test
    fun `every spec round-trips through JSON and reports its type`() {
        val specs = listOf(
            TaskSpec.Gather("mining", "copper_rocks", targetItemCode = "copper_bar"),
            TaskSpec.Fight("chicken", equip = listOf(EquipPlan("weapon", "copper_dagger", "bank")),
                dropStrategies = mapOf("raw_chicken" to DropStrategy.COOK_AND_USE)),
            TaskSpec.Craft("weaponcrafting", "copper_dagger", mode = CraftMode.BANK, targetQuantity = 5),
            TaskSpec.TaskMaster("monsters"),
            TaskSpec.BankWithdraw("copper_ore", 10),
            TaskSpec.BankRecycle("copper_dagger", 1, "weaponcrafting"),
            TaskSpec.InventoryDeposit("copper_ore", 10),
            TaskSpec.InventoryRecycle("copper_dagger", 1, "weaponcrafting"),
            TaskSpec.BulkBankWithdraw(listOf(ItemQty("a", 1))),
            TaskSpec.BulkInventoryDeposit(listOf(ItemQty("a", 1))),
        )
        for (s in specs) {
            val json = s.toJson()
            assertEquals(s.typeName, json["kind"].toString().trim('"'))
            assertEquals(s, TaskSpec.fromJson(json))
        }
    }
}
