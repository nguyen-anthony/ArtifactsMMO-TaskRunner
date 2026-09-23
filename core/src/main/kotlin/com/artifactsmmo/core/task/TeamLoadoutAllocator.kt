package com.artifactsmmo.core.task

internal data class TeamLoadoutCandidate<T>(
    val characterName: String,
    val value: T,
    val bankDemand: Map<String, Int>,
    val heuristicScore: Double,
    val threat: Int,
    val maxHp: Int,
    val stableKey: String
)

internal enum class TankTargetingClass {
    STRICT_THREAT,
    HP_TIEBREAK,
    INVALID
}

internal data class TeamLoadoutSelection<T>(
    val byCharacter: Map<String, TeamLoadoutCandidate<T>>,
    val bankDemand: Map<String, Int>,
    val heuristicScore: Double,
    val targetingClass: TankTargetingClass,
    val threatMargin: Int,
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
                val tank = selected[tankName] ?: return
                val supports = selected.filterKeys { it != tankName }.values
                val highestSupportThreat = supports.maxOfOrNull { it.threat } ?: Int.MIN_VALUE
                val tiedSupports = supports.filter { it.threat == tank.threat }
                val targetingClass = when {
                    supports.isEmpty() || tank.threat > highestSupportThreat -> TankTargetingClass.STRICT_THREAT
                    tank.threat == highestSupportThreat && tiedSupports.all { tank.maxHp < it.maxHp } ->
                        TankTargetingClass.HP_TIEBREAK
                    else -> TankTargetingClass.INVALID
                }
                results += TeamLoadoutSelection(
                    byCharacter = selected.toMap(),
                    bankDemand = demand.toMap(),
                    heuristicScore = selected.values.sumOf { it.heuristicScore },
                    targetingClass = targetingClass,
                    threatMargin = tank.threat - highestSupportThreat,
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
        return results
            .asSequence()
            .filter { it.targetingClass != TankTargetingClass.INVALID }
            .sortedWith(
                compareBy<TeamLoadoutSelection<T>> { it.targetingClass.ordinal }
                    .thenByDescending { it.heuristicScore }
                    .thenByDescending { it.threatMargin }
                    .thenBy { it.bankDemand.values.sum() }
                    .thenBy { it.stableKey }
            )
            .toList()
    }
}
