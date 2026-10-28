package com.artifactsmmo.client.services

import com.artifactsmmo.client.models.CombatSimulationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SimulationServiceTest {
    @Test
    fun normalizesPercentagePointsToFraction() {
        assertEquals(1.0, normalizeSimulationWinRate(100.0, wins = 100, losses = 0))
    }

    @Test
    fun preservesFractionalWinRate() {
        assertEquals(0.85, normalizeSimulationWinRate(0.85, wins = 85, losses = 15))
    }

    @Test
    fun usesCountsWhenOnePercentIsAmbiguous() {
        assertEquals(0.01, normalizeSimulationWinRate(1.0, wins = 1, losses = 99))
    }

    @Test
    fun parsesMonsterHpLeftFromTheLastMonsterHpLine() {
        val result = CombatSimulationResult(
            result = "loss", turns = 3,
            logs = listOf(
                "Fight start: Character_1 HP: 2365/2365 vs Sandwhisper Empress HP: 13500/13500",
                "Turn 1: Character_1 used air attack and dealt 325 damage. Sandwhisper Empress HP: 13175/13500",
                "Turn 2: Character_1 used air attack and dealt 6425 damage. Sandwhisper Empress HP: 6750/13500",
                "Turn 3: Sandwhisper Empress used fire attack against Character_1 and dealt 2365 damage. Character_1 HP: 0/2365"
            )
        )
        assertEquals(0.5, result.monsterHpRemainingFraction())
        assertNull(CombatSimulationResult(result = "loss", turns = 1).monsterHpRemainingFraction())
    }
}
