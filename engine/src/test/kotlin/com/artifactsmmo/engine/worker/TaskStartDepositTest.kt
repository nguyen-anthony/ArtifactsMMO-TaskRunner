package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.CraftMode
import com.artifactsmmo.core.task.GearOptimizer
import com.artifactsmmo.core.task.TaskType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class TaskStartDepositTest {

    @Test
    fun fightKeepsInventoryGearUtilitiesAndItsFood() {
        val task = TaskType.Fight(
            monsterCode = "chicken", monsterName = "Chicken",
            equipActions = listOf(
                ActionHelper.EquipAction("weapon", "copper_dagger", "inventory"),
                ActionHelper.EquipAction("helmet", "copper_helmet", "bank"),
            ),
            utilityActions = listOf(GearOptimizer.UtilityEquipAction("utility1", "small_health_potion", 50, "bank")),
        )
        assertEquals(
            setOf("copper_dagger", "small_health_potion", "cooked_chicken"),
            TaskStartDeposit.keepFor(task, character(), useFoodCodes = setOf("cooked_chicken")),
        )
    }

    @Test
    fun craftAndGatherStartWithAnEmptyInventory() {
        assertEquals(emptySet(), TaskStartDeposit.keepFor(TaskType.Craft("cooking", "cooked_chicken", "Cooked Chicken", CraftMode.entries.first()), character()))
        assertEquals(emptySet(), TaskStartDeposit.keepFor(TaskType.Gather("mining", "copper_rocks", "Copper Rocks"), character()))
    }

    @Test
    fun taskMasterKeepsItemsForTheCurrentItemsTask() {
        assertEquals(setOf("copper_ore"), TaskStartDeposit.keepFor(TaskType.TaskMaster("items"), character(task = "copper_ore", taskType = "items")))
        assertEquals(emptySet(), TaskStartDeposit.keepFor(TaskType.TaskMaster("monsters"), character(task = "chicken", taskType = "monsters")))
    }

    @Test
    fun inventoryAndBankQuickTasksSkipTheDeposit() {
        assertNull(TaskStartDeposit.keepFor(TaskType.InventoryDeposit("copper_ore", "Copper Ore", 5), character()))
        assertNull(TaskStartDeposit.keepFor(TaskType.BulkInventoryDeposit(listOf(SimpleItem("copper_ore", 5))), character()))
        assertNull(TaskStartDeposit.keepFor(TaskType.BankWithdraw("copper_ore", "Copper Ore", 5), character()))
    }

    private fun character(task: String = "", taskType: String = "") = Character(
        name = "Ront", account = "account", skin = "skin", level = 1, xp = 0, maxXp = 100,
        gold = 0, speed = 0,
        miningLevel = 1, miningXp = 0, miningMaxXp = 100,
        woodcuttingLevel = 1, woodcuttingXp = 0, woodcuttingMaxXp = 100,
        fishingLevel = 1, fishingXp = 0, fishingMaxXp = 100,
        weaponcraftingLevel = 1, weaponcraftingXp = 0, weaponcraftingMaxXp = 100,
        gearcraftingLevel = 1, gearcraftingXp = 0, gearcraftingMaxXp = 100,
        jewelrycraftingLevel = 1, jewelrycraftingXp = 0, jewelrycraftingMaxXp = 100,
        cookingLevel = 1, cookingXp = 0, cookingMaxXp = 100,
        alchemyLevel = 1, alchemyXp = 0, alchemyMaxXp = 100,
        hp = 100, maxHp = 100, haste = 0, criticalStrike = 0, wisdom = 0,
        prospecting = 0, initiative = 0, threat = 0,
        attackFire = 0, attackEarth = 0, attackWater = 0, attackAir = 0,
        dmg = 0, dmgFire = 0, dmgEarth = 0, dmgWater = 0, dmgAir = 0,
        resFire = 0, resEarth = 0, resWater = 0, resAir = 0,
        x = 0, y = 0, layer = "overworld", mapId = 1, cooldown = 0,
        cooldownExpiration = Instant.fromEpochMilliseconds(0),
        weaponSlot = "", runeSlot = "", shieldSlot = "", helmetSlot = "",
        bodyArmorSlot = "", legArmorSlot = "", bootsSlot = "", ring1Slot = "",
        ring2Slot = "", amuletSlot = "", artifact1Slot = "", artifact2Slot = "",
        artifact3Slot = "", utility1Slot = "", utility1SlotQuantity = 0,
        utility2Slot = "", utility2SlotQuantity = 0, bagSlot = "",
        task = task, taskType = taskType, taskProgress = 0, taskTotal = 0,
        inventoryMaxItems = 100
    )
}
