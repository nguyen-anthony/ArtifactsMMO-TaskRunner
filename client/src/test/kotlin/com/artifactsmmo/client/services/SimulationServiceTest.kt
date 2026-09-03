package com.artifactsmmo.client.services

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
