package com.artifactsmmo.core.task

internal data class TeamLoadoutCandidate<T>(
    val characterName: String,
    val value: T,
    val bankDemand: Map<String, Int>,
    val heuristicScore: Double,
    val threat: Int,
    val stableKey: String
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
        tankName: String
    ): List<TeamLoadoutSelection<T>> {
        val results = mutableListOf<TeamLoadoutSelection<T>>()

        fun visit(
            index: Int,
            selected: LinkedHashMap<String, TeamLoadoutCandidate<T>>,
            demand: MutableMap<String, Int>
        ) {
            if (index == participantOrder.size) {
                val tankThreat = selected[tankName]?.threat ?: Int.MIN_VALUE
                val highestSupport = selected.filterKeys { it != tankName }.values.maxOfOrNull { it.threat }
                val violation = if (highestSupport == null) {
                    0
                } else {
                    (highestSupport.toLong() - tankThreat.toLong() + 1L)
                        .coerceAtLeast(0L)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                }
                results += TeamLoadoutSelection(
                    byCharacter = selected.toMap(),
                    bankDemand = demand.toMap(),
                    heuristicScore = selected.values.sumOf { it.heuristicScore },
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
        val hasThreatValid = results.any { it.threatViolation == 0 }
        return results
            .asSequence()
            .filter { !hasThreatValid || it.threatViolation == 0 }
            .sortedWith(
                compareBy<TeamLoadoutSelection<T>> { it.threatViolation }
                    .thenByDescending { it.heuristicScore }
                    .thenBy { it.bankDemand.values.sum() }
                    .thenBy { it.stableKey }
            )
            .toList()
    }
}
