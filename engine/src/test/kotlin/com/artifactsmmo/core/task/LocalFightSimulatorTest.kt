package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Monster
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class LocalFightSimulatorTest {
    @Test
    fun calculatesIndependentElementsWithDocumentedRounding() {
        val result = LocalFightSimulator.calculateAttack(
            attacks = LocalFightSimulator.ElementalValues(fire = 100, earth = 50),
            globalDamageBonus = 10,
            elementalDamageBonuses = LocalFightSimulator.ElementalValues(fire = 20, earth = 0),
            resistances = LocalFightSimulator.ElementalValues(fire = 30, earth = -10),
            criticalStrike = 0,
            rng = Random(1)
        )

        assertEquals(91, result.damage.fire)
        assertEquals(61, result.damage.earth)
        assertEquals(152, result.totalDamage)
    }

    @Test
    fun oneCriticalRollAppliesToEveryElement() {
        val result = LocalFightSimulator.calculateAttack(
            attacks = LocalFightSimulator.ElementalValues(fire = 39, earth = 216),
            globalDamageBonus = 0,
            elementalDamageBonuses = LocalFightSimulator.ElementalValues(),
            resistances = LocalFightSimulator.ElementalValues(),
            criticalStrike = 100,
            rng = Random(1)
        )

        assertTrue(result.critical)
        assertEquals(59, result.damage.fire)
        assertEquals(324, result.damage.earth)
    }

    @Test
    fun threatBranchUsesHighestThreatThenLowestHp() {
        val target = LocalFightSimulator.selectMonsterTarget(
            candidates = listOf(
                LocalFightSimulator.TargetCandidate("tank-high-hp", threat = 20, hp = 500),
                LocalFightSimulator.TargetCandidate("tank-low-hp", threat = 20, hp = 300),
                LocalFightSimulator.TargetCandidate("support", threat = 5, hp = 100)
            ),
            useThreat = true,
            rng = Random(1)
        )

        assertEquals("tank-low-hp", target)
    }

    @Test
    fun hpBranchUsesLowestCurrentHpRegardlessOfThreat() {
        val target = LocalFightSimulator.selectMonsterTarget(
            candidates = listOf(
                LocalFightSimulator.TargetCandidate("tank", threat = 50, hp = 500),
                LocalFightSimulator.TargetCandidate("support", threat = 0, hp = 100)
            ),
            useThreat = false,
            rng = Random(1)
        )

        assertEquals("support", target)
    }

    @Test
    fun unresolvedFightIsAlwaysLossEvenWhenMonsterActsFirst() {
        val result = LocalFightSimulator.simulate(
            character = character(name = "character", initiative = 0, attackFire = 0),
            monster = monster(initiative = 100, attackFire = 0),
            iterations = 1,
            rng = Random(1)
        )

        assertEquals(0, result.wins)
        assertEquals(1, result.losses)
        assertEquals(0.0, result.winRate)
    }

    @Test
    fun rejectsNonPositiveIterations() {
        assertFailsWith<IllegalArgumentException> {
            LocalFightSimulator.simulate(character(), monster(), iterations = 0)
        }
    }

    private fun monster(initiative: Int = 0, attackFire: Int = 1) = Monster(
        name = "Monster",
        code = "monster",
        level = 1,
        type = "normal",
        hp = 100,
        attackFire = attackFire,
        attackEarth = 0,
        attackWater = 0,
        attackAir = 0,
        resFire = 0,
        resEarth = 0,
        resWater = 0,
        resAir = 0,
        criticalStrike = 0,
        initiative = initiative,
        minGold = 0,
        maxGold = 0,
        drops = emptyList()
    )

    private fun character(
        name: String = "Character",
        initiative: Int = 0,
        attackFire: Int = 1
    ) = Character(
        name = name, account = "account", skin = "skin", level = 1, xp = 0, maxXp = 100,
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
        prospecting = 0, initiative = initiative, threat = 0,
        attackFire = attackFire, attackEarth = 0, attackWater = 0, attackAir = 0,
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
