package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.CombatSimulationData
import com.artifactsmmo.client.models.Item
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Greedy slot-by-slot gear optimizer.
 *
 * Two modes:
 *  - [optimize]: full pass using inventory + bank candidates, API sim for scoring
 *  - [optimizeInventoryOnly]: fast pass using inventory-only candidates, local sim only
 *
 * Both modes return an [OptimizationResult] describing gear changes and score deltas.
 */
class GearOptimizer(private val helper: ActionHelper) {

    companion object {
        val GEAR_SLOTS = listOf(
            ActionHelper.SlotInfo("shield",     "shield",     "gearcrafting"),
            ActionHelper.SlotInfo("helmet",     "helmet",     "gearcrafting"),
            ActionHelper.SlotInfo("body_armor", "body_armor", "gearcrafting"),
            ActionHelper.SlotInfo("leg_armor",  "leg_armor",  "gearcrafting"),
            ActionHelper.SlotInfo("boots",      "boots",      "gearcrafting"),
            ActionHelper.SlotInfo("ring1",      "ring",       "jewelrycrafting"),
            ActionHelper.SlotInfo("ring2",      "ring",       "jewelrycrafting"),
            ActionHelper.SlotInfo("amulet",     "amulet",     "jewelrycrafting")
        )

        const val DISTANCE_THRESHOLD = 40
    }

    /** Keyed by characterName → last optimized monster code */
    private val lastOptimizedFor = mutableMapOf<String, String>()

    data class GearScore(
        val winRate: Double,
        val avgWinTurns: Double,
        val prospecting: Int,
        val wisdom: Int
    ) : Comparable<GearScore> {
        override fun compareTo(other: GearScore): Int {
            // Primary: win rate descending (1% minimum meaningful difference)
            if (this.winRate > other.winRate + 0.01) return 1
            if (this.winRate < other.winRate - 0.01) return -1
            // Secondary: avg win turns ascending (1 turn minimum meaningful difference)
            if (this.avgWinTurns < other.avgWinTurns - 1.0) return 1
            if (this.avgWinTurns > other.avgWinTurns + 1.0) return -1
            // Tertiary: prospecting + wisdom descending
            return (this.prospecting + this.wisdom).compareTo(other.prospecting + other.wisdom)
        }
    }

    data class SlotChange(
        val slot: String,
        val fromItemCode: String,
        val toItemCode: String,
        val source: String
    )

    data class OptimizationResult(
        val equipActions: List<ActionHelper.EquipAction>,
        val baselineScore: GearScore,
        val optimizedScore: GearScore,
        val slotChanges: List<SlotChange>
    )

    /**
     * Full optimization pass: inventory + bank candidates, API sim for scoring.
     */
    suspend fun optimize(char: Character, monsterCode: String): OptimizationResult {
        val monster = try { helper.contentCache.getMonster(monsterCode) }
                      catch (_: Exception) { return emptyResult(char, monsterCode) }

        // Baseline via API sim
        val baseSim = try {
            helper.simulateFightWithSlotOverrides(char, monsterCode, emptyMap(), 100)
        } catch (_: Exception) {
            return emptyResult(char, monsterCode)
        }
        val baselineScore = simToScore(baseSim, char)

        val accumulatedOverrides = mutableMapOf<String, String>()
        val slotChanges = mutableListOf<SlotChange>()
        var bestScore = baselineScore

        for (slotInfo in GEAR_SLOTS) {
            val candidates = try {
                helper.getAvailableEquipmentForSlot(char, slotInfo)
                    .filter { it.source == "inventory" || it.source == "bank" }
            } catch (_: Exception) { continue }

            if (candidates.isEmpty()) continue

            var bestCandidate: ActionHelper.EquipmentOption? = null

            for (candidate in candidates) {
                val testOverrides = accumulatedOverrides + (slotInfo.slot to candidate.item.code)

                // Local sim prune — skip only if local sim says clearly worse AND we're not
                // already at 100% win rate. At 100% win rate, turn count is the tiebreaker
                // and 50-iteration local sims have too much variance to prune reliably —
                // a 2-loss sample (96%) is noise, not evidence that the item is worse.
                val localResult = try {
                    helper.simulateLocalWithOverrides(char, monster, testOverrides, 50)
                } catch (_: Exception) { null }
                val atMaxWinRate = bestScore.winRate >= 0.99
                if (!atMaxWinRate && localResult != null && localResult.winRate < bestScore.winRate - 0.02) continue

                // API sim
                val apiResult = try {
                    val r = helper.simulateFightWithSlotOverrides(char, monsterCode, testOverrides, 100)
                    delay(1.seconds)
                    r
                } catch (_: Exception) { continue }

                val candidateScore = GearScore(
                    winRate      = apiResult.winrate,
                    avgWinTurns  = avgWinTurns(apiResult),
                    prospecting  = char.prospecting + prospectingDelta(candidate.item),
                    wisdom       = char.wisdom + wisdomDelta(candidate.item)
                )

                if (candidateScore > bestScore) {
                    bestCandidate = candidate
                    bestScore = candidateScore
                }
            }

            if (bestCandidate != null) {
                val fromCode = helper.getEquippedInSlot(char, slotInfo.slot)
                accumulatedOverrides[slotInfo.slot] = bestCandidate.item.code
                slotChanges.add(SlotChange(
                    slot         = slotInfo.slot,
                    fromItemCode = fromCode,
                    toItemCode   = bestCandidate.item.code,
                    source       = bestCandidate.source
                ))
            }
        }

        // Refinement pass: re-evaluate each winner with all other changes applied
        val finalChanges = slotChanges.toMutableList()
        val iterator = finalChanges.iterator()
        while (iterator.hasNext()) {
            val change = iterator.next()
            // Build overrides without this slot's change, keeping all others
            val othersOverrides = finalChanges
                .filter { it.slot != change.slot }
                .associate { it.slot to it.toItemCode }
            val testOverrides = othersOverrides + (change.slot to change.toItemCode)
            val recheck = try {
                helper.simulateFightWithSlotOverrides(char, monsterCode, testOverrides, 100)
            } catch (_: Exception) { continue }
            val recheckScore = simToScore(recheck, char)
            val baselineOverrides = othersOverrides
            val baseRecheck = try {
                helper.simulateFightWithSlotOverrides(char, monsterCode, baselineOverrides, 100)
            } catch (_: Exception) { continue }
            val baseRecheckScore = simToScore(baseRecheck, char)
            if (recheckScore <= baseRecheckScore) iterator.remove()
        }

        val equipActions = finalChanges.map {
            ActionHelper.EquipAction(slot = it.slot, itemCode = it.toItemCode, source = it.source)
        }

        // Final score with all accepted changes
        val finalOverrides = finalChanges.associate { it.slot to it.toItemCode }
        val optimizedScore = if (finalOverrides.isEmpty()) baselineScore else try {
            simToScore(
                helper.simulateFightWithSlotOverrides(char, monsterCode, finalOverrides, 100),
                char
            )
        } catch (_: Exception) { bestScore }

        return OptimizationResult(equipActions, baselineScore, optimizedScore, finalChanges)
    }

    /**
     * Inventory-only optimization pass: no bank trips, local sim only.
     * One final API sim if any changes found.
     */
    suspend fun optimizeInventoryOnly(char: Character, monsterCode: String): OptimizationResult {
        val monster = try { helper.contentCache.getMonster(monsterCode) }
                      catch (_: Exception) { return emptyResult(char, monsterCode) }

        val baseSim = try {
            helper.simulateFightWithSlotOverrides(char, monsterCode, emptyMap(), 100)
        } catch (_: Exception) { return emptyResult(char, monsterCode) }
        val baselineScore = simToScore(baseSim, char)

        val accumulatedOverrides = mutableMapOf<String, String>()
        val slotChanges = mutableListOf<SlotChange>()
        var bestScore = baselineScore

        for (slotInfo in GEAR_SLOTS) {
            val candidates = try {
                helper.getAvailableEquipmentForSlot(char, slotInfo)
                    .filter { it.source == "inventory" }
            } catch (_: Exception) { continue }

            if (candidates.isEmpty()) continue

            var bestCandidate: ActionHelper.EquipmentOption? = null

            for (candidate in candidates) {
                val testOverrides = accumulatedOverrides + (slotInfo.slot to candidate.item.code)
                val localResult = try {
                    helper.simulateLocalWithOverrides(char, monster, testOverrides, 50)
                } catch (_: Exception) { continue }

                val candidateScore = GearScore(
                    winRate      = localResult.winRate,
                    avgWinTurns  = Double.MAX_VALUE, // local sim doesn't track turns
                    prospecting  = char.prospecting + prospectingDelta(candidate.item),
                    wisdom       = char.wisdom + wisdomDelta(candidate.item)
                )
                if (candidateScore > bestScore) {
                    bestCandidate = candidate
                    bestScore = candidateScore
                }
            }

            if (bestCandidate != null) {
                val fromCode = helper.getEquippedInSlot(char, slotInfo.slot)
                accumulatedOverrides[slotInfo.slot] = bestCandidate.item.code
                slotChanges.add(SlotChange(
                    slot         = slotInfo.slot,
                    fromItemCode = fromCode,
                    toItemCode   = bestCandidate.item.code,
                    source       = bestCandidate.source
                ))
            }
        }

        val equipActions = slotChanges.map {
            ActionHelper.EquipAction(slot = it.slot, itemCode = it.toItemCode, source = it.source)
        }

        // One final API sim for accurate score if any changes were found
        val finalOverrides = slotChanges.associate { it.slot to it.toItemCode }
        val optimizedScore = if (finalOverrides.isEmpty()) baselineScore else try {
            simToScore(
                helper.simulateFightWithSlotOverrides(char, monsterCode, finalOverrides, 100),
                char
            )
        } catch (_: Exception) { bestScore }

        return OptimizationResult(equipActions, baselineScore, optimizedScore, slotChanges)
    }

    fun markOptimized(characterName: String, monsterCode: String) {
        lastOptimizedFor[characterName] = monsterCode
    }

    fun getLastOptimizedMonster(characterName: String): String? = lastOptimizedFor[characterName]

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun simToScore(sim: CombatSimulationData, char: Character) = GearScore(
        winRate     = sim.winrate,
        avgWinTurns = avgWinTurns(sim),
        prospecting = char.prospecting,
        wisdom      = char.wisdom
    )

    private fun avgWinTurns(sim: CombatSimulationData): Double =
        sim.results.filter { it.result == "win" }
                   .map { it.turns }
                   .let { if (it.isEmpty()) Double.MAX_VALUE else it.average() }

    private fun prospectingDelta(item: Item): Int =
        item.effects.find { it.code == "prospecting" }?.value ?: 0

    private fun wisdomDelta(item: Item): Int =
        item.effects.find { it.code == "wisdom" }?.value ?: 0

    private fun emptyResult(char: Character, monsterCode: String): OptimizationResult {
        val score = GearScore(0.0, Double.MAX_VALUE, char.prospecting, char.wisdom)
        return OptimizationResult(emptyList(), score, score, emptyList())
    }
}
