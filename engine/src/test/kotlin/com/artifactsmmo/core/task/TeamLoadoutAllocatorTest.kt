package com.artifactsmmo.core.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamLoadoutAllocatorTest {
    @Test
    fun rejectsTeamThatOverAllocatesSharedBankItem() {
        val candidates = mapOf(
            "tank" to listOf(candidate("tank", "shared", demand = mapOf("rare_ring" to 1), score = 10.0, threat = 10)),
            "support" to listOf(
                candidate("support", "shared", demand = mapOf("rare_ring" to 1), score = 10.0, threat = 1),
                candidate("support", "personal", demand = emptyMap(), score = 5.0, threat = 1)
            )
        )

        val result = TeamLoadoutAllocator.enumerate(
            participantOrder = listOf("tank", "support"),
            candidates = candidates,
            bankQuantities = mapOf("rare_ring" to 1),
            tankName = "tank"
        )

        assertEquals("personal", result.first().byCharacter.getValue("support").value)
        assertEquals(1, result.first().bankDemand["rare_ring"])
    }

    @Test
    fun permitsBothCandidatesWhenBankHasTwoCopies() {
        val candidates = mapOf(
            "tank" to listOf(candidate("tank", "tank-best", mapOf("rare_ring" to 1), 10.0, 10)),
            "support" to listOf(candidate("support", "support-best", mapOf("rare_ring" to 1), 9.0, 1))
        )

        val result = TeamLoadoutAllocator.enumerate(
            participantOrder = listOf("tank", "support"),
            candidates = candidates,
            bankQuantities = mapOf("rare_ring" to 2),
            tankName = "tank"
        )

        assertEquals(2, result.first().bankDemand["rare_ring"])
    }

    @Test
    fun prefersThreatValidCombinationOverHigherHeuristicViolation() {
        val candidates = mapOf(
            "tank" to listOf(candidate("tank", "tank", emptyMap(), 5.0, 10)),
            "support" to listOf(
                candidate("support", "high-score-threat", emptyMap(), 100.0, 10),
                candidate("support", "valid", emptyMap(), 1.0, 9)
            )
        )

        val result = TeamLoadoutAllocator.enumerate(
            participantOrder = listOf("tank", "support"),
            candidates = candidates,
            bankQuantities = emptyMap(),
            tankName = "tank"
        )

        assertEquals("valid", result.first().byCharacter.getValue("support").value)
        assertEquals(0, result.first().threatViolation)
    }

    @Test
    fun orderingIsStableWhenCandidateInputIsShuffled() {
        val firstOrder = mapOf(
            "tank" to listOf(
                candidate("tank", "b", emptyMap(), 5.0, 10),
                candidate("tank", "a", emptyMap(), 5.0, 10)
            )
        )
        val secondOrder = mapOf("tank" to firstOrder.getValue("tank").reversed())

        val first = TeamLoadoutAllocator.enumerate(listOf("tank"), firstOrder, emptyMap(), "tank")
        val second = TeamLoadoutAllocator.enumerate(listOf("tank"), secondOrder, emptyMap(), "tank")

        assertEquals(first.map { it.stableKey }, second.map { it.stableKey })
        assertTrue(first.first().stableKey.endsWith("a"))
    }

    @Test
    fun singleParticipantHasNoThreatViolation() {
        val candidates = mapOf(
            "tank" to listOf(candidate("tank", "only", emptyMap(), 1.0, threat = 0))
        )

        val result = TeamLoadoutAllocator.enumerate(
            participantOrder = listOf("tank"),
            candidates = candidates,
            bankQuantities = emptyMap(),
            tankName = "tank"
        )

        assertEquals(0, result.single().threatViolation)
    }

    private fun candidate(
        character: String,
        value: String,
        demand: Map<String, Int>,
        score: Double,
        threat: Int
    ) = TeamLoadoutCandidate(
        characterName = character,
        value = value,
        bankDemand = demand,
        heuristicScore = score,
        threat = threat,
        stableKey = "$character:$value"
    )
}
