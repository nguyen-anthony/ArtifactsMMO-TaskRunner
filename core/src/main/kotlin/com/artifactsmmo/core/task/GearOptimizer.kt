package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.CombatSimulationData
import com.artifactsmmo.client.models.Item

/**
 * Unified weapon + gear + utility slot optimizer.
 *
 * Design principles:
 *  - Local sim by default for the greedy per-slot pass (fast, free, no rate limit)
 *  - API sim for greedy when monster has effects (accurate for complex combats)
 *  - Single API sim at the end validates the final loadout
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
class GearOptimizer(private val helper: ActionHelper) {

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
        const val GREEDY_LOCAL_ITERATIONS = 50
        const val GREEDY_API_ITERATIONS = 50
        const val CACHE_MAX_ENTRIES = 20
        const val CACHE_VERIFY_MIN_WINRATE = 0.85
        const val UTILITY_MAX_QUANTITY = 100

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
    }

    /** Records which monster each character was most recently optimized for. */
    private val lastOptimizedFor = mutableMapOf<String, String>()

    /**
     * Session-only cache of optimization results keyed by (monster, gear level bucket).
     * LinkedHashMap with access-order = true → LRU eviction when capacity exceeded.
     */
    private data class OptimizationCacheKey(
        val monsterCode: String,
        val gearLevelBucket: Int
    )

    private val optimizationCache: LinkedHashMap<OptimizationCacheKey, OptimizationResult> =
        object : LinkedHashMap<OptimizationCacheKey, OptimizationResult>(CACHE_MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<OptimizationCacheKey, OptimizationResult>): Boolean =
                size > CACHE_MAX_ENTRIES
        }

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
        val targetLoadout: Map<String, String> = emptyMap()
    )

    // ── Public API ──────────────────────────────────────────────────

    /**
     * Optimize with a session cache. Reuses cached results when a same-level-bucket
     * character recently optimized against this same monster, and the required items
     * are still available. Falls back to full [optimize] on cache miss or verify failure.
     *
     * The cache stores the full target loadout (see [OptimizationResult.targetLoadout]) so
     * that a second character starting from a different baseline (e.g. holding a gathering
     * tool) computes the correct delta from THEIR own currently-equipped gear rather than
     * inheriting the first character's delta.
     */
    suspend fun optimizeWithCacheHint(char: Character, monsterCode: String): OptimizationResult {
        val key = OptimizationCacheKey(monsterCode, char.level / 5)
        val cached = synchronized(optimizationCache) { optimizationCache[key] }

        if (cached != null && cached.targetLoadout.isNotEmpty()) {
            // Verify all TARGET items are available for THIS character (own or in bank).
            // We check ownership regardless of source since char2 may hold the item in a
            // different location than char1 did.
            val targetItemsAvailable = cached.targetLoadout.values.all { code ->
                helper.getItemQuantity(char, code) >= 1 || helper.bankState.getQuantity(code) >= 1
            }
            val utilityAvailable = cached.utilityActions.all { util ->
                val owned = helper.getItemQuantity(char, util.itemCode) + helper.bankState.getQuantity(util.itemCode)
                owned >= 1  // any amount is enough — quantity is scaled per-character below
            }

            if (targetItemsAvailable && utilityAvailable) {
                // Compute per-character EquipActions by comparing the target loadout to
                // what THIS character has equipped right now.
                val perCharEquipActions = mutableListOf<ActionHelper.EquipAction>()
                val perCharSlotChanges = mutableListOf<SlotChange>()
                for ((slot, targetCode) in cached.targetLoadout) {
                    val currentCode = helper.getEquippedInSlot(char, slot)
                    if (currentCode == targetCode) continue
                    val source = when {
                        helper.getItemQuantity(char, targetCode) >= 1 -> "inventory"
                        helper.bankState.getQuantity(targetCode) >= 1 -> "bank"
                        else -> continue  // should not happen — we verified availability above
                    }
                    perCharEquipActions.add(ActionHelper.EquipAction(slot = slot, itemCode = targetCode, source = source))
                    perCharSlotChanges.add(SlotChange(slot = slot, fromItemCode = currentCode, toItemCode = targetCode, source = source))
                }

                // Rebuild utility actions per-character with THIS character's owned quantity.
                val perCharUtilityActions = cached.utilityActions.mapNotNull { util ->
                    val owned = helper.getItemQuantity(char, util.itemCode) + helper.bankState.getQuantity(util.itemCode)
                    val qty = owned.coerceAtMost(UTILITY_MAX_QUANTITY)
                    if (qty <= 0) return@mapNotNull null
                    val source = if (helper.getItemQuantity(char, util.itemCode) >= qty) "inventory" else "bank"
                    UtilityEquipAction(util.slot, util.itemCode, qty, source)
                }

                // Verify with a single API sim using the target loadout and this character's
                // stats. If win rate holds, return a per-character result with the freshly
                // computed delta.
                val equipOverrides = cached.targetLoadout
                val utilityOverrides = perCharUtilityActions.associate { it.slot to it.itemCode }
                val utilityQuantities = perCharUtilityActions.associate { it.slot to it.quantity }
                val verifySim = try {
                    if (utilityOverrides.isEmpty()) {
                        helper.simulateFightWithSlotOverrides(char, monsterCode, equipOverrides, API_SIM_ITERATIONS)
                    } else {
                        helper.simulateFightWithSlotAndUtilityOverrides(
                            char, monsterCode, equipOverrides, utilityOverrides, utilityQuantities, API_SIM_ITERATIONS
                        )
                    }
                } catch (e: Exception) {
                    println("[${char.name}] GearOptimizer: cache verify sim failed: ${e.message}")
                    null
                }

                if (verifySim != null && verifySim.winrate >= CACHE_VERIFY_MIN_WINRATE) {
                    println("[${char.name}] GearOptimizer: cache hit for $monsterCode — win rate ${(verifySim.winrate * 100).toInt()}% — ${perCharEquipActions.size} equip swap(s), ${perCharUtilityActions.size} utility swap(s)")
                    return OptimizationResult(
                        equipActions = perCharEquipActions,
                        utilityActions = perCharUtilityActions,
                        baselineScore = cached.baselineScore,
                        optimizedScore = simToScore(verifySim, char),
                        slotChanges = perCharSlotChanges,
                        targetLoadout = cached.targetLoadout
                    )
                }
                println("[${char.name}] GearOptimizer: cache verify failed (win rate ${verifySim?.winrate ?: "null"}) — running full optimization")
            } else {
                println("[${char.name}] GearOptimizer: cache items not fully available — running full optimization")
            }
        }

        // Cache miss — full optimization
        val result = optimize(char, monsterCode)
        if (result.targetLoadout.isNotEmpty()) {
            synchronized(optimizationCache) { optimizationCache[key] = result }
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

        val accumulatedOverrides = mutableMapOf<String, String>()
        val slotChanges = mutableListOf<SlotChange>()
        var bestScore = baselineScore

        // ── Greedy per-slot pass ──
        for (slotInfo in GEAR_SLOTS) {
            val excludedCodes = getExcludedCodes(char, slotInfo.slot, accumulatedOverrides)
            val candidates = try {
                helper.getAvailableEquipmentForSlot(char, slotInfo)
                    .filter { it.source == "inventory" || it.source == "bank" }
                    .filter { it.item.code !in excludedCodes }
                    .filter { slotInfo.slot != "weapon" || it.item.subtype != "tool" }
            } catch (_: Exception) { continue }

            if (candidates.isEmpty()) continue

            var bestCandidate: ActionHelper.EquipmentOption? = null

            for (candidate in candidates) {
                val testOverrides = accumulatedOverrides + (slotInfo.slot to candidate.item.code)

                val candidateScore = try {
                    if (hasEffects) {
                        // API sim needed to account for monster effects (poison, burn, etc.)
                        val r = helper.simulateFightWithSlotOverrides(char, monsterCode, testOverrides, GREEDY_API_ITERATIONS)
                        GearScore(
                            winRate = r.winrate,
                            avgWinTurns = avgWinTurns(r),
                            prospecting = char.prospecting + prospectingDelta(candidate.item),
                            wisdom = char.wisdom + wisdomDelta(candidate.item)
                        )
                    } else {
                        // Local sim is accurate for effect-free monsters — no API call, no rate limit
                        val r = helper.simulateLocalWithOverrides(char, monster, testOverrides, GREEDY_LOCAL_ITERATIONS)
                        GearScore(
                            winRate = r.winRate,
                            avgWinTurns = Double.MAX_VALUE,  // local sim doesn't track turns
                            prospecting = char.prospecting + prospectingDelta(candidate.item),
                            wisdom = char.wisdom + wisdomDelta(candidate.item)
                        )
                    }
                } catch (_: Exception) { continue }

                if (candidateScore > bestScore) {
                    bestCandidate = candidate
                    bestScore = candidateScore
                }
            }

            if (bestCandidate != null) {
                val fromCode = helper.getEquippedInSlot(char, slotInfo.slot)
                accumulatedOverrides[slotInfo.slot] = bestCandidate.item.code
                slotChanges.add(SlotChange(
                    slot = slotInfo.slot,
                    fromItemCode = fromCode,
                    toItemCode = bestCandidate.item.code,
                    source = bestCandidate.source
                ))
            }
        }

        val equipActions = slotChanges.map {
            ActionHelper.EquipAction(slot = it.slot, itemCode = it.toItemCode, source = it.source)
        }

        // ── Utility slot pass — only for monsters with effects ──
        val utilityActions = if (hasEffects) {
            optimizeUtilitySlots(char, monsterCode, accumulatedOverrides, bestScore)
        } else {
            emptyList()
        }

        // ── Final API sim validation with the full loadout ──
        val optimizedScore = if (accumulatedOverrides.isEmpty() && utilityActions.isEmpty()) {
            baselineScore
        } else {
            try {
                val finalSim = if (utilityActions.isEmpty()) {
                    helper.simulateFightWithSlotOverrides(char, monsterCode, accumulatedOverrides, API_SIM_ITERATIONS)
                } else {
                    val utilityOverrides = utilityActions.associate { it.slot to it.itemCode }
                    val utilityQuantities = utilityActions.associate { it.slot to it.quantity }
                    helper.simulateFightWithSlotAndUtilityOverrides(
                        char, monsterCode, accumulatedOverrides, utilityOverrides, utilityQuantities, API_SIM_ITERATIONS
                    )
                }
                simToScore(finalSim, char)
            } catch (_: Exception) { bestScore }
        }

        // Build the full target loadout: for every gear slot, record what SHOULD be equipped.
        // Slots present in accumulatedOverrides use the winning candidate; other slots keep
        // whatever the character had equipped. This is what the cache needs to correctly
        // apply the same target loadout to a second character starting from a different baseline.
        val targetLoadout: Map<String, String> = GEAR_SLOTS.mapNotNull { slotInfo ->
            val target = accumulatedOverrides[slotInfo.slot] ?: helper.getEquippedInSlot(char, slotInfo.slot)
            if (target.isEmpty()) null else slotInfo.slot to target
        }.toMap()

        return OptimizationResult(equipActions, utilityActions, baselineScore, optimizedScore, slotChanges, targetLoadout)
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
        val ownedCodes = (char.inventory.map { it.code } + helper.bankState.snapshot.value.keys)
            .filter { it.isNotEmpty() }
            .distinct()
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
        helper.getItemQuantity(char, code) + helper.bankState.getQuantity(code)

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
            .sortedWith(compareByDescending<Item> { score(it) }.thenByDescending { it.level })
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

    private fun prospectingDelta(item: Item): Int =
        item.effects.find { it.code == "prospecting" }?.value ?: 0

    private fun wisdomDelta(item: Item): Int =
        item.effects.find { it.code == "wisdom" }?.value ?: 0

    private fun emptyResult(char: Character): OptimizationResult {
        val score = GearScore(0.0, Double.MAX_VALUE, char.prospecting, char.wisdom)
        return OptimizationResult(emptyList(), emptyList(), score, score, emptyList(), emptyMap())
    }
}
