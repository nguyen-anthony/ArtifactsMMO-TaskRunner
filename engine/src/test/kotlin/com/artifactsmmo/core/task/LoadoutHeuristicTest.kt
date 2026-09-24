package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Monster
import kotlin.test.Test
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

    private fun monster(attackEarth: Int = 0, resEarth: Int = 0) = Monster(
        name = "Monster", code = "monster", level = 1, type = "normal", hp = 1000,
        attackFire = 0, attackEarth = attackEarth, attackWater = 0, attackAir = 0,
        resFire = 0, resEarth = resEarth, resWater = 0, resAir = 0,
        criticalStrike = 0, initiative = 0, minGold = 0, maxGold = 0, drops = emptyList()
    )

    private fun character(
        attackEarth: Int = 100,
        dmgEarth: Int = 0,
        maxHp: Int = 100,
        resEarth: Int = 0
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
        prospecting = 0, initiative = 0, threat = 0,
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
