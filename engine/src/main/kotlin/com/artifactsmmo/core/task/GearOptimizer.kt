package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.CombatSimulationData
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Monster

/**
 * Unified weapon + gear + utility slot optimizer.
 *
 * Design principles:
 *  - Gear/weapon search uses a whole-loadout HEURISTIC ([evaluateLoadout]), not
 *    per-candidate simulation. Solo loadouts are ranked by expected seconds per kill
 *    ([cycleSeconds]: fight cooldown incl. haste + rest needed to recover lost HP), so
 *    damage, HP, resistance and haste are traded in one common currency.
 *  - Real API sim is used only for: the baseline score, one post-gear-pass score (feeds
 *    the utility pass), the utility slot greedy pass itself, and the final validation —
 *    never for per-candidate gear/weapon comparisons. This avoids depending on the local
 *    simulator's fidelity for the decision that matters most (which item to equip).
 *  - Weapon is optimized as part of the greedy pass (no separate weapon logic)
 *  - Artifact slots enforce uniqueness (game constraint: no duplicate artifacts)
 *  - Gear is evaluated with both utility slots empty. The utility pass runs only when the
 *    best gear-only loadout is below [NO_UTILITY_MIN_WINRATE], and stops adding potions as
 *    soon as that threshold is met. Potions that are not needed are unequipped.
 *  - Cache-and-verify: if a same-level character recently optimized against this monster,
 *    the cached loadout is verified with a single API sim before being reused
 *
 * Public entry points:
 *  - [optimizeWithCacheHint] — preferred; uses the session cache
 *  - [optimize] — full optimization pass, always executes
 *
 * Simulation calls are rate-limited (1/sec) by the client gateway.
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
        /** Alternative weapons used to anchor non-weapon shortlists (element synergy). */
        const val ANCHOR_WEAPONS = 2
        const val CANDIDATES_PER_ANCHOR = 4
        const val API_GEAR_FINALISTS = 3
        const val CACHE_VERIFY_MIN_WINRATE = 0.85
        const val UTILITY_MAX_QUANTITY = 100
        /**
         * Win rate at which a loadout is considered good enough WITHOUT utility potions.
         * Potions are consumed every fight, so they are only planned when gear alone falls
         * below this threshold, and are added only until the threshold is reached.
         */
        const val NO_UTILITY_MIN_WINRATE = 0.95
        /**
         * Heuristic HP-loss ceiling for a loadout to count as "safe" before simulation. Expected
         * values hide crit/variance, so a loadout predicted to end at 1 HP is not a real win.
         */
        const val SAFE_HP_LOSS_FRACTION = 0.8
        const val MAX_FIGHT_TURNS = 100.0
        /** Simulated per-kill time differences below this are noise. */
        const val CYCLE_SECONDS_TOLERANCE = 2.0
        const val OPTIMIZER_VERSION = "loadout-beam-v3"

        /** Utility overrides that simulate both utility slots as empty. */
        val NO_UTILITIES: Map<String, String> = UTILITY_SLOT_NAMES.associateWith { "" }

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

            // Whole-hit breakpoints and turn order: the monster only gets the hits that land
            // before our last one, and acts first when its initiative is higher (ties -> higher HP).
            val playerFirst = stats.initiative > monster.initiative ||
                (stats.initiative == monster.initiative && stats.maxHp >= monster.hp)
            val hitsToKill = kotlin.math.ceil(killTurns).coerceAtLeast(1.0)
            val monsterHits = if (playerFirst) hitsToKill - 1 else hitsToKill
            val turns = hitsToKill + monsterHits
            val hpLost = monsterHits * expectedIncoming
            val hpLostFraction = hpLost / stats.maxHp.coerceAtLeast(1)
            val safe = hpLostFraction <= SAFE_HP_LOSS_FRACTION && turns <= MAX_FIGHT_TURNS
            return LoadoutHeuristic(
                combatRatio = survivalTurns / killTurns.coerceAtLeast(1.0),
                expectedDamage = expectedDamage,
                effectiveHp = survivalTurns,
                initiativeAdvantage = stats.initiative - monster.initiative,
                utilityStats = stats.prospecting + stats.wisdom,
                safe = safe,
                hpLostFraction = hpLostFraction,
                cycleSeconds = cycleSeconds(turns, stats.haste, hpLostFraction)
            )
        }

        /**
         * Bank items [result] will withdraw for [char]: gear sourced from the bank plus the
         * part of each planned utility stack not already in the inventory or equipped.
         */
        fun bankDemand(char: Character, result: OptimizationResult): Map<String, Int> {
            val demand = mutableMapOf<String, Int>()
            result.equipActions.filter { it.source == "bank" }.forEach { demand.merge(it.itemCode, 1, Int::plus) }
            for (u in result.utilityActions) {
                if (u.quantity <= 0 || u.source != "bank") continue
                val held = char.inventory.filter { it.code == u.itemCode }.sumOf { it.quantity } +
                    (if (char.utility1Slot == u.itemCode) char.utility1SlotQuantity else 0) +
                    (if (char.utility2Slot == u.itemCode) char.utility2SlotQuantity else 0)
                val need = u.quantity - held
                if (need > 0) demand.merge(u.itemCode, need, Int::plus)
            }
            return demand
        }

        /**
         * Seconds one kill costs: the fight cooldown (`turns × 2`, reduced 1% per haste point,
         * min 5s) plus recovering the HP it cost (rest = 1s per 1% HP missing, min 3s). Food
         * recovers faster in wall time but costs items in proportion to the same HP, so this
         * is the common currency for "kill fast" vs "take less damage" vs haste.
         */
        fun cycleSeconds(turns: Double, haste: Int, hpLostFraction: Double): Double {
            val fight = (turns * 2.0 * (1.0 - haste.coerceIn(0, 100) / 100.0)).coerceAtLeast(5.0)
            val lostPercent = (hpLostFraction * 100.0).coerceAtLeast(0.0)
            val recovery = if (lostPercent <= 0.0) 0.0 else kotlin.math.ceil(lostPercent).coerceAtLeast(3.0)
            return fight + recovery
        }
    }

    /** Records which monster each character was most recently optimized for. */
    private val lastOptimizedFor = mutableMapOf<String, String>()

    data class GearScore(
        val winRate: Double,
        val avgWinTurns: Double,
        val prospecting: Int,
        val wisdom: Int,
        /** Expected seconds per kill (fight cooldown + HP recovery); see [cycleSeconds]. */
        val cycleSeconds: Double = Double.MAX_VALUE
    ) : Comparable<GearScore> {
        override fun compareTo(other: GearScore): Int {
            // Primary: win rate descending (1% minimum meaningful difference)
            if (this.winRate > other.winRate + 0.01) return 1
            if (this.winRate < other.winRate - 0.01) return -1
            // Secondary: seconds per kill ascending — fight cooldown (turns, haste) plus the
            // rest/food needed to recover the HP the fight cost.
            if (this.cycleSeconds < other.cycleSeconds - CYCLE_SECONDS_TOLERANCE) return 1
            if (this.cycleSeconds > other.cycleSeconds + CYCLE_SECONDS_TOLERANCE) return -1
            // Tertiary: prospecting + wisdom descending
            return (this.prospecting + this.wisdom).compareTo(other.prospecting + other.wisdom)
        }
    }

    internal data class LoadoutHeuristic(
        val combatRatio: Double,
        val expectedDamage: Double,
        val effectiveHp: Double,
        val initiativeAdvantage: Int,
        val utilityStats: Int,
        /** Predicted to win with HP to spare (see [SAFE_HP_LOSS_FRACTION]). */
        val safe: Boolean = true,
        val hpLostFraction: Double = 0.0,
        val cycleSeconds: Double = 0.0
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
    suspend fun optimizeWithCacheHint(char: Character, monsterCode: String, bank: Map<String, Int>? = null): OptimizationResult {
        val cached = knownLoadoutStore.find(char.name, char.level, monsterCode, OPTIMIZER_VERSION)
        if (cached != null && requiredItemsAvailable(char, cached.requiredItems, bank)) {
            val plan = buildActionsForTarget(char, cached.gear, cached.utilities, bank)
            val verifySim = try {
                helper.simulateFightWithSlotAndUtilityOverrides(
                    char,
                    monsterCode,
                    cached.gear,
                    NO_UTILITIES + cached.utilities.mapValues { it.value.code },
                    cached.utilities.mapValues { it.value.quantity },
                    API_SIM_ITERATIONS
                )
            } catch (e: Exception) {
                println("[${char.name}] GearOptimizer: known-loadout verification failed: ${e.message}")
                null
            }
            if (verifySim != null && verifySim.winrate >= CACHE_VERIFY_MIN_WINRATE) {
                println("[${char.name}] GearOptimizer: reusing known-good loadout for $monsterCode (${(verifySim.winrate * 100).toInt()}%)")
                val monster = try { helper.contentCache.getMonster(monsterCode) } catch (_: Exception) { null }
                val score = simToScore(verifySim, char, monster)
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

        val result = optimize(char, monsterCode, bank)
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
     *
     * [bank] is the bank stock this plan may draw from; null = the bank minus other
     * characters' reservations ([BankState.availableFor]).
     */
    suspend fun optimize(char: Character, monsterCode: String, bank: Map<String, Int>? = null): OptimizationResult {
        val monster = try { helper.contentCache.getMonster(monsterCode) }
                      catch (_: Exception) { return emptyResult(char) }

        // Baseline — always API sim for an accurate starting score (as currently equipped,
        // including any utility potions)
        val baseSim = try {
            helper.simulateFightWithSlotOverrides(char, monsterCode, emptyMap(), API_SIM_ITERATIONS)
        } catch (e: Exception) {
            println("[${char.name}] GearOptimizer: baseline sim failed: ${e.message}")
            return emptyResult(char)
        }
        val baselineScore = simToScore(baseSim, char, monster)

        // Gear is compared with utility slots empty so equipped potions never mask a
        // loadout that can win on its own.
        val hasEquippedUtilities = char.utility1Slot.isNotEmpty() || char.utility2Slot.isNotEmpty()
        val gearOnlyBaselineScore = if (!hasEquippedUtilities) baselineScore else try {
            simToScore(simulateGearOnly(char, monsterCode, emptyMap(), API_SIM_ITERATIONS), char, monster)
        } catch (e: Exception) {
            println("[${char.name}] GearOptimizer: gear-only baseline sim failed: ${e.message}")
            return currentLoadoutResult(char, baselineScore)
        }

        // ── Bounded whole-loadout beam search ──
        // Search uses complete aggregate character stats so weapon attack, elemental damage,
        // critical strike, HP, resistance, and initiative interactions are evaluated together.
        // The current loadout is always the incumbent and remains selected unless a finalist
        // demonstrates a real API-sim improvement.
        val currentTarget = currentGearTarget(char)
        var selectedTarget = currentTarget
        var selectedStats = char
        var postGearScore = gearOnlyBaselineScore

        // Finalists: the best predicted seconds-per-kill loadouts, plus the strongest raw
        // combat ratio as a hedge in case the heuristic's time estimate is off.
        val changedStates = searchGearLoadouts(char, monster, bank)
            .filter { state -> state.loadout.any { (slot, item) -> currentTarget[slot] != item.code } }
        val finalists = (changedStates.take(API_GEAR_FINALISTS) +
            listOfNotNull(changedStates.maxByOrNull { it.heuristic.combatRatio }))
            .distinctBy { it.signature }

        for (finalist in finalists) {
            val target = finalist.loadout.mapValues { it.value.code }
            val simulated = try {
                simulateGearOnly(char, monsterCode, target, API_SIM_ITERATIONS)
            } catch (e: Exception) {
                println("[${char.name}] GearOptimizer: beam finalist validation failed: ${e.message}")
                continue
            }
            val score = simToScore(simulated, finalist.stats, monster)
            if (score > postGearScore) {
                selectedTarget = target
                selectedStats = finalist.stats
                postGearScore = score
            }
        }

        val accumulatedOverrides = selectedTarget.toMutableMap()
        var validationSucceeded = true

        // ── Utility slot pass — only when gear alone can't win reliably ──
        val utilityActions = if (postGearScore.winRate < NO_UTILITY_MIN_WINRATE) {
            optimizeUtilitySlots(char, selectedStats, monster, accumulatedOverrides, postGearScore, bank)
        } else {
            emptyList()
        }

        // ── Final API sim validation with the full loadout ──
        val optimizedScore = if (utilityActions.isEmpty()) {
            postGearScore
        } else {
            try {
                val utilityOverrides = NO_UTILITIES + utilityActions.associate { it.slot to it.itemCode }
                val utilityQuantities = utilityActions.associate { it.slot to it.quantity }
                val finalSim = helper.simulateFightWithSlotAndUtilityOverrides(
                    char, monsterCode, accumulatedOverrides, utilityOverrides, utilityQuantities, API_SIM_ITERATIONS
                )
                simToScore(finalSim, selectedStats, monster)
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
        val targetUtilities = utilityActions.associate { it.slot to (it.itemCode to it.quantity) }

        // A loadout that meets the win-rate floor is accepted even if the current loadout
        // (e.g. with potions) scores slightly better — saving potions is the point.
        val acceptable = optimizedScore >= baselineScore || optimizedScore.winRate >= NO_UTILITY_MIN_WINRATE
        if (!validationSucceeded || !acceptable) {
            println("[${char.name}] GearOptimizer: proposed loadout did not validate above baseline — keeping current loadout")
            return currentLoadoutResult(char, baselineScore)
        }

        val actions = buildActionsForTarget(
            char,
            targetLoadout,
            targetUtilities.mapValues { KnownUtilityLoadout(it.value.first, it.value.second) },
            bank
        )
        if (utilityActions.isEmpty()) {
            println("[${char.name}] GearOptimizer: no utility potions needed for $monsterCode (${(optimizedScore.winRate * 100).toInt()}% without)")
        }

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

    /**
     * Solo ranking: loadouts predicted to win with HP to spare first, then fewest expected
     * seconds per kill (fight cooldown + recovery), then raw combat ratio for unsafe ones.
     */
    private val beamStateComparator =
        compareByDescending<BeamState> { it.heuristic.safe }
            .thenBy { if (it.heuristic.safe) it.heuristic.cycleSeconds else 0.0 }
            .thenByDescending { it.heuristic.combatRatio }
            .thenByDescending { it.heuristic.expectedDamage }
            .thenByDescending { it.heuristic.utilityStats }
            .thenBy { it.changedSlots }
            .thenBy { it.signature }

    private suspend fun searchGearLoadouts(char: Character, monster: Monster, bank: Map<String, Int>? = null): List<BeamState> {
        val currentItems = mutableMapOf<String, Item>()
        for (slotInfo in GEAR_SLOTS) {
            val code = helper.getEquippedInSlot(char, slotInfo.slot)
            if (code.isEmpty()) continue
            try {
                helper.contentCache.getItemOrNull(code)?.let { currentItems[slotInfo.slot] = it }
            } catch (_: Exception) {}
        }

        val availableQuantities = accessibleGearQuantities(char, bank)
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

    /** One character's gear pick for a boss/raid team slot, produced by [searchCoopLoadout]. */
    data class CoopLoadoutResult(
        val targetLoadout: Map<String, String>,
        val expectedDamage: Double,
        val effectiveHp: Double,
    )

    /**
     * Whole-loadout beam search for a boss/raid team member, combining offense/defense the
     * same way [searchGearLoadouts] does for solo fights (so e.g. a fire-damage weapon pairs
     * with fire-damage armor instead of scoring each item in isolation) — see
     * [LoadoutHeuristic]/[evaluateLoadout], which multiplies attack by the matching element's
     * damage bonus and nets resistance against the boss's own attack elements.
     *
     * Ranking differs by [role]:
     *  - SUPPORT: highest expected damage wins; effective HP only breaks ties.
     *  - TANK: loadouts that would die before the boss goes down are ruled out first — the
     *    tank is attacked ~90% of the time, so pure damage risks a wipe. The survival bar
     *    assumes all [partySize] members contribute comparable damage (monster HP /
     *    (this loadout's damage × partySize)); loadouts clearing it are then ranked by damage, then
     *    effective HP. If none clear it, the highest effective HP is used instead.
     *
     * [claimedGear] is a shared, mutated-by-reference map of item code -> copies already
     * committed to earlier team members (tank first) — this call subtracts what's already
     * claimed from the owned pool and does NOT commit its own picks (the caller does, once
     * it settles on a target loadout, mirroring [pickBestGearHeuristic]'s claim tracking).
     * [excludedBankCodes]/[allowBank] mirror the same knobs on [pickBestGearHeuristic].
     */
    suspend fun searchCoopLoadout(
        char: Character,
        monster: Monster,
        role: CoopOptimizer.Role,
        claimedGear: Map<String, Int> = emptyMap(),
        excludedBankCodes: Set<String> = emptySet(),
        allowBank: Boolean = true,
        partySize: Int = 3,
    ): CoopLoadoutResult {
        val currentItems = mutableMapOf<String, Item>()
        for (slotInfo in GEAR_SLOTS) {
            val code = helper.getEquippedInSlot(char, slotInfo.slot)
            if (code.isEmpty()) continue
            try { helper.contentCache.getItemOrNull(code)?.let { currentItems[slotInfo.slot] = it } } catch (_: Exception) {}
        }

        val availableQuantities = accessibleGearQuantities(char).mapValues { (code, qty) ->
            (qty - (claimedGear[code] ?: 0)).coerceAtLeast(0)
        }
        val candidatesBySlot = buildCoopBeamCandidates(char, monster, currentItems, excludedBankCodes, allowBank, role)
        val initial = makeBeamState(
            char = char, monster = monster, loadout = currentItems,
            usedQuantities = currentItems.values.groupingBy { it.code }.eachCount(),
            stats = char, currentLoadout = currentItems
        )
        var beam = listOf(initial)
        val comparator = coopBeamComparator(role, monster.hp.toDouble(), partySize)

        for (slotInfo in GEAR_SLOTS) {
            if (slotInfo.slot == "rune") continue  // rune is picked separately, by role, in CoopOptimizer
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
                    val stats = if (oldItem?.code == candidate.code) state.stats else state.stats.applyItemDelta(oldItem, candidate)
                    expanded += makeBeamState(char, monster, loadout, counts, stats, currentItems)
                }
            }
            beam = expanded.sortedWith(comparator).distinctBy { canonicalLoadoutKey(it.loadout) }.take(BEAM_WIDTH)
            if (beam.isEmpty()) { beam = listOf(initial); break }
        }
        val best = beam.sortedWith(comparator).firstOrNull() ?: initial
        return CoopLoadoutResult(
            targetLoadout = GEAR_SLOTS.filter { it.slot != "rune" }.mapNotNull { s -> best.loadout[s.slot]?.code?.let { s.slot to it } }.toMap(),
            expectedDamage = best.heuristic.expectedDamage,
            effectiveHp = best.heuristic.effectiveHp,
        )
    }

    /** [role]-specific ordering over beam states — see [searchCoopLoadout] kdoc. */
    private fun coopBeamComparator(role: CoopOptimizer.Role, monsterHp: Double, partySize: Int): Comparator<BeamState> = when (role) {
        CoopOptimizer.Role.SUPPORT -> compareByDescending<BeamState> { it.heuristic.expectedDamage }
            .thenByDescending { it.heuristic.effectiveHp }
            .thenBy { it.changedSlots }.thenBy { it.signature }
        CoopOptimizer.Role.TANK -> compareByDescending<BeamState> { survivesTeamFight(it.heuristic, monsterHp, partySize) }
            .thenByDescending { it.heuristic.expectedDamage }
            .thenByDescending { it.heuristic.effectiveHp }
            .thenBy { it.changedSlots }.thenBy { it.signature }
    }

    /**
     * True if this loadout's effective HP (survival turns) covers the fight's expected
     * length, assuming each of the [partySize] members contributes comparable damage
     * (monsterHp / (expectedDamage × partySize)). A heuristic floor, not a guarantee —
     * the tank is targeted ~90% of the time per [artifacts-docs], not always.
     */
    internal fun survivesTeamFight(heuristic: LoadoutHeuristic, monsterHp: Double, partySize: Int): Boolean {
        val teamKillTurns = monsterHp / (heuristic.expectedDamage.coerceAtLeast(1.0) * partySize.coerceAtLeast(1))
        return heuristic.effectiveHp >= teamKillTurns
    }

    /** Like [buildBeamCandidates], but respects cross-character bank claims (boss/raid teams). */
    private suspend fun buildCoopBeamCandidates(
        char: Character,
        monster: Monster,
        currentLoadout: Map<String, Item>,
        excludedBankCodes: Set<String>,
        allowBank: Boolean,
        role: CoopOptimizer.Role,
    ): Map<String, List<Item>> {
        // Tanks shortlist survivability first; supports shortlist damage first.
        val ranking = when (role) {
            CoopOptimizer.Role.TANK -> compareByDescending<LoadoutHeuristic> { it.effectiveHp }
                .thenByDescending { it.expectedDamage }
            CoopOptimizer.Role.SUPPORT -> compareByDescending<LoadoutHeuristic> { it.expectedDamage }
                .thenByDescending { it.effectiveHp }
        }
        return rankedSlotCandidates(char, monster, currentLoadout, GEAR_SLOTS.filter { it.slot != "rune" }, ranking) {
            it.source == "inventory" || (it.source == "bank" && allowBank && it.item.code !in excludedBankCodes)
        }
    }

    private suspend fun buildBeamCandidates(
        char: Character,
        monster: Monster,
        currentLoadout: Map<String, Item>
    ): Map<String, List<Item>> {
        val ranking = compareByDescending<LoadoutHeuristic> { it.safe }
            .thenBy { if (it.safe) it.cycleSeconds else 0.0 }
            .thenByDescending { it.combatRatio }
            .thenByDescending { it.expectedDamage }
        return rankedSlotCandidates(char, monster, currentLoadout, GEAR_SLOTS, ranking) {
            it.source == "inventory" || it.source == "bank"
        }
    }

    /**
     * Per-slot shortlists for the beam search, ranked by single-item swaps scored with
     * [ranking]. Non-weapon slots are ranked against the current weapon AND the top
     * [ANCHOR_WEAPONS] alternative weapons, and the shortlists are unioned — otherwise
     * e.g. water-damage armor would never be shortlisted while a fire weapon is equipped,
     * and the beam could never reach a strong all-water loadout.
     */
    private suspend fun rankedSlotCandidates(
        char: Character,
        monster: Monster,
        currentLoadout: Map<String, Item>,
        slots: List<ActionHelper.SlotInfo>,
        ranking: Comparator<LoadoutHeuristic>,
        sourceFilter: (ActionHelper.EquipmentOption) -> Boolean,
    ): Map<String, List<Item>> {
        val discoveredBySlot = slots.associate { slotInfo ->
            val discovered = try {
                helper.getAvailableEquipmentForSlot(char, slotInfo)
                    .filter(sourceFilter)
                    .filter { slotInfo.slot != "weapon" || it.item.subtype != "tool" }
                    .map { it.item }
            } catch (_: Exception) { emptyList() }
            slotInfo.slot to (discovered + listOfNotNull(currentLoadout[slotInfo.slot])).distinctBy { it.code }
        }

        fun rank(base: Character, slot: String, current: Item?, pool: List<Item>): List<Item> =
            pool.map { item ->
                val stats = if (current?.code == item.code) base else base.applyItemDelta(current, item)
                item to evaluateLoadout(stats, monster)
            }.sortedWith(
                compareBy<Pair<Item, LoadoutHeuristic>, LoadoutHeuristic>(ranking) { it.second }
                    .thenByDescending { it.first.level }
                    .thenBy { it.first.code }
            ).map { it.first }

        val currentWeapon = currentLoadout["weapon"]
        val rankedWeapons = discoveredBySlot["weapon"]?.let { rank(char, "weapon", currentWeapon, it) }.orEmpty()
        val anchors = listOf(char) + rankedWeapons
            .filter { it.code != currentWeapon?.code }
            .take(ANCHOR_WEAPONS)
            .map { char.applyItemDelta(currentWeapon, it) }

        return slots.associate { slotInfo ->
            val current = currentLoadout[slotInfo.slot]
            val pool = discoveredBySlot[slotInfo.slot].orEmpty()
            val shortlist = if (slotInfo.slot == "weapon") {
                rankedWeapons.take(CANDIDATES_PER_SLOT)
            } else {
                val perAnchor = anchors.map { rank(it, slotInfo.slot, current, pool) }
                // Primary anchor keeps its full shortlist; alternative anchors contribute their top picks.
                (perAnchor.first().take(CANDIDATES_PER_SLOT) +
                    perAnchor.drop(1).flatMap { it.take(CANDIDATES_PER_ANCHOR) }).distinctBy { it.code }
            }.toMutableList()
            if (current != null && shortlist.none { it.code == current.code }) shortlist += current
            slotInfo.slot to shortlist.distinctBy { it.code }
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

    // ── Utility optimization ──────────────────────────────────────

    /**
     * Try adding owned potions to utility1 and utility2, starting from empty utility slots.
     * Only accepts a candidate if it strictly improves the score, and stops once the
     * [NO_UTILITY_MIN_WINRATE] floor is reached so no surplus potion is consumed.
     */
    private suspend fun optimizeUtilitySlots(
        char: Character,
        gearStats: Character,
        monster: Monster,
        gearOverrides: Map<String, String>,
        currentScore: GearScore,
        bank: Map<String, Int>? = null,
    ): List<UtilityEquipAction> {
        val monsterCode = monster.code
        val utilityCandidates = getOwnedUtilityCandidates(char, bank)
        if (utilityCandidates.isEmpty()) return emptyList()

        val results = mutableListOf<UtilityEquipAction>()
        var runningScore = currentScore
        val filledSlots = mutableMapOf<String, String>()

        for (slot in UTILITY_SLOT_NAMES) {
            if (runningScore.winRate >= NO_UTILITY_MIN_WINRATE) break
            val excludeCodes = filledSlots.values.toSet()
            var bestForSlot: Pair<Item, Int>? = null  // (item, quantity)
            var bestScoreForSlot = runningScore

            for (candidate in utilityCandidates.filter { it.code !in excludeCodes }) {
                val quantity = getOwnedQuantity(char, candidate.code, bank).coerceAtMost(UTILITY_MAX_QUANTITY)
                if (quantity <= 0) continue

                val utilityOverrides = NO_UTILITIES + filledSlots + (slot to candidate.code)
                val utilityQuantities = utilityOverrides.filterValues { it.isNotEmpty() }.mapValues { (_, code) ->
                    getOwnedQuantity(char, code, bank).coerceAtMost(UTILITY_MAX_QUANTITY)
                }

                val simResult = try {
                    val r = helper.simulateFightWithSlotAndUtilityOverrides(
                        char, monsterCode, gearOverrides, utilityOverrides, utilityQuantities, GREEDY_API_ITERATIONS
                    )
                    simToScore(r, gearStats, monster)
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

    private suspend fun simulateGearOnly(
        char: Character,
        monsterCode: String,
        gearOverrides: Map<String, String>,
        iterations: Int
    ): CombatSimulationData = helper.simulateFightWithSlotAndUtilityOverrides(
        char, monsterCode, gearOverrides, NO_UTILITIES, emptyMap(), iterations
    )

    /**
     * Owned utility-slot-eligible potions usable by this character.
     *
     * Utility slots hold items of `type == "utility"` (subtype = "potion").
     * NOT `type == "consumable"` — that's food (used via /action/use).
     *
     * We additionally filter by [UTILITY_EFFECT_CODES] as a safety net so any misclassified
     * item without a real utility effect is excluded.
     */
    private suspend fun getOwnedUtilityCandidates(char: Character, bank: Map<String, Int>? = null): List<Item> {
        val ownedCodes = (char.inventory.map { it.code } + bankView(char, bank).keys +
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

    /** Bank stock a plan for [char] may use: [bank] if given, else the reservation-aware view. */
    private fun bankView(char: Character, bank: Map<String, Int>?): Map<String, Int> =
        bank ?: helper.bankState.availableFor(char.name)

    private fun getOwnedQuantity(char: Character, code: String, bank: Map<String, Int>? = null): Int =
        helper.getItemQuantity(char, code) + (bankView(char, bank)[code] ?: 0) +
            (if (char.utility1Slot == code) char.utility1SlotQuantity else 0) +
            (if (char.utility2Slot == code) char.utility2SlotQuantity else 0)

    private fun accessibleGearQuantities(char: Character, bank: Map<String, Int>? = null): Map<String, Int> {
        val quantities = mutableMapOf<String, Int>()
        fun add(code: String, quantity: Int = 1) {
            if (code.isNotEmpty() && quantity > 0) {
                quantities[code] = quantities.getOrDefault(code, 0) + quantity
            }
        }
        char.inventory.forEach { add(it.code, it.quantity) }
        bankView(char, bank).forEach { (code, quantity) -> add(code, quantity) }
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

    internal fun requiredItemsAvailable(char: Character, requiredItems: Map<String, Int>, bank: Map<String, Int>? = null): Boolean {
        val available = accessibleGearQuantities(char, bank).toMutableMap()
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
        utilities: Map<String, KnownUtilityLoadout>,
        bank: Map<String, Int>? = null,
    ): Triple<List<ActionHelper.EquipAction>, List<UtilityEquipAction>, List<SlotChange>> {
        val inventoryRemaining = char.inventory
            .filter { it.quantity > 0 }
            .associate { it.code to it.quantity }
            .toMutableMap()
        val bankRemaining = bankView(char, bank).toMutableMap()
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
        // Unneeded potions would still be consumed in fights — unequip them (quantity 0).
        val clearActions = UTILITY_SLOT_NAMES.filter { it !in utilities }.mapNotNull { slot ->
            val currentCode = helper.getEquippedInSlot(char, slot)
            if (currentCode.isEmpty()) null else UtilityEquipAction(slot, currentCode, 0, "equipped")
        }
        return Triple(gearActions, clearActions + utilityActions, changes)
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

    /**
     * [stats] are the aggregate stats of the simulated loadout (haste, max HP). HP lost per
     * kill comes from the sim's `final_hp` when the API reports it, else from the
     * [evaluateLoadout] estimate against [monster].
     */
    private fun simToScore(sim: CombatSimulationData, stats: Character, monster: Monster?): GearScore {
        val turns = avgWinTurns(sim)
        val maxHp = stats.maxHp.coerceAtLeast(1)
        val simulatedLoss = sim.results.filter { it.result == "win" }
            .mapNotNull { r -> r.finalHp()?.let { (maxHp - it).coerceIn(0, maxHp).toDouble() / maxHp } }
            .takeIf { it.isNotEmpty() }?.average()
        val lossFraction = simulatedLoss
            ?: monster?.let { evaluateLoadout(stats, it).hpLostFraction.coerceIn(0.0, 1.0) }
            ?: 0.0
        return GearScore(
            winRate = sim.winrate,
            avgWinTurns = turns,
            prospecting = stats.prospecting,
            wisdom = stats.wisdom,
            cycleSeconds = if (turns == Double.MAX_VALUE) Double.MAX_VALUE
                           else cycleSeconds(turns, stats.haste, lossFraction)
        )
    }

    private fun avgWinTurns(sim: CombatSimulationData): Double =
        sim.results.filter { it.result == "win" }
            .map { it.turns }
            .let { if (it.isEmpty()) Double.MAX_VALUE else it.average() }

    private fun emptyResult(char: Character): OptimizationResult {
        val score = GearScore(0.0, Double.MAX_VALUE, char.prospecting, char.wisdom)
        return OptimizationResult(emptyList(), emptyList(), score, score, emptyList(), emptyMap(), emptyMap())
    }
}
