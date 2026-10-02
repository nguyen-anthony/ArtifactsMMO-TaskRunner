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
                // Strictly more threat than the tank — a real violation.
                candidate("support", "high-score-threat", emptyMap(), 100.0, 11),
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
    fun equalThreatIsNotAViolation() {
        // A support tied with the tank on threat used to count as a violation (off-by-one);
        // it shouldn't — ties still favor the tank in-game (higher HP, then random).
        val candidates = mapOf(
            "tank" to listOf(candidate("tank", "tank", emptyMap(), 5.0, 10)),
            "support" to listOf(candidate("support", "tied", emptyMap(), 1.0, 10))
        )

        val result = TeamLoadoutAllocator.enumerate(
            participantOrder = listOf("tank", "support"),
            candidates = candidates,
            bankQuantities = emptyMap(),
            tankName = "tank"
        )

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

    @Test
    fun returnsEmptyWhenEveryCandidateWantsTheSameScarceItem() {
        // Regression: each character's independently-generated candidates all lean on the
        // same single scarce bank item (their shared "best" pick) — no per-character list
        // includes a variant that skips it, so no combination fits the bank at once.
        // TeamLoadoutAllocator correctly reports "no feasible team" here; CoopOptimizer's
        // shared-claim greedy pass (not unit-tested here — needs a live ActionHelper) is what
        // recovers a fitting team by construction instead of leaving everyone on current gear.
        val candidates = mapOf(
            "tank" to listOf(candidate("tank", "wants-trophy", mapOf("lich_race_trophy" to 1), 10.0, 10)),
            "a" to listOf(candidate("a", "wants-trophy", mapOf("lich_race_trophy" to 1), 8.0, 2)),
            "b" to listOf(candidate("b", "wants-trophy", mapOf("lich_race_trophy" to 1), 7.0, 2)),
        )

        val result = TeamLoadoutAllocator.enumerate(
            participantOrder = listOf("tank", "a", "b"),
            candidates = candidates,
            bankQuantities = mapOf("lich_race_trophy" to 1),
            tankName = "tank"
        )

        assertTrue(result.isEmpty())
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
