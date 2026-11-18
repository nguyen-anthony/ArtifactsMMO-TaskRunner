package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Condition
import com.artifactsmmo.client.models.DropRate
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Resource
import com.artifactsmmo.client.models.SimpleEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class StatGearOptimizerTest {

    private fun item(
        code: String, type: String, prospecting: Int = 0, wisdom: Int = 0, minLevel: Int? = null,
        extra: List<SimpleEffect> = emptyList(),
    ) = Item(
        name = code, code = code, level = minLevel?.plus(1) ?: 1, type = type, subtype = "", description = "",
        conditions = minLevel?.let { listOf(Condition("level", "gt", it)) } ?: emptyList(),
        effects = listOfNotNull(
            if (prospecting > 0) SimpleEffect("prospecting", prospecting, "") else null,
            if (wisdom > 0) SimpleEffect("wisdom", wisdom, "") else null,
        ) + extra,
        tradeable = true,
    )

    private val GATHER = StatGearOptimizer.Goal.gathering("mining", StatGearOptimizer.Focus.PROSPECTING)
    private val CRAFT = StatGearOptimizer.Goal.crafting("cooking")

    private val altarDoll = item("altar_doll", "artifact", extra = listOf(
        SimpleEffect("mining_gathering", -10, ""), SimpleEffect("cooking_crafting", -10, ""),
    ))

    private fun plan(
        goal: StatGearOptimizer.Goal,
        candidates: List<Item>,
        char: Character = character(),
        inv: Map<String, Int> = emptyMap(),
        bank: Map<String, Int> = emptyMap(),
    ) = StatGearOptimizer.plan(char, goal, candidates.groupBy { it.type },
        inventoryQty = { inv[it] ?: 0 }, bankQty = { bank[it] ?: 0 })

    @Test
    fun picksHighestProspectingForGathering() {
        val helmets = listOf(item("plain_helmet", "helmet", prospecting = 0), item("prospector_helmet", "helmet", prospecting = 50))
        val actions = plan(GATHER, helmets, bank = mapOf("prospector_helmet" to 1))
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
        val actions = plan(GATHER, amulets, bank = mapOf("amulet_a" to 1, "amulet_b" to 1))
        assertEquals(listOf(ActionHelper.EquipAction("amulet", "amulet_b", "bank")), actions)
    }

    @Test
    fun craftingIgnoresProspectingAndOnlyWantsWisdom() {
        val boots = listOf(item("fast_boots", "boots", prospecting = 100, wisdom = 0), item("wise_boots", "boots", prospecting = 0, wisdom = 10))
        val actions = plan(CRAFT, boots, bank = mapOf("fast_boots" to 1, "wise_boots" to 1))
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
        val actions = plan(GATHER, listOf(ring), bank = mapOf("chance_ring" to 1))
        assertEquals(1, actions.count { it.itemCode == "chance_ring" })
    }

    @Test
    fun twoCopiesFillBothRingSlots() {
        val ring = item("chance_ring", "ring", prospecting = 40)
        val actions = plan(GATHER, listOf(ring), bank = mapOf("chance_ring" to 2))
        assertEquals(setOf("ring1", "ring2"), actions.map { it.slot }.toSet())
    }

    @Test
    fun artifactsMustAllBeDifferent() {
        val artifact = item("lucky_charm", "artifact", prospecting = 20)
        val actions = plan(GATHER, listOf(artifact), bank = mapOf("lucky_charm" to 3))
        assertEquals(1, actions.count { it.itemCode == "lucky_charm" })
    }

    @Test
    fun tiedCurrentItemIsNotSwapped() {
        val char = character().copy(helmetSlot = "plain_helmet")
        val helmets = listOf(item("plain_helmet", "helmet", prospecting = 20), item("other_helmet", "helmet", prospecting = 20))
        val actions = plan(GATHER, helmets, char = char, bank = mapOf("other_helmet" to 1))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun drawsFromInventoryBeforeBank() {
        val helmets = listOf(item("prospector_helmet", "helmet", prospecting = 50))
        val actions = plan(GATHER, helmets, inv = mapOf("prospector_helmet" to 1), bank = mapOf("prospector_helmet" to 1))
        assertEquals("inventory", actions.single().source)
    }

    @Test
    fun wisdomFocusPrefersWisdomOverProspecting() {
        val amulets = listOf(item("prospecting_amulet", "amulet", prospecting = 100), item("wisdom_amulet", "amulet", wisdom = 60))
        val goal = StatGearOptimizer.Goal.gathering("woodcutting", StatGearOptimizer.Focus.WISDOM)
        val actions = plan(goal, amulets, bank = mapOf("prospecting_amulet" to 1, "wisdom_amulet" to 1))
        assertEquals(listOf(ActionHelper.EquipAction("amulet", "wisdom_amulet", "bank")), actions)
    }

    @Test
    fun speedArtifactTakesASlotBeforeStatArtifacts() {
        // Four candidates for three slots: the doll must win a slot over the weakest stat
        // artifact even though it has no stats at all.
        val artifacts = listOf(
            item("perfect_pearl", "artifact", prospecting = 100), item("altar_totem", "artifact", prospecting = 50),
            item("small_charm", "artifact", prospecting = 5), altarDoll,
        )
        val bank = artifacts.associate { it.code to 1 }
        val actions = plan(GATHER, artifacts, bank = bank)
        assertEquals(setOf("altar_doll", "perfect_pearl", "altar_totem"), actions.map { it.itemCode }.toSet())
        assertEquals(listOf("artifact1", "artifact2", "artifact3"), actions.map { it.slot })
    }

    @Test
    fun speedArtifactReplacesTheWeakestEquippedArtifact() {
        val char = character().copy(artifact1Slot = "lost_world_map", artifact2Slot = "a2", artifact3Slot = "a3")
        val artifacts = listOf(item("lost_world_map", "artifact", wisdom = 100), item("a2", "artifact", wisdom = 50),
            item("a3", "artifact", wisdom = 40), altarDoll)
        val actions = plan(CRAFT, artifacts, char = char, bank = mapOf("altar_doll" to 1))
        assertEquals(listOf(ActionHelper.EquipAction("artifact3", "altar_doll", "bank")), actions)
    }

    @Test
    fun speedOnlyCountsForTheTasksSkill() {
        val fishingDoll = item("fish_doll", "artifact", extra = listOf(SimpleEffect("fishing_gathering", -10, "")))
        val actions = plan(GATHER, listOf(fishingDoll), bank = mapOf("fish_doll" to 1))
        assertTrue(actions.isEmpty())
    }

    private fun resource(level: Int, vararg drops: Pair<String, Int>) =
        Resource("r", "r", "mining", level, drops.map { DropRate(it.first, it.second, 1, 1) })

    private val prospectFor = GearFocusConfig.DEFAULT_PROSPECT_FOR.toSet()

    @Test
    fun autoFocusProspectsForGemsAndOtherwiseWantsWisdom() {
        val rocks = resource(10, "iron_ore" to 1, "topaz_stone" to 200)
        val tree = resource(10, "spruce_wood" to 1, "sap" to 10)
        assertEquals(StatGearOptimizer.Focus.PROSPECTING, StatGearOptimizer.resolveFocus(null, rocks, 12, prospectFor))
        assertEquals(StatGearOptimizer.Focus.WISDOM, StatGearOptimizer.resolveFocus(null, tree, 12, prospectFor))
        // The guaranteed main drop doesn't count even if listed.
        val guaranteed = resource(10, "topaz_stone" to 1)
        assertEquals(StatGearOptimizer.Focus.WISDOM, StatGearOptimizer.resolveFocus(null, guaranteed, 12, prospectFor))
    }

    @Test
    fun autoFocusProspectsWhenNoXpIsEarned() {
        val tree = resource(10, "spruce_wood" to 1, "sap" to 10)
        assertEquals(StatGearOptimizer.Focus.WISDOM, StatGearOptimizer.resolveFocus(null, tree, 20, prospectFor))
        assertEquals(StatGearOptimizer.Focus.PROSPECTING, StatGearOptimizer.resolveFocus(null, tree, 21, prospectFor))
        assertEquals(StatGearOptimizer.Focus.PROSPECTING, StatGearOptimizer.resolveFocus(null, resource(45, "x" to 1), 50, prospectFor))
    }

    @Test
    fun requestedFocusOverridesAuto() {
        val rocks = resource(10, "iron_ore" to 1, "topaz_stone" to 200)
        assertEquals(StatGearOptimizer.Focus.WISDOM,
            StatGearOptimizer.resolveFocus(StatGearOptimizer.Focus.WISDOM, rocks, 12, prospectFor))
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
