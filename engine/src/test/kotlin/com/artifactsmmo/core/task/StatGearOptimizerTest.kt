package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Condition
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class StatGearOptimizerTest {

    private fun item(code: String, type: String, prospecting: Int = 0, wisdom: Int = 0, minLevel: Int? = null) = Item(
        name = code, code = code, level = minLevel?.plus(1) ?: 1, type = type, subtype = "", description = "",
        conditions = minLevel?.let { listOf(Condition("level", "gt", it)) } ?: emptyList(),
        effects = listOfNotNull(
            if (prospecting > 0) SimpleEffect("prospecting", prospecting, "") else null,
            if (wisdom > 0) SimpleEffect("wisdom", wisdom, "") else null,
        ),
        tradeable = true,
    )

    private fun plan(
        priority: StatGearOptimizer.Priority,
        candidates: List<Item>,
        char: Character = character(),
        inv: Map<String, Int> = emptyMap(),
        bank: Map<String, Int> = emptyMap(),
    ) = StatGearOptimizer.plan(char, priority, candidates.groupBy { it.type },
        inventoryQty = { inv[it] ?: 0 }, bankQty = { bank[it] ?: 0 })

    @Test
    fun picksHighestProspectingForGathering() {
        val helmets = listOf(item("plain_helmet", "helmet", prospecting = 0), item("prospector_helmet", "helmet", prospecting = 50))
        val actions = plan(StatGearOptimizer.Priority.GATHERING, helmets, bank = mapOf("prospector_helmet" to 1))
        assertEquals(listOf(ActionHelper.EquipAction("helmet", "prospector_helmet", "bank")), actions)
    }

    @Test
    fun wisdomBreaksProspectingTiesForGathering() {
        // Single-slot item type (amulet) so both candidates genuinely compete for one slot —
        // with two ring slots both would simply get equipped, tie or not.
        val amulets = listOf(
            item("amulet_a", "amulet", prospecting = 30, wisdom = 0),
            item("amulet_b", "amulet", prospecting = 30, wisdom = 20),
        )
        val actions = plan(StatGearOptimizer.Priority.GATHERING, amulets, bank = mapOf("amulet_a" to 1, "amulet_b" to 1))
        assertEquals(listOf(ActionHelper.EquipAction("amulet", "amulet_b", "bank")), actions)
    }

    @Test
    fun craftingIgnoresProspectingAndOnlyWantsWisdom() {
        val boots = listOf(item("fast_boots", "boots", prospecting = 100, wisdom = 0), item("wise_boots", "boots", prospecting = 0, wisdom = 10))
        val actions = plan(StatGearOptimizer.Priority.CRAFTING, boots, bank = mapOf("fast_boots" to 1, "wise_boots" to 1))
        assertEquals(listOf(ActionHelper.EquipAction("boots", "wise_boots", "bank")), actions)
    }

    @Test
    fun neverTouchesWeaponSlot() {
        assertTrue(StatGearOptimizer.STAT_SLOTS.none { it.slot == "weapon" })
    }

    @Test
    fun levelConditionBlocksGearAboveCombatLevel() {
        // `plan()` trusts pre-filtered candidates (STAT_SLOTS never checks conditions itself);
        // the actual gate is TeleportAdvisor.meetsCondition, used by candidatesFor().
        val endgameHelmet = item("endgame_helmet", "helmet", prospecting = 100, minLevel = 49)
        val condition = endgameHelmet.conditions.single()
        assertTrue(!TeleportAdvisor.meetsCondition(character(level = 20), condition, emptySet()))
        assertTrue(TeleportAdvisor.meetsCondition(character(level = 50), condition, emptySet()))
    }

    @Test
    fun oneRingInBankFillsOnlyOneOfTwoRingSlots() {
        val ring = item("chance_ring", "ring", prospecting = 40)
        val actions = plan(StatGearOptimizer.Priority.GATHERING, listOf(ring), bank = mapOf("chance_ring" to 1))
        assertEquals(1, actions.count { it.itemCode == "chance_ring" })
    }

    @Test
    fun twoCopiesFillBothRingSlots() {
        val ring = item("chance_ring", "ring", prospecting = 40)
        val actions = plan(StatGearOptimizer.Priority.GATHERING, listOf(ring), bank = mapOf("chance_ring" to 2))
        assertEquals(setOf("ring1", "ring2"), actions.map { it.slot }.toSet())
    }

    @Test
    fun artifactsMustAllBeDifferent() {
        val artifact = item("lucky_charm", "artifact", prospecting = 20)
        val actions = plan(StatGearOptimizer.Priority.GATHERING, listOf(artifact), bank = mapOf("lucky_charm" to 3))
        assertEquals(1, actions.count { it.itemCode == "lucky_charm" })
    }

    @Test
    fun tiedCurrentItemIsNotSwapped() {
        val char = character().copy(helmetSlot = "plain_helmet")
        val helmets = listOf(item("plain_helmet", "helmet", prospecting = 20), item("other_helmet", "helmet", prospecting = 20))
        val actions = plan(StatGearOptimizer.Priority.GATHERING, helmets, char = char, bank = mapOf("other_helmet" to 1))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun drawsFromInventoryBeforeBank() {
        val helmets = listOf(item("prospector_helmet", "helmet", prospecting = 50))
        val actions = plan(StatGearOptimizer.Priority.GATHERING, helmets, inv = mapOf("prospector_helmet" to 1), bank = mapOf("prospector_helmet" to 1))
        assertEquals("inventory", actions.single().source)
    }

    private fun character(level: Int = 50) = Character(
        name = "Character", account = "account", skin = "skin", level = level, xp = 0, maxXp = 100,
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
        task = "", taskType = "", taskProgress = 0, taskTotal = 0,
        inventoryMaxItems = 100
    )
}
