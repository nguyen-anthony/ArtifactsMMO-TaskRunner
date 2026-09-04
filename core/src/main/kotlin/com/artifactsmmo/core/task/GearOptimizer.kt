package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.CombatSimulationData
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Monster

/**
 * Unified weapon + gear + utility slot optimizer.
 *
 * Design principles:
 *  - Gear/weapon greedy pass uses HEURISTIC scoring (offense + defense formula), not
 *    per-candidate simulation — mirrors [CoopOptimizer]'s scoring philosophy so solo and
 *    boss-fight gear selection behave consistently. A solo fighter is scored with balanced
 *    offense/defense weights and no threat term (see [scoreItemSolo]).
 *  - Real API sim is used only for: the baseline score, one post-gear-pass score (feeds
 *    the utility pass), the utility slot greedy pass itself, and the final validation —
 *    never for per-candidate gear/weapon comparisons. This avoids depending on the local
 *    simulator's fidelity for the decision that matters most (which item to equip).
 *  - Weapon is optimized as part of the greedy pass (no separate weapon logic)
 *  - Artifact slots enforce uniqueness (game constraint: no duplicate artifacts)
 *  - Utility slot pass runs only for monsters with effects — tries owned potions
 *  - Cache-and-verify: if a same-level character recently optimized against this monster,
 *    the cached loadout is verified with a single API sim before being reused
 *
 * Public entry points:
 *  - [optimizeWithCacheHint] — preferred; uses the session cache
 *  - [optimize] — full optimization pass, always executes
 *
 * @see SimulationRateLimiter for the global 1/sec API sim rate limit
 */
class GearOptimizer(
    private val helper: ActionHelper,
    private val knownLoadoutStore: KnownLoadoutStore = KnownLoadoutStore()
) {

    companion object {
        /**
         * All combat equipment slots evaluated by the greedy pass, in evaluation order.
         * Weapon is first because it anchors damage output — every other slot benefits
         * from being simulated against the optimal weapon.
         */
        val GEAR_SLOTS = listOf(
            ActionHelper.SlotInfo("weapon",     "weapon",     "weaponcrafting"),
            ActionHelper.SlotInfo("shield",     "shield",     "gearcrafting"),
            ActionHelper.SlotInfo("helmet",     "helmet",     "gearcrafting"),
            ActionHelper.SlotInfo("body_armor", "body_armor", "gearcrafting"),
            ActionHelper.SlotInfo("leg_armor",  "leg_armor",  "gearcrafting"),
            ActionHelper.SlotInfo("boots",      "boots",      "gearcrafting"),
            ActionHelper.SlotInfo("ring1",      "ring",       "jewelrycrafting"),
            ActionHelper.SlotInfo("ring2",      "ring",       "jewelrycrafting"),
            ActionHelper.SlotInfo("amulet",     "amulet",     "jewelrycrafting"),
            ActionHelper.SlotInfo("artifact1",  "artifact",   null),
            ActionHelper.SlotInfo("artifact2",  "artifact",   null),
            ActionHelper.SlotInfo("artifact3",  "artifact",   null),
            ActionHelper.SlotInfo("rune",       "rune",       null)
        )

        val UTILITY_SLOT_NAMES = listOf("utility1", "utility2")
        val ARTIFACT_SLOTS = setOf("artifact1", "artifact2", "artifact3")

        const val DISTANCE_THRESHOLD = 40
        const val LOCAL_SIM_ITERATIONS = 100
        const val API_SIM_ITERATIONS = 100
        const val GREEDY_API_ITERATIONS = 50
        const val BEAM_WIDTH = 48
        const val CANDIDATES_PER_SLOT = 10
        const val API_GEAR_FINALISTS = 3
        const val CACHE_VERIFY_MIN_WINRATE = 0.85
        const val UTILITY_MAX_QUANTITY = 100
        const val OPTIMIZER_VERSION = "loadout-beam-v1"

        /**
         * Effect codes that identify a consumable as a valid UTILITY potion (utility1/utility2).
         *
         * Distinct from food/use consumables whose effects are `heal`, `gold`, or `teleport`
         * — those go to the /action/use endpoint and are NOT valid utility slot items.
         * Kept in sync with [CoopOptimizer]'s equivalent set.
         */
        val UTILITY_EFFECT_CODES = setOf(
            "restore",
            "splash_restore",
            "antipoison", "antidote",
            "boost_hp",
            "boost_dmg", "boost_dmg_fire", "boost_dmg_earth", "boost_dmg_water", "boost_dmg_air",
            "boost_res_fire", "boost_res_earth", "boost_res_water", "boost_res_air",
            "boost_prospecting", "boost_critical_strike"
        )

        internal fun evaluateLoadout(stats: Character, monster: Monster): LoadoutHeuristic {
            fun multiplier(resistance: Int): Double = (1.0 - resistance / 100.0).coerceAtLeast(0.0)
            val critMultiplier = 1.0 + 0.5 * stats.criticalStrike.coerceIn(0, 100) / 100.0
            val monsterCritMultiplier = 1.0 + 0.5 * monster.criticalStrike.coerceIn(0, 100) / 100.0
            val expectedDamage = (
                stats.attackFire * (1.0 + (stats.dmg + stats.dmgFire) / 100.0) * multiplier(monster.resFire) +
                    stats.attackEarth * (1.0 + (stats.dmg + stats.dmgEarth) / 100.0) * multiplier(monster.resEarth) +
                    stats.attackWater * (1.0 + (stats.dmg + stats.dmgWater) / 100.0) * multiplier(monster.resWater) +
                    stats.attackAir * (1.0 + (stats.dmg + stats.dmgAir) / 100.0) * multiplier(monster.resAir)
                ) * critMultiplier
            val expectedIncoming = (
                monster.attackFire * multiplier(stats.resFire) +
                    monster.attackEarth * multiplier(stats.resEarth) +
                    monster.attackWater * multiplier(stats.resWater) +
                    monster.attackAir * multiplier(stats.resAir)
                ) * monsterCritMultiplier
            val survivalTurns = stats.maxHp.toDouble() / expectedIncoming.coerceAtLeast(1.0)
            val killTurns = monster.hp.toDouble() / expectedDamage.coerceAtLeast(1.0)
            return LoadoutHeuristic(
                combatRatio = survivalTurns / killTurns.coerceAtLeast(1.0),
                expectedDamage = expectedDamage,
                effectiveHp = survivalTurns,
                initiativeAdvantage = stats.initiative - monster.initiative,
                utilityStats = stats.prospecting + stats.wisdom
            )
        }
    }

    /** Records which monster each character was most recently optimized for. */
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

    internal data class LoadoutHeuristic(
        val combatRatio: Double,
        val expectedDamage: Double,
        val effectiveHp: Double,
        val initiativeAdvantage: Int,
        val utilityStats: Int
    )

    private data class BeamState(
        val loadout: Map<String, Item>,
        val usedQuantities: Map<String, Int>,
        val stats: Character,
        val heuristic: LoadoutHeuristic,
        val changedSlots: Int,
        val signature: String
    )

    data class SlotChange(
        val slot: String,
        val fromItemCode: String,
        val toItemCode: String,
        val source: String
    )

    /** Utility potion loadout — quantity semantics matter for the stack. */
    data class UtilityEquipAction(
        val slot: String,       // "utility1" or "utility2"
        val itemCode: String,
        val quantity: Int,
        val source: String      // "inventory" or "bank"
    )

    data class OptimizationResult(
        val equipActions: List<ActionHelper.EquipAction>,
        val utilityActions: List<UtilityEquipAction>,
        val baselineScore: GearScore,
        val optimizedScore: GearScore,
        val slotChanges: List<SlotChange>,
        /**
         * The full intended loadout across ALL slots (both changed and unchanged),
         * as an absolute mapping of slot → itemCode. Used for cache-and-verify so a
         * second character starting from a different baseline can compute the correct
         * delta from their own currently-equipped gear.
         *
         * For unchanged slots, the itemCode is what the first character already had
         * equipped in that slot; for changed slots it's the greedy pass's winning code.
         */
        val targetLoadout: Map<String, String> = emptyMap(),
        /** Absolute intended utility loadout, including quantities. */
        val targetUtilities: Map<String, Pair<String, Int>> = emptyMap(),
        /** True when this result came from a previously validated known-good record. */
        val reusedKnownLoadout: Boolean = false
    )

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Reuse an exact character/level known-good loadout when its complete item multiset is
     * still available and an API verification still meets the required safety floor.
     */
    suspend fun optimizeWithCacheHint(char: Character, monsterCode: String): OptimizationResult {
        val cached = knownLoadoutStore.find(char.name, char.level, monsterCode, OPTIMIZER_VERSION)
        if (cached != null && requiredItemsAvailable(char, cached.requiredItems)) {
            val plan = buildActionsForTarget(char, cached.gear, cached.utilities)
            val verifySim = try {
                if (cached.utilities.isEmpty()) {
                    helper.simulateFightWithSlotOverrides(char, monsterCode, cached.gear, API_SIM_ITERATIONS)
                } else {
                    helper.simulateFightWithSlotAndUtilityOverrides(
                        char,
                        monsterCode,
                        cached.gear,
                        cached.utilities.mapValues { it.value.code },
                        cached.utilities.mapValues { it.value.quantity },
                        API_SIM_ITERATIONS
                    )
                }
            } catch (e: Exception) {
                println("[${char.name}] GearOptimizer: known-loadout verification failed: ${e.message}")
                null
            }
            if (verifySim != null && verifySim.winrate >= CACHE_VERIFY_MIN_WINRATE) {
                println("[${char.name}] GearOptimizer: reusing known-good loadout for $monsterCode (${(verifySim.winrate * 100).toInt()}%)")
                val score = simToScore(verifySim, char)
                return OptimizationResult(
                    equipActions = plan.first,
                    utilityActions = plan.second,
                    baselineScore = score,
                    optimizedScore = score,
                    slotChanges = plan.third,
                    targetLoadout = cached.gear,
                    targetUtilities = cached.utilities.mapValues { it.value.code to it.value.quantity },
                    reusedKnownLoadout = true
                )
            }
        }

        val result = optimize(char, monsterCode)
        if (result.targetLoadout.isNotEmpty() && result.optimizedScore >= result.baselineScore) {
            val utilities = result.targetUtilities.mapValues { KnownUtilityLoadout(it.value.first, it.value.second) }
            knownLoadoutStore.put(
                KnownLoadout(
                    characterName = char.name,
                    exactLevel = char.level,
                    monsterCode = monsterCode,
                    optimizerVersion = OPTIMIZER_VERSION,
                    gear = result.targetLoadout,
                    utilities = utilities,
                    requiredItems = requiredItemCounts(result.targetLoadout, result.targetUtilities),
                    validatedWinRate = result.optimizedScore.winRate,
                    averageWinTurns = result.optimizedScore.avgWinTurns.takeIf { it < Double.MAX_VALUE },
                    validatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
        return result
    }

    /**
     * Full greedy optimization pass.
     * See class kdoc for algorithm details.
     */
    suspend fun optimize(char: Character, monsterCode: String): OptimizationResult {
        val monster = try { helper.contentCache.getMonster(monsterCode) }
                      catch (_: Exception) { return emptyResult(char) }

        val hasEffects = monster.effects.isNotEmpty()

        // Baseline — always API sim for an accurate starting score
        val baseSim = try {
            helper.simulateFightWithSlotOverrides(char, monsterCode, emptyMap(), API_SIM_ITERATIONS)
        } catch (e: Exception) {
            println("[${char.name}] GearOptimizer: baseline sim failed: ${e.message}")
            return emptyResult(char)
        }
        val baselineScore = simToScore(baseSim, char)

        // ── Bounded whole-loadout beam search ──
        // Search uses complete aggregate character stats so weapon attack, elemental damage,
        // critical strike, HP, resistance, and initiative interactions are evaluated together.
        // The current loadout is always the incumbent and remains selected unless a finalist
        // demonstrates a real API-sim improvement.
        val currentTarget = currentGearTarget(char)
        var selectedTarget = currentTarget
        var selectedStats = char
        var postGearScore = baselineScore

        val finalists = searchGearLoadouts(char, monster)
            .filter { state -> state.loadout.any { (slot, item) -> currentTarget[slot] != item.code } }
            .take(API_GEAR_FINALISTS)

        for (finalist in finalists) {
            val target = finalist.loadout.mapValues { it.value.code }
            val simulated = try {
                helper.simulateFightWithSlotOverrides(char, monsterCode, target, API_SIM_ITERATIONS)
            } catch (e: Exception) {
                println("[${char.name}] GearOptimizer: beam finalist validation failed: ${e.message}")
                continue
            }
            val score = simToScore(simulated, finalist.stats)
            if (score > postGearScore) {
                selectedTarget = target
                selectedStats = finalist.stats
                postGearScore = score
            }
        }

        val accumulatedOverrides = selectedTarget.toMutableMap()
        var validationSucceeded = true

        // ── Utility slot pass — only for monsters with effects ──
        val utilityActions = if (hasEffects) {
            optimizeUtilitySlots(char, monsterCode, accumulatedOverrides, postGearScore)
        } else {
            emptyList()
        }

        // ── Final API sim validation with the full loadout ──
        val optimizedScore = if (accumulatedOverrides.all { (slot, code) -> helper.getEquippedInSlot(char, slot) == code } && utilityActions.isEmpty()) {
            baselineScore
        } else if (utilityActions.isEmpty()) {
            postGearScore
        } else {
            try {
                val utilityOverrides = utilityActions.associate { it.slot to it.itemCode }
                val utilityQuantities = utilityActions.associate { it.slot to it.quantity }
                val finalSim = helper.simulateFightWithSlotAndUtilityOverrides(
                    char, monsterCode, accumulatedOverrides, utilityOverrides, utilityQuantities, API_SIM_ITERATIONS
                )
                simToScore(finalSim, selectedStats)
            } catch (_: Exception) {
                validationSucceeded = false
                postGearScore
            }
        }

        // Build the full target loadout: for every gear slot, record what SHOULD be equipped.
        // Slots present in accumulatedOverrides use the winning candidate; other slots keep
        // whatever the character had equipped. This is what the cache needs to correctly
        // apply the same target loadout to a second character starting from a different baseline.
        val targetLoadout: Map<String, String> = GEAR_SLOTS.mapNotNull { slotInfo ->
            val target = accumulatedOverrides[slotInfo.slot] ?: helper.getEquippedInSlot(char, slotInfo.slot)
            if (target.isEmpty()) null else slotInfo.slot to target
        }.toMap()
        val targetUtilities = absoluteUtilityTarget(char, utilityActions)

        if (!validationSucceeded || optimizedScore < baselineScore) {
            println("[${char.name}] GearOptimizer: proposed loadout did not validate above baseline — keeping current loadout")
            return currentLoadoutResult(char, baselineScore)
        }

        val actions = buildActionsForTarget(
            char,
            targetLoadout,
            targetUtilities.mapValues { KnownUtilityLoadout(it.value.first, it.value.second) }
        )

        return OptimizationResult(
            equipActions = actions.first,
            utilityActions = actions.second,
            baselineScore = baselineScore,
            optimizedScore = optimizedScore,
            slotChanges = actions.third,
            targetLoadout = targetLoadout,
            targetUtilities = targetUtilities
        )
    }

    private val beamStateComparator =
        compareByDescending<BeamState> { it.heuristic.combatRatio }
            .thenByDescending { it.heuristic.expectedDamage }
            .thenByDescending { it.heuristic.effectiveHp }
            .thenByDescending { it.heuristic.initiativeAdvantage }
            .thenByDescending { it.heuristic.utilityStats }
            .thenBy { it.changedSlots }
            .thenBy { it.signature }

    private suspend fun searchGearLoadouts(char: Character, monster: Monster): List<BeamState> {
        val currentItems = mutableMapOf<String, Item>()
        for (slotInfo in GEAR_SLOTS) {
            val code = helper.getEquippedInSlot(char, slotInfo.slot)
            if (code.isEmpty()) continue
            try {
                helper.contentCache.getItemOrNull(code)?.let { currentItems[slotInfo.slot] = it }
            } catch (_: Exception) {}
        }

        val availableQuantities = accessibleGearQuantities(char)
        val candidatesBySlot = buildBeamCandidates(char, monster, currentItems)
        val initial = makeBeamState(
            char = char,
            monster = monster,
            loadout = currentItems,
            usedQuantities = currentItems.values.groupingBy { it.code }.eachCount(),
            stats = char,
            currentLoadout = currentItems
        )
        var beam = listOf(initial)

        for (slotInfo in GEAR_SLOTS) {
            val candidates = candidatesBySlot[slotInfo.slot].orEmpty()
            if (candidates.isEmpty()) continue
            val expanded = mutableListOf<BeamState>()

            for (state in beam) {
                val oldItem = state.loadout[slotInfo.slot]
                for (candidate in candidates) {
                    val counts = state.usedQuantities.toMutableMap()
                    oldItem?.let {
                        val remaining = counts.getOrDefault(it.code, 0) - 1
                        if (remaining <= 0) counts.remove(it.code) else counts[it.code] = remaining
                    }
                    counts[candidate.code] = counts.getOrDefault(candidate.code, 0) + 1
                    if (counts.getValue(candidate.code) > availableQuantities.getOrDefault(candidate.code, 0)) continue

                    val loadout = state.loadout + (slotInfo.slot to candidate)
                    if (!artifactsAreUnique(loadout)) continue
                    val stats = if (oldItem?.code == candidate.code) {
                        state.stats
                    } else {
                        state.stats.applyItemDelta(oldItem, candidate)
                    }
                    expanded += makeBeamState(char, monster, loadout, counts, stats, currentItems)
                }
            }

            beam = expanded
                .sortedWith(beamStateComparator)
                .distinctBy { canonicalLoadoutKey(it.loadout) }
                .take(BEAM_WIDTH)
            if (beam.isEmpty()) return listOf(initial)
        }
        return beam.sortedWith(beamStateComparator)
    }

    private suspend fun buildBeamCandidates(
        char: Character,
        monster: Monster,
        currentLoadout: Map<String, Item>
    ): Map<String, List<Item>> {
        return GEAR_SLOTS.associate { slotInfo ->
            val current = currentLoadout[slotInfo.slot]
            val discovered = try {
                helper.getAvailableEquipmentForSlot(char, slotInfo)
                    .filter { it.source == "inventory" || it.source == "bank" }
                    .filter { slotInfo.slot != "weapon" || it.item.subtype != "tool" }
                    .map { it.item }
            } catch (_: Exception) {
                emptyList()
            }
            val ranked = (discovered + listOfNotNull(current))
                .distinctBy { it.code }
                .map { item ->
                    val stats = if (current?.code == item.code) char else char.applyItemDelta(current, item)
                    item to evaluateLoadout(stats, monster)
                }
                .sortedWith(
                    compareByDescending<Pair<Item, LoadoutHeuristic>> { it.second.combatRatio }
                        .thenByDescending { it.second.expectedDamage }
                        .thenByDescending { it.first.level }
                        .thenBy { it.first.code }
                )
                .map { it.first }

            val bounded = ranked.take(CANDIDATES_PER_SLOT).toMutableList()
            if (current != null && bounded.none { it.code == current.code }) {
                if (bounded.size == CANDIDATES_PER_SLOT) bounded.removeAt(bounded.lastIndex)
                bounded += current
            }
            slotInfo.slot to bounded.distinctBy { it.code }
        }
    }

    private fun makeBeamState(
        char: Character,
        monster: Monster,
        loadout: Map<String, Item>,
        usedQuantities: Map<String, Int>,
        stats: Character,
        currentLoadout: Map<String, Item>
    ): BeamState {
        val changed = GEAR_SLOTS.count { loadout[it.slot]?.code != currentLoadout[it.slot]?.code }
        return BeamState(
            loadout = loadout,
            usedQuantities = usedQuantities,
            stats = stats,
            heuristic = evaluateLoadout(stats, monster),
            changedSlots = changed,
            signature = loadoutSignature(loadout)
        )
    }

    private fun artifactsAreUnique(loadout: Map<String, Item>): Boolean {
        val codes = ARTIFACT_SLOTS.mapNotNull { loadout[it]?.code }
        return codes.size == codes.distinct().size
    }

    private fun loadoutSignature(loadout: Map<String, Item>): String =
        GEAR_SLOTS.joinToString("|") { "${it.slot}=${loadout[it.slot]?.code.orEmpty()}" }

    private fun canonicalLoadoutKey(loadout: Map<String, Item>): String {
        val rings = listOf("ring1", "ring2").map { loadout[it]?.code.orEmpty() }.sorted().joinToString(",")
        val artifacts = ARTIFACT_SLOTS.map { loadout[it]?.code.orEmpty() }.sorted().joinToString(",")
        val fixed = GEAR_SLOTS.map { it.slot }
            .filter { it !in setOf("ring1", "ring2") && it !in ARTIFACT_SLOTS }
            .joinToString("|") { "$it=${loadout[it]?.code.orEmpty()}" }
        return "$fixed|rings=$rings|artifacts=$artifacts"
    }

    private fun currentGearTarget(char: Character): Map<String, String> =
        GEAR_SLOTS.mapNotNull { slotInfo ->
            helper.getEquippedInSlot(char, slotInfo.slot).takeIf { it.isNotEmpty() }?.let { slotInfo.slot to it }
        }.toMap()

    /**
     * Heuristic value of [item] in [slot] for a SOLO fighter against a monster whose attack
     * outputs are [monsterDmg] and whose resistances are [monsterRes]. Higher = better.
     *
     * Same formula as [CoopOptimizer]'s scoring but with balanced offense/defense weights
     * (1.0/1.0) and no threat term — see [optimize]'s kdoc comment for the rationale.
     */
    private fun scoreItemSolo(
        item: Item,
        slot: String,
        monsterDmg: Map<String, Int>,
        monsterRes: Map<String, Int>
    ): Double {
        val effects = item.effects.associate { it.code to it.value }
        val globalDmgBonus = (effects["dmg"] ?: 0) / 100.0

        // Offense: expected damage after monster resistance
        val offense = monsterRes.entries.sumOf { (element, res) ->
            val attackKey = "attack_$element"
            val dmgKey = "dmg_$element"
            val baseAttack = effects[attackKey] ?: 0
            val elementDmgBonus = (effects[dmgKey] ?: 0) / 100.0
            val resMult = (1.0 - res / 100.0).coerceAtLeast(0.0)
            baseAttack * (1.0 + globalDmgBonus + elementDmgBonus) * resMult
        }

        // Defense: HP + weighted resistance against the monster's attack outputs
        val hp = (effects["hp"] ?: 0).toDouble()
        val defenseFromRes = monsterDmg.entries.sumOf { (element, atk) ->
            val resKey = "res_$element"
            val resValue = (effects[resKey] ?: 0)
            atk * (resValue / 100.0)
        }
        val defense = hp + defenseFromRes

        val prospecting = (effects["prospecting"] ?: 0).toDouble()
        val wisdom = (effects["wisdom"] ?: 0).toDouble()
        val criticalStrike = (effects["critical_strike"] ?: 0).toDouble()
        val haste = (effects["haste"] ?: 0).toDouble()

        // Slot-specific weighting — weapons are pure offense, armor/shield pure defense,
        // jewelry/artifacts/rune are mixed. Matches CoopOptimizer's slot weighting scheme.
        val slotOffenseWeight = when (slot) {
            "weapon" -> 3.0
            "shield", "helmet", "body_armor", "leg_armor", "boots" -> 0.5
            else -> 1.0
        }
        val slotDefenseWeight = when (slot) {
            "shield", "helmet", "body_armor", "leg_armor", "boots" -> 2.0
            "weapon" -> 0.0
            else -> 1.0
        }

        return offense * slotOffenseWeight +
               defense * slotDefenseWeight +
               (prospecting + wisdom) * 0.1 +
               criticalStrike * 2.0 +
               haste * 1.5
    }

    // ── Utility optimization ──────────────────────────────────────

    /**
     * For monsters with effects, try adding owned potions to utility1 and utility2.
     * Only accepts a candidate if it strictly improves the score.
     */
    private suspend fun optimizeUtilitySlots(
        char: Character,
        monsterCode: String,
        gearOverrides: Map<String, String>,
        currentScore: GearScore
    ): List<UtilityEquipAction> {
        val utilityCandidates = getOwnedUtilityCandidates(char)
        if (utilityCandidates.isEmpty()) return emptyList()

        val results = mutableListOf<UtilityEquipAction>()
        var runningScore = currentScore
        val filledSlots = mutableMapOf<String, String>()

        for (slot in UTILITY_SLOT_NAMES) {
            val excludeCodes = filledSlots.values.toSet()
            var bestForSlot: Pair<Item, Int>? = null  // (item, quantity)
            var bestScoreForSlot = runningScore

            for (candidate in utilityCandidates.filter { it.code !in excludeCodes }) {
                val quantity = getOwnedQuantity(char, candidate.code).coerceAtMost(UTILITY_MAX_QUANTITY)
                if (quantity <= 0) continue

                val utilityOverrides = filledSlots + (slot to candidate.code)
                val utilityQuantities = utilityOverrides.mapValues { (_, code) ->
                    getOwnedQuantity(char, code).coerceAtMost(UTILITY_MAX_QUANTITY)
                }

                val simResult = try {
                    val r = helper.simulateFightWithSlotAndUtilityOverrides(
                        char, monsterCode, gearOverrides, utilityOverrides, utilityQuantities, GREEDY_API_ITERATIONS
                    )
                    simToScore(r, char)
                } catch (_: Exception) { continue }

                if (simResult > bestScoreForSlot) {
                    bestForSlot = candidate to quantity
                    bestScoreForSlot = simResult
                }
            }

            if (bestForSlot != null) {
                val (item, quantity) = bestForSlot
                val source = if (helper.getItemQuantity(char, item.code) >= quantity) "inventory" else "bank"
                results.add(UtilityEquipAction(slot, item.code, quantity, source))
                filledSlots[slot] = item.code
                runningScore = bestScoreForSlot
            }
        }
        return results
    }

    /**
     * Owned utility-slot-eligible potions usable by this character.
     *
     * Utility slots hold items of `type == "utility"` (subtype = "potion").
     * NOT `type == "consumable"` — that's food (used via /action/use).
     *
     * We additionally filter by [UTILITY_EFFECT_CODES] as a safety net so any misclassified
     * item without a real utility effect is excluded.
     */
    private suspend fun getOwnedUtilityCandidates(char: Character): List<Item> {
        val ownedCodes = (char.inventory.map { it.code } + helper.bankState.snapshot.value.keys +
            listOf(char.utility1Slot, char.utility2Slot))
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        return ownedCodes.mapNotNull { code ->
            try {
                val item = helper.contentCache.getItemOrNull(code) ?: return@mapNotNull null
                if (item.type != "utility") return@mapNotNull null
                if (item.level > char.level) return@mapNotNull null
                if (item.effects.none { it.code in UTILITY_EFFECT_CODES }) return@mapNotNull null
                item
            } catch (_: Exception) { null }
        }
    }

    private fun getOwnedQuantity(char: Character, code: String): Int =
        helper.getItemQuantity(char, code) + helper.bankState.getQuantity(code) +
            (if (char.utility1Slot == code) char.utility1SlotQuantity else 0) +
            (if (char.utility2Slot == code) char.utility2SlotQuantity else 0)

    private fun accessibleGearQuantities(char: Character): Map<String, Int> {
        val quantities = mutableMapOf<String, Int>()
        fun add(code: String, quantity: Int = 1) {
            if (code.isNotEmpty() && quantity > 0) {
                quantities[code] = quantities.getOrDefault(code, 0) + quantity
            }
        }
        char.inventory.forEach { add(it.code, it.quantity) }
        helper.bankState.snapshot.value.forEach { (code, quantity) -> add(code, quantity) }
        GEAR_SLOTS.forEach { add(helper.getEquippedInSlot(char, it.slot)) }
        return quantities
    }

    internal fun requiredItemCounts(
        gear: Map<String, String>,
        utilities: Map<String, Pair<String, Int>>
    ): Map<String, Int> {
        val required = mutableMapOf<String, Int>()
        gear.values.filter { it.isNotEmpty() }.forEach { code ->
            required[code] = required.getOrDefault(code, 0) + 1
        }
        utilities.values.forEach { (code, quantity) ->
            required[code] = required.getOrDefault(code, 0) + quantity
        }
        return required
    }

    internal fun requiredItemsAvailable(char: Character, requiredItems: Map<String, Int>): Boolean {
        val available = accessibleGearQuantities(char).toMutableMap()
        if (char.utility1Slot.isNotEmpty()) {
            available[char.utility1Slot] = available.getOrDefault(char.utility1Slot, 0) + char.utility1SlotQuantity
        }
        if (char.utility2Slot.isNotEmpty()) {
            available[char.utility2Slot] = available.getOrDefault(char.utility2Slot, 0) + char.utility2SlotQuantity
        }
        return requiredItems.all { (code, quantity) -> available.getOrDefault(code, 0) >= quantity }
    }

    private fun absoluteUtilityTarget(
        char: Character,
        utilityActions: List<UtilityEquipAction>
    ): Map<String, Pair<String, Int>> {
        val target = mutableMapOf<String, Pair<String, Int>>()
        if (char.utility1Slot.isNotEmpty()) target["utility1"] = char.utility1Slot to char.utility1SlotQuantity
        if (char.utility2Slot.isNotEmpty()) target["utility2"] = char.utility2Slot to char.utility2SlotQuantity
        utilityActions.forEach { target[it.slot] = it.itemCode to it.quantity }
        return target
    }

    private fun buildActionsForTarget(
        char: Character,
        gear: Map<String, String>,
        utilities: Map<String, KnownUtilityLoadout>
    ): Triple<List<ActionHelper.EquipAction>, List<UtilityEquipAction>, List<SlotChange>> {
        val inventoryRemaining = char.inventory
            .filter { it.quantity > 0 }
            .associate { it.code to it.quantity }
            .toMutableMap()
        val bankRemaining = helper.bankState.snapshot.value.toMutableMap()
        val equippedRemaining = GEAR_SLOTS
            .map { helper.getEquippedInSlot(char, it.slot) }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .toMutableMap()
        val gearActions = mutableListOf<ActionHelper.EquipAction>()
        val changes = mutableListOf<SlotChange>()

        for (slotInfo in GEAR_SLOTS) {
            val targetCode = gear[slotInfo.slot] ?: continue
            val currentCode = helper.getEquippedInSlot(char, slotInfo.slot)
            if (currentCode == targetCode) {
                equippedRemaining[targetCode] = equippedRemaining.getOrDefault(targetCode, 0) - 1
                continue
            }
            val source = when {
                inventoryRemaining.getOrDefault(targetCode, 0) > 0 -> {
                    inventoryRemaining[targetCode] = inventoryRemaining.getValue(targetCode) - 1
                    "inventory"
                }
                bankRemaining.getOrDefault(targetCode, 0) > 0 -> {
                    bankRemaining[targetCode] = bankRemaining.getValue(targetCode) - 1
                    "bank"
                }
                equippedRemaining.getOrDefault(targetCode, 0) > 0 -> {
                    equippedRemaining[targetCode] = equippedRemaining.getValue(targetCode) - 1
                    "equipped"
                }
                else -> continue
            }
            gearActions += ActionHelper.EquipAction(slotInfo.slot, targetCode, source)
            changes += SlotChange(slotInfo.slot, currentCode, targetCode, source)
        }

        val utilityActions = utilities.entries.sortedBy { it.key }.mapNotNull { (slot, utility) ->
            val currentCode = helper.getEquippedInSlot(char, slot)
            val currentQuantity = if (currentCode == utility.code) helper.getEquippedUtilityQuantity(char, slot) else 0
            if (currentCode == utility.code && currentQuantity >= utility.quantity) return@mapNotNull null
            val inventoryQuantity = helper.getItemQuantity(char, utility.code)
            val source = if (inventoryQuantity + currentQuantity >= utility.quantity) "inventory" else "bank"
            UtilityEquipAction(slot, utility.code, utility.quantity, source)
        }
        return Triple(gearActions, utilityActions, changes)
    }

    private fun currentLoadoutResult(char: Character, score: GearScore): OptimizationResult {
        val gear = GEAR_SLOTS.mapNotNull { slotInfo ->
            helper.getEquippedInSlot(char, slotInfo.slot).takeIf { it.isNotEmpty() }?.let { slotInfo.slot to it }
        }.toMap()
        return OptimizationResult(
            equipActions = emptyList(),
            utilityActions = emptyList(),
            baselineScore = score,
            optimizedScore = score,
            slotChanges = emptyList(),
            targetLoadout = gear,
            targetUtilities = absoluteUtilityTarget(char, emptyList())
        )
    }

    // ── Cache management ──────────────────────────────────────────

    /** Preserved for TaskMasterExecutor's per-character "last monster optimized" tracking. */
    fun markOptimized(characterName: String, monsterCode: String) {
        lastOptimizedFor[characterName] = monsterCode
    }

    fun getLastOptimizedMonster(characterName: String): String? = lastOptimizedFor[characterName]

    // ── Weapon-only heuristic (fast, no simulation) ───────────────

    /**
     * Fast heuristic weapon selection — no simulation calls. Used by the FightingExecutor's
     * runtime fallback when a Fight task is entered without a wizard-provided loadout.
     *
     * Candidates: all weapons the character owns (inventory, bank, or currently equipped)
     * within their level and NOT gathering tools.
     *
     * Scoring: expected damage after resistance across all 4 elements, scaled by the weapon's
     * global "dmg" bonus. Ties broken by item level descending.
     *
     * Returns:
     *  - null if the best weapon is already equipped OR if nothing better is available
     *  - EquipAction with empty itemCode if the character has NO combat weapons at all —
     *    caller should treat this as "unequip current weapon" so the character doesn't
     *    fight with a gathering tool
     */
    suspend fun findBestWeapon(char: Character, monsterCode: String): ActionHelper.EquipAction? {
        val allWeapons = try {
            helper.contentCache.getItemsByType("weapon").filter {
                it.level <= char.level && it.subtype != "tool"
            }
        } catch (_: Exception) { return null }

        val monster = try { helper.contentCache.getMonster(monsterCode) }
                      catch (_: Exception) { null }

        val inventoryCodes = char.inventory
            .filter { it.quantity > 0 }
            .associate { it.code to "inventory" }

        val bankCodes = helper.bankState.snapshot.value
            .filter { it.value > 0 }
            .mapValues { "bank" }

        val currentWeaponCode = char.weaponSlot.takeIf { it.isNotEmpty() }

        val ownedCombatCandidates = allWeapons.filter { weapon ->
            weapon.code in inventoryCodes ||
            weapon.code in bankCodes ||
            weapon.code == currentWeaponCode
        }

        if (ownedCombatCandidates.isEmpty()) {
            return if (currentWeaponCode != null) {
                ActionHelper.EquipAction(slot = "weapon", itemCode = "", source = "unequip")
            } else null
        }

        fun score(weapon: Item): Double {
            val effectMap = weapon.effects.associate { it.code to it.value }
            val globalDmgBonus = (effectMap["dmg"] ?: 0) / 100.0

            if (monster == null) {
                val totalAttack = listOf("attack_fire", "attack_earth", "attack_water", "attack_air")
                    .sumOf { effectMap[it] ?: 0 }
                return totalAttack * (1.0 + globalDmgBonus)
            }

            val elementMap = mapOf(
                "attack_fire"  to monster.resFire  / 100.0,
                "attack_earth" to monster.resEarth / 100.0,
                "attack_water" to monster.resWater / 100.0,
                "attack_air"   to monster.resAir   / 100.0
            )

            return elementMap.entries.sumOf { (attackKey, resRatio) ->
                val baseAttack = effectMap[attackKey] ?: 0
                val resMult = (1.0 - resRatio).coerceAtLeast(0.0)
                baseAttack * (1.0 + globalDmgBonus) * resMult
            }
        }

        val best = ownedCombatCandidates
            .sortedWith(compareByDescending<Item> { score(it) }.thenByDescending { it.level }.thenBy { it.code })
            .first()

        if (best.code == currentWeaponCode) return null

        val source = when {
            best.code in inventoryCodes -> "inventory"
            best.code in bankCodes      -> "bank"
            else                        -> return null
        }

        return ActionHelper.EquipAction(slot = "weapon", itemCode = best.code, source = source)
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * For artifact slots: exclude codes already picked in accumulated overrides OR
     * currently equipped in the other artifact slots. Artifacts must be unique across
     * the three slots per the game's constraint.
     * Non-artifact slots have no exclusion.
     */
    private fun getExcludedCodes(char: Character, currentSlot: String, accumulatedOverrides: Map<String, String>): Set<String> {
        if (currentSlot !in ARTIFACT_SLOTS) return emptySet()
        val otherArtifactSlots = ARTIFACT_SLOTS - currentSlot
        return otherArtifactSlots.mapNotNull { slot ->
            accumulatedOverrides[slot] ?: helper.getEquippedInSlot(char, slot).takeIf { it.isNotEmpty() }
        }.toSet()
    }

    private fun simToScore(sim: CombatSimulationData, char: Character): GearScore = GearScore(
        winRate = sim.winrate,
        avgWinTurns = avgWinTurns(sim),
        prospecting = char.prospecting,
        wisdom = char.wisdom
    )

    private fun avgWinTurns(sim: CombatSimulationData): Double =
        sim.results.filter { it.result == "win" }
            .map { it.turns }
            .let { if (it.isEmpty()) Double.MAX_VALUE else it.average() }

    private fun emptyResult(char: Character): OptimizationResult {
        val score = GearScore(0.0, Double.MAX_VALUE, char.prospecting, char.wisdom)
        return OptimizationResult(emptyList(), emptyList(), score, score, emptyList(), emptyMap(), emptyMap())
    }
}
