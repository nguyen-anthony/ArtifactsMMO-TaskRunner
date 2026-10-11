package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Monster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
class LoadoutHeuristicTest {
    @Test
    fun elementalDamageBonusScalesAttackFromDifferentGearSlot() {
        val base = character(attackEarth = 100, dmgEarth = 0)
        val synergized = base.copy(dmgEarth = 50)
        val monster = monster(resEarth = 0)
        val baseScore = GearOptimizer.evaluateLoadout(base, monster)
        val synergyScore = GearOptimizer.evaluateLoadout(synergized, monster)

        assertTrue(synergyScore.expectedDamage > baseScore.expectedDamage)
        assertTrue(synergyScore.combatRatio > baseScore.combatRatio)
    }

    @Test
    fun resistanceAndHpIncreaseSurvivability() {
        val base = character(maxHp = 100, resEarth = 0)
        val defensive = base.copy(maxHp = 150, resEarth = 50)
        val monster = monster(attackEarth = 100)
        val baseScore = GearOptimizer.evaluateLoadout(base, monster)
        val defensiveScore = GearOptimizer.evaluateLoadout(defensive, monster)

        assertTrue(defensiveScore.effectiveHp > baseScore.effectiveHp)
    }

    @Test
    fun actingFirstSavesTheMonstersLastHit() {
        // 100 dmg vs 1000 HP = 10 hits; the monster hits 10 times if it acts first, 9 if we do.
        val monster = monster(attackEarth = 10, initiative = 50)
        val slow = GearOptimizer.evaluateLoadout(character(maxHp = 1000, initiative = 0), monster)
        val fast = GearOptimizer.evaluateLoadout(character(maxHp = 1000, initiative = 100), monster)

        assertEquals(0.10, slow.hpLostFraction, 1e-9)
        assertEquals(0.09, fast.hpLostFraction, 1e-9)
        assertTrue(fast.cycleSeconds < slow.cycleSeconds)
    }

    @Test
    fun loadoutThatWouldNearlyDieIsNotSafe() {
        val monster = monster(attackEarth = 90, hp = 1000)
        val heuristic = GearOptimizer.evaluateLoadout(character(maxHp = 1000), monster)

        assertFalse(heuristic.safe)
    }

    @Test
    fun cycleSecondsAppliesHasteRestAndMinimums() {
        // 20 turns -> 40s, 10% haste -> 36s; 30% HP lost -> 30s rest.
        assertEquals(66.0, GearOptimizer.cycleSeconds(20.0, 10, 0.30), 1e-9)
        // Fight cooldown floor 5s, rest floor 3s.
        assertEquals(8.0, GearOptimizer.cycleSeconds(1.0, 0, 0.01), 1e-9)
        // No HP lost -> no rest.
        assertEquals(5.0, GearOptimizer.cycleSeconds(1.0, 0, 0.0), 1e-9)
    }

    @Test
    fun simulatedScoreRanksFasterKillAboveEqualWinRate() {
        val slow = GearOptimizer.GearScore(1.0, 20.0, 0, 0, cycleSeconds = 90.0)
        val fast = GearOptimizer.GearScore(1.0, 22.0, 0, 0, cycleSeconds = 80.0)
        assertTrue(fast > slow)
    }

    private fun monster(attackEarth: Int = 0, resEarth: Int = 0, hp: Int = 1000, initiative: Int = 0) = Monster(
        name = "Monster", code = "monster", level = 1, type = "normal", hp = hp,
        attackFire = 0, attackEarth = attackEarth, attackWater = 0, attackAir = 0,
        resFire = 0, resEarth = resEarth, resWater = 0, resAir = 0,
        criticalStrike = 0, initiative = initiative, minGold = 0, maxGold = 0, drops = emptyList()
    )

    private fun character(
        attackEarth: Int = 100,
        dmgEarth: Int = 0,
        maxHp: Int = 100,
        resEarth: Int = 0,
        initiative: Int = 0
    ) = Character(
        name = "Character", account = "account", skin = "skin", level = 1, xp = 0, maxXp = 100,
        gold = 0, speed = 0,
        miningLevel = 1, miningXp = 0, miningMaxXp = 100,
        woodcuttingLevel = 1, woodcuttingXp = 0, woodcuttingMaxXp = 100,
        fishingLevel = 1, fishingXp = 0, fishingMaxXp = 100,
        weaponcraftingLevel = 1, weaponcraftingXp = 0, weaponcraftingMaxXp = 100,
        gearcraftingLevel = 1, gearcraftingXp = 0, gearcraftingMaxXp = 100,
        jewelrycraftingLevel = 1, jewelrycraftingXp = 0, jewelrycraftingMaxXp = 100,
        cookingLevel = 1, cookingXp = 0, cookingMaxXp = 100,
        alchemyLevel = 1, alchemyXp = 0, alchemyMaxXp = 100,
        hp = maxHp, maxHp = maxHp, haste = 0, criticalStrike = 0, wisdom = 0,
        prospecting = 0, initiative = initiative, threat = 0,
        attackFire = 0, attackEarth = attackEarth, attackWater = 0, attackAir = 0,
        dmg = 0, dmgFire = 0, dmgEarth = dmgEarth, dmgWater = 0, dmgAir = 0,
        resFire = 0, resEarth = resEarth, resWater = 0, resAir = 0,
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
