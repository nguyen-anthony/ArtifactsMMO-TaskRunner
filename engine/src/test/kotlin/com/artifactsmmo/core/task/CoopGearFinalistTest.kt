package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Monster
import com.artifactsmmo.client.models.SimpleEffect
import com.artifactsmmo.core.task.CoopOptimizer.CoopScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoopGearFinalistTest {
    private fun losing(bossHpLeft: Double) = CoopScore(0.0, Double.MAX_VALUE, 0, bossHpLeft)
    private fun winning(rate: Double, turns: Double = 40.0) = CoopScore(rate, turns, 0)

    @Test
    fun gearThatOnlyWinsWithPotionsBeatsTeamsThatAllLoseWithoutThem() {
        // Every finalist loses without potions; #1 (e.g. the current gear) wins with its potions.
        val choice = CoopOptimizer.chooseGearFinalist(
            gearOnly = listOf(losing(0.3), losing(0.6)),
            withUtilities = listOf(winning(0.05), winning(1.0))
        )
        assertEquals(1 to true, choice)
    }

    @Test
    fun aTeamThatWinsWithoutPotionsIsPreferredOverOneThatNeedsThem() {
        val choice = CoopOptimizer.chooseGearFinalist(
            gearOnly = listOf(losing(0.1), winning(0.96, turns = 60.0)),
            withUtilities = listOf(winning(1.0, turns = 30.0), null)
        )
        assertEquals(1 to false, choice)
    }

    @Test
    fun losingTeamsAreRankedByTheBossHpTheyLeave() {
        assertTrue(losing(0.2) > losing(0.5))
        assertEquals(0, losing(0.50).compareTo(losing(0.51)))
        assertEquals(0 to false, CoopOptimizer.chooseGearFinalist(listOf(losing(0.2), losing(0.5)), listOf(null, null)))
    }

    @Test
    fun alliesHealingAuraExtendsTheTargetsSurvivalButTheCastersOwnAuraDoesNot() {
        val boss = boss()
        // 2000 max HP, 10 turns of effective HP -> 200 incoming per round.
        val target = member("target", maxHp = 2000, effectiveHp = 10.0, aura = 6.0)
        val ally = member("ally", maxHp = 2000, effectiveHp = 10.0, aura = 6.0)
        assertEquals(10.0, CoopOptimizer.sustainedSurvivalTurns(target, listOf(target), boss), 1e-9)
        // Ally heals 6% x 2000 = 120 per round -> net 80 incoming -> 25 rounds.
        assertEquals(25.0, CoopOptimizer.sustainedSurvivalTurns(target, listOf(target, ally), boss), 1e-9)
    }

    @Test
    fun bossBerserkAndHealingMakeTheFightHarder() {
        val plain = boss()
        val nasty = boss(SimpleEffect("berserker_rage", 50, ""), SimpleEffect("healing", 30, ""))
        val target = member("target", maxHp = 2000, effectiveHp = 10.0)
        assertTrue(CoopOptimizer.sustainedSurvivalTurns(target, listOf(target), nasty) <
            CoopOptimizer.sustainedSurvivalTurns(target, listOf(target), plain))
        // 30% of 10000 HP every 3 turns = 1000 healed per round.
        assertEquals(2000.0, CoopOptimizer.netTeamDamage(3000.0, nasty), 1e-9)
        assertEquals(3000.0, CoopOptimizer.netTeamDamage(3000.0, plain), 1e-9)
    }

    private fun member(name: String, maxHp: Int, effectiveHp: Double, aura: Double = 0.0) = TeamLoadoutCandidate(
        characterName = name, value = name, bankDemand = emptyMap(), heuristicScore = 0.0, threat = 0,
        stableKey = name, effectiveHp = effectiveHp, maxHp = maxHp, allyHealPercentPerTurn = aura
    )

    private fun boss(vararg effects: SimpleEffect) = Monster(
        name = "Boss", code = "boss", level = 50, type = "boss", hp = 10000,
        attackFire = 200, attackEarth = 0, attackWater = 0, attackAir = 0,
        resFire = 0, resEarth = 0, resWater = 0, resAir = 0, criticalStrike = 0, initiative = 0,
        effects = effects.toList(), minGold = 0, maxGold = 0, drops = emptyList()
    )
}
