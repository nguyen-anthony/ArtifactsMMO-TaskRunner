package com.artifactsmmo.core.task

internal data class TeamLoadoutCandidate<T>(
    val characterName: String,
    val value: T,
    val bankDemand: Map<String, Int>,
    val heuristicScore: Double,
    val threat: Int,
    val stableKey: String,
    /** Heuristic expected damage per turn of this loadout (see GearOptimizer.evaluateLoadout). */
    val expectedDamage: Double = 0.0,
    /** Heuristic turns this loadout survives being the boss's target. */
    val effectiveHp: Double = 0.0,
    /** Projected max HP with this loadout (boss threat ties go to the LOWEST max HP); 0 = unknown. */
    val maxHp: Int = 0,
    /** HP this loadout restores to its wearer per turn (self-heal rune, lifesteal). */
    val selfHealPerTurn: Double = 0.0,
    /** % of max HP this loadout heals every OTHER participant per turn (healing aura). */
    val allyHealPercentPerTurn: Double = 0.0
)

internal data class TeamLoadoutSelection<T>(
    val byCharacter: Map<String, TeamLoadoutCandidate<T>>,
    val bankDemand: Map<String, Int>,
    val heuristicScore: Double,
    val threatViolation: Int,
    val stableKey: String
)

internal object TeamLoadoutAllocator {
    fun <T> enumerate(
        participantOrder: List<String>,
        candidates: Map<String, List<TeamLoadoutCandidate<T>>>,
        bankQuantities: Map<String, Int>,
        tankName: String,
        /**
         * Whole-team ranking value; defaults to the sum of per-character heuristic scores.
         * Boss teams pass a score that couples the tank's survival with the team's total damage.
         */
        teamScore: (Map<String, TeamLoadoutCandidate<T>>) -> Double = { team -> team.values.sumOf { it.heuristicScore } }
    ): List<TeamLoadoutSelection<T>> {
        val results = mutableListOf<TeamLoadoutSelection<T>>()

        fun visit(
            index: Int,
            selected: LinkedHashMap<String, TeamLoadoutCandidate<T>>,
            demand: MutableMap<String, Int>
        ) {
            if (index == participantOrder.size) {
                val violation = threatViolation(selected, tankName)
                results += TeamLoadoutSelection(
                    byCharacter = selected.toMap(),
                    bankDemand = demand.toMap(),
                    heuristicScore = teamScore(selected),
                    threatViolation = violation,
                    stableKey = participantOrder.joinToString("|") { selected.getValue(it).stableKey }
                )
                return
            }

            val name = participantOrder[index]
            for (candidate in candidates[name].orEmpty().sortedBy { it.stableKey }) {
                var fits = true
                val nextDemand = demand.toMutableMap()
                for ((code, quantity) in candidate.bankDemand) {
                    val total = nextDemand.getOrDefault(code, 0) + quantity
                    if (total > bankQuantities.getOrDefault(code, 0)) {
                        fits = false
                        break
                    }
                    nextDemand[code] = total
                }
                if (!fits) continue
                selected[name] = candidate
                visit(index + 1, selected, nextDemand)
                selected.remove(name)
            }
        }

        visit(0, linkedMapOf(), mutableMapOf())
        return preferThreatValid(results.sortedWith(
            compareByDescending<TeamLoadoutSelection<T>> { it.heuristicScore }
                .thenBy { it.bankDemand.values.sum() }
                .thenBy { it.stableKey }
        ))
    }

    /**
     * The participant the boss attacks: highest threat; on a tie, the LOWEST max HP
     * (unknown HP = 0 never wins a tie over a known one); then name, for determinism.
     */
    fun <T> predictedTarget(team: Map<String, TeamLoadoutCandidate<T>>): String? =
        team.values.sortedWith(
            compareByDescending<TeamLoadoutCandidate<T>> { it.threat }
                .thenBy { if (it.maxHp > 0) it.maxHp else Int.MAX_VALUE }
                .thenBy { it.characterName }
        ).firstOrNull()?.characterName

    /**
     * Threat a support would have to shed so the boss targets the tank instead: a support
     * with more threat, or EQUAL threat and lower max HP (the tiebreak), is a violation.
     * 0 = the tank is the predicted target.
     */
    fun <T> threatViolation(team: Map<String, TeamLoadoutCandidate<T>>, tankName: String): Int {
        val tank = team[tankName] ?: return 0
        return team.filterKeys { it != tankName }.values.maxOfOrNull { support ->
            val lowerHp = support.maxHp > 0 && tank.maxHp > 0 && support.maxHp < tank.maxHp
            (support.threat.toLong() - tank.threat.toLong() + (if (lowerHp) 1 else 0))
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        } ?: 0
    }

    /**
     * Keeps only teams where no support out-threatens the tank, when at least one exists;
     * otherwise orders by smallest violation. Relative order is otherwise preserved.
     */
    fun <T> preferThreatValid(teams: List<TeamLoadoutSelection<T>>): List<TeamLoadoutSelection<T>> =
        if (teams.any { it.threatViolation == 0 }) teams.filter { it.threatViolation == 0 }
        else teams.sortedBy { it.threatViolation }
}
