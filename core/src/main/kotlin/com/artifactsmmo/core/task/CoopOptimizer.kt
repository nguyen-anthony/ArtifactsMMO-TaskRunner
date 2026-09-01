package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.CombatSimulationData
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Monster

/**
 * Cooperative fight optimizer for boss fights.
 *
 * Strategy (rewritten for clarity):
 *  - **Gear**: per-character HEURISTIC scoring. Solo simulation against a boss is useless
 *    (a single character will lose 100% of the time against a boss), so we don't sim per-char.
 *    Instead we score each candidate slot item by its contribution to combat (expected damage
 *    vs boss resistance for weapons; HP + resistance vs boss's attack elements for defensive
 *    gear; generic stat sums for jewelry/artifacts/rune). This is fast, deterministic, and
 *    yields the "best available" loadout without waiting on sim calls.
 *  - **Utilities**: coop-sim-judged greedy pass. Utilities like Splash Restore only make
 *    sense in a coop context — a single-char sim can't evaluate them meaningfully. We use
 *    the multi-character sim endpoint with all three characters' gear locked in.
 *  - **Final validation**: one coop sim reports the optimized team win rate for display.
 *
 * @see ActionHelper.simulateCoopFight
 */
class CoopOptimizer(
    private val helper: ActionHelper,
    private val gearOptimizer: GearOptimizer,
    private val client: ArtifactsMMOClient
) {

    /** Rune candidate category — used for role-based rune priority ordering. */
    private enum class RuneCategory {
        /** Rune that heals the equipped character (self-restore effect). */
        SELF_HEAL,
        /** Rune that heals allies / emits a healing aura (splash/aura restore effect). */
        AURA_HEAL,
        /** Rune focused on dealing damage (boost_dmg / attack effects). */
        DAMAGE,
        /** Rune focused on defense/resistance. */
        DEFENSIVE,
        OTHER
    }

    enum class Role { TANK, SUPPORT }

    /** Utility candidate category — used for role-based priority ordering. */
    private enum class UtilityCategory {
        BOOST_RES_FIRE, BOOST_RES_EARTH, BOOST_RES_WATER, BOOST_RES_AIR,
        RESTORE, BOOST_HP, ANTIPOISON,
        SPLASH_RESTORE,
        BOOST_DMG, BOOST_DMG_FIRE, BOOST_DMG_EARTH, BOOST_DMG_WATER, BOOST_DMG_AIR,
        BOOST_CRITICAL_STRIKE,
        OTHER
    }

    data class ParticipantPlan(
        val characterName: String,
        val role: Role,
        val equipActions: List<ActionHelper.EquipAction>,
        val utilityActions: List<GearOptimizer.UtilityEquipAction>,
        val targetLoadout: Map<String, String>,
        val targetUtilities: Map<String, Pair<String, Int>>,
        /**
         * Potion stacks to withdraw into inventory as reserves for loop sustainability.
         * Map of itemCode -> quantity. These are carried in inventory and re-equipped into
         * the appropriate utility slot when it drops below [UTILITY_REEQUIP_THRESHOLD].
         */
        val reservePotions: Map<String, Int> = emptyMap(),
        /**
         * Food to pre-load into inventory before entering the dungeon.
         * Null if no suitable food was found in the bank.
         */
        val foodCode: String? = null,
        val foodQuantity: Int = 0,
        /**
         * Item costs consumed by the transition to reach the boss (e.g. dungeon keys).
         * These are withdrawn during the provisioning phase so the character doesn't need
         * a mid-travel bank trip when they first reach the transition tile.
         */
        val transitionCosts: Map<String, Int> = emptyMap(),
        /**
         * One spare set of transition keys beyond the initial entry cost.
         * Carried so a single restock trip re-enters the dungeon without another bank visit.
         */
        val spareKeys: Map<String, Int> = emptyMap()
    )

    data class CoopOptimizationResult(
        val monsterCode: String,
        val participants: List<ParticipantPlan>,
        val tankName: String,
        val baselineWinRate: Double,
        val optimizedWinRate: Double,
        val avgWinTurns: Double
    )

    companion object {
        const val COOP_SIM_ITERATIONS = 100
        const val UTILITY_MAX_QUANTITY = 100
        /**
         * Win rate at or above this threshold triggers the priority-first utility assignment
         * path instead of the sim-guided path. At near-100% win rates the coop sim cannot
         * meaningfully distinguish between utility candidates, so we assign by role priority
         * instead to guarantee potions are always equipped for loop sustainability.
         */
        const val HIGH_WIN_RATE_THRESHOLD = 0.95
        /**
         * When a utility slot's remaining quantity drops to or below this value mid-loop,
         * the runner re-equips from inventory reserves before the next fight.
         */
        const val UTILITY_REEQUIP_THRESHOLD = 10
        /**
         * Fraction of inventory capacity reserved for loot drops during boss fight loops.
         * This many slots are excluded from reserve potion calculations.
         */
        const val LOOT_RESERVE_FRACTION = 0.15
        /**
         * Fixed number of inventory slots always kept free for food withdrawal between
         * boss fights. Food is consumed immediately before fighting (out-of-combat healing
         * is faster than resting), so these slots are used transiently — they don't need
         * to coexist with loot drops. Carving them out here ensures the character always
         * has room to withdraw and eat food when HP is low.
         */
        const val FOOD_BUFFER = 10

        /** Utility-valid effect codes (utility slot passive effects, NOT /use food effects). */
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

    // ── Public API ─────────────────────────────────────────────────────

    suspend fun optimizeForBossFight(
        participantNames: List<String>,
        monsterCode: String,
        tankOverride: String? = null
    ): CoopOptimizationResult {
        require(participantNames.isNotEmpty()) { "Must have at least one participant" }
        require(participantNames.size <= 3) { "Coop fight supports at most 3 characters" }

        // 1. Fetch fresh Character objects
        val chars = participantNames.associateWith { name ->
            client.characters.getCharacter(name)
        }

        // 2. Fetch monster once — needed for all heuristic scoring
        val monster = try { helper.contentCache.getMonster(monsterCode) }
                      catch (e: Exception) {
                          println("CoopOptimizer: could not fetch monster $monsterCode: ${e.message}")
                          return emptyResult(monsterCode, participantNames, chars)
                      }

        // 3. Auto-detect tank BEFORE gear optimization using CURRENT threat stat.
        //    (Post-gear threat would be more accurate, but heuristic scoring runs downstream —
        //    we use current threat as a hint. User override always wins.)
        val tankName = tankOverride?.takeIf { it in participantNames }
            ?: chars.entries
                .sortedWith(compareByDescending<Map.Entry<String, Character>> { it.value.threat }.thenBy { it.value.maxHp })
                .first().key
        println("CoopOptimizer: tank = $tankName")

        // 4. Per-character HEURISTIC gear picks (no sim per char — sim against boss solo is useless)
        // claimedGear tracks bank items already claimed by previous characters so that subsequent
        // characters don't try to withdraw items that are already spoken for. Inventory and
        // currently-equipped items are per-character and don't need cross-character tracking.
        //
        // IMPORTANT: tank picks gear FIRST so it can claim priority items (e.g. healing_rune)
        // before supports run. If a support runs first and claims the only healing_rune, the
        // tank gets nothing — exactly the wrong outcome.
        val claimedGear = mutableMapOf<String, Int>()
        val gearPicks = mutableMapOf<String, GearPickResult>()
        val orderedForGear = listOf(tankName) + participantNames.filter { it != tankName }
        for (name in orderedForGear) {
            val role = if (name == tankName) Role.TANK else Role.SUPPORT
            println("[$name] CoopOptimizer: picking best gear (heuristic, role=$role) vs $monsterCode")
            gearPicks[name] = try {
                pickBestGearHeuristic(chars[name]!!, monster, role, claimedGear)
            } catch (e: Exception) {
                println("[$name] CoopOptimizer: heuristic gear pick failed: ${e.message}")
                GearPickResult(emptyList(), emptyMap())
            }
        }

        // 4b. Post-gear threat validation pass.
        //
        // After heuristic gear picks, compute each character's post-gear threat:
        //   post-gear threat = base char.threat + Σ(threat effect on each item in targetLoadout)
        //
        // Invariant: the tank's post-gear threat must strictly exceed every support's.
        // If any support's post-gear threat >= tank's, swap the highest-threat item in
        // that support's loadout for the next-best alternative that reduces their threat.
        // Repeat until the invariant holds or no swap is possible.
        //
        // This is a safety net for edge cases where Fix 1 (threatWeight=0 for support) still
        // allows a support to incidentally match the tank's threat via non-threat-primary items
        // that happen to carry incidental threat effects.
        // 4b. Post-gear threat validation pass.
        //
        // Pre-fetch threat values for every item in every loadout so the validation
        // loop below can be synchronous (getItemOrNull is a suspend function and
        // cannot be called from a plain local function).
        val allLoadoutCodes = gearPicks.values.flatMap { it.targetLoadout.values }.distinct()
        val threatByCode = mutableMapOf<String, Int>()
        for (code in allLoadoutCodes) {
            try {
                threatByCode[code] = helper.contentCache.getItemOrNull(code)?.effects
                    ?.find { it.code == "threat" }?.value ?: 0
            } catch (_: Exception) { threatByCode[code] = 0 }
        }

        fun postGearThreat(name: String): Int {
            val char = chars[name]!!
            val loadout = gearPicks[name]?.targetLoadout ?: emptyMap()
            val gearThreat = loadout.values.sumOf { code -> threatByCode[code] ?: 0 }
            return char.threat + gearThreat
        }

        val supportNames = participantNames.filter { it != tankName }
        val tankPostThreat = postGearThreat(tankName)
        for (supportName in supportNames) {
            val supportPostThreat = postGearThreat(supportName)
            if (supportPostThreat < tankPostThreat) continue

            // Support's post-gear threat >= tank's — find the highest-threat item in support's loadout
            println("[$supportName] CoopOptimizer: post-gear threat $supportPostThreat >= tank $tankName threat $tankPostThreat — adjusting")
            val support = chars[supportName]!!
            val supportPick = gearPicks[supportName] ?: continue
            val mutableEquipActions = supportPick.equipActions.toMutableList()
            val mutableLoadout = supportPick.targetLoadout.toMutableMap()

            // Find the slot contributing the most threat in the support's loadout
            val worstSlot = mutableLoadout.entries.maxByOrNull { (_, code) ->
                try {
                    helper.contentCache.getItemOrNull(code)?.effects
                        ?.find { it.code == "threat" }?.value ?: 0
                } catch (_: Exception) { 0 }
            }
            if (worstSlot == null) continue
            val worstThreatValue = try {
                helper.contentCache.getItemOrNull(worstSlot.value)?.effects
                    ?.find { it.code == "threat" }?.value ?: 0
            } catch (_: Exception) { 0 }
            if (worstThreatValue <= 0) {
                // No item in the support's loadout has an explicit threat effect —
                // their threat tie comes from base stats, which we can't change via gear.
                println("[$supportName] CoopOptimizer: no threat-bearing item to swap — accepting tie (base stat threat)")
                continue
            }

            // Find the next-best alternative for worstSlot that has zero threat
            val slotInfo = GearOptimizer.GEAR_SLOTS.firstOrNull { it.slot == worstSlot.key }
            if (slotInfo == null) continue
            val boss = monster
            val bossDmg = mapOf(
                "fire" to boss.attackFire, "earth" to boss.attackEarth,
                "water" to boss.attackWater, "air" to boss.attackAir
            )
            val bossRes = mapOf(
                "fire" to boss.resFire, "earth" to boss.resEarth,
                "water" to boss.resWater, "air" to boss.resAir
            )
            val alternative = try {
                helper.getAvailableEquipmentForSlot(support, slotInfo)
                    .filter { it.source == "inventory" || it.source == "bank" }
                    .filter { option ->
                        // Must have zero threat effect
                        option.item.effects.none { it.code == "threat" }
                    }
                    .filter { option ->
                        // Cross-character claimed gear check
                        if (option.source != "bank") true
                        else {
                            val bankQty = helper.bankState.getQuantity(option.item.code)
                            val claimed = claimedGear.getOrDefault(option.item.code, 0)
                            (bankQty - claimed) > 0
                        }
                    }
                    .maxByOrNull { scoreItem(it.item, worstSlot.key, bossDmg, bossRes, Role.SUPPORT) }
            } catch (_: Exception) { null }

            if (alternative == null) {
                println("[$supportName] CoopOptimizer: no zero-threat alternative for ${worstSlot.key} — keeping ${worstSlot.value}")
                continue
            }

            println("[$supportName] CoopOptimizer: replacing ${worstSlot.value} (threat=$worstThreatValue) in ${worstSlot.key} with ${alternative.item.code} (zero threat)")
            mutableLoadout[worstSlot.key] = alternative.item.code
            mutableEquipActions.removeAll { it.slot == worstSlot.key }
            val currentInSlot = helper.getEquippedInSlot(support, worstSlot.key)
            if (alternative.item.code != currentInSlot && alternative.source != "equipped") {
                if (alternative.source == "bank") {
                    claimedGear[alternative.item.code] = claimedGear.getOrDefault(alternative.item.code, 0) + 1
                }
                mutableEquipActions.add(ActionHelper.EquipAction(
                    slot = worstSlot.key,
                    itemCode = alternative.item.code,
                    source = alternative.source
                ))
            }
            // Also release the claim on the item we stopped recommending
            if (supportPick.equipActions.any { it.slot == worstSlot.key && it.source == "bank" }) {
                val oldCode = worstSlot.value
                val cur = claimedGear.getOrDefault(oldCode, 0)
                if (cur > 0) claimedGear[oldCode] = cur - 1
            }

            gearPicks[supportName] = GearPickResult(mutableEquipActions, mutableLoadout)
        }
        val gearOnlyLoadouts = participantNames.map { name ->
            ActionHelper.CoopParticipantLoadout(
                char = chars[name]!!,
                slotOverrides = gearPicks[name]!!.targetLoadout
            )
        }
        val baselineSim = try {
            helper.simulateCoopFight(monsterCode, gearOnlyLoadouts, COOP_SIM_ITERATIONS)
        } catch (e: Exception) {
            println("CoopOptimizer: baseline coop sim failed: ${e.message}")
            null
        }
        val baselineWinRate = baselineSim?.winrate ?: 0.0
        println("CoopOptimizer: baseline (gear only) coop win rate = ${(baselineWinRate * 100).toInt()}%")

        // 6. Utility greedy pass — tank first, then supports.
        //    Coop sim is essential here: Splash Restore heals allies, so we need a team sim.
        //
        //    Two strategies depending on baseline win rate:
        //    - Win rate < HIGH_WIN_RATE_THRESHOLD: sim-guided greedy — pick the candidate that
        //      most improves the coop score (win rate > avg turns > utility stats).
        //    - Win rate >= HIGH_WIN_RATE_THRESHOLD: sim-free priority assignment — the team is
        //      already winning; just assign the highest-priority available potion per role.
        //      The coop sim can't distinguish utility value at near-100% win rates, so forcing
        //      the priority list ensures potions are always equipped for loop sustainability.
        //
        //    Committed quantity tracking: each time a potion is committed to a character, its
        //    contribution is subtracted from the available pool so that subsequent characters
        //    see the real remaining stock.
        val monsterEffects = monster.effects.map { it.code }.toSet()
        val committedUtilities = mutableMapOf<String, MutableMap<String, Pair<String, Int>>>()
            .apply { participantNames.forEach { put(it, mutableMapOf()) } }
        // Global committed quantities across all characters: code -> total claimed so far
        val committedQuantities = mutableMapOf<String, Int>()

        val highWinRate = baselineWinRate >= HIGH_WIN_RATE_THRESHOLD

        val orderedNames = listOf(tankName) + participantNames.filter { it != tankName }
        for (name in orderedNames) {
            val role = if (name == tankName) Role.TANK else Role.SUPPORT
            val char = chars[name]!!

            for (utilitySlot in listOf("utility1", "utility2")) {
                val alreadyPickedForOtherSlot = committedUtilities[name]!!
                    .filterKeys { it != utilitySlot }
                    .values.map { it.first }
                    .toSet()

                val candidateGroups = orderedCandidatesByRole(char, role, monsterEffects, alreadyPickedForOtherSlot)
                if (candidateGroups.all { it.isEmpty() }) {
                    println("[$name] CoopOptimizer: no owned utility candidates for $utilitySlot")
                    continue
                }

                if (highWinRate) {
                    // ── Priority-first path (team already winning) ──────────────────
                    // Assign the top-priority potion we actually have available stock for.
                    var picked: Pair<Item, Int>? = null
                    outer@ for (categoryPool in candidateGroups) {
                        for (candidate in categoryPool) {
                            val totalOwned = getOwnedQuantity(char, candidate.code)
                            val alreadyClaimed = committedQuantities.getOrDefault(candidate.code, 0)
                            val available = (totalOwned - alreadyClaimed).coerceAtMost(UTILITY_MAX_QUANTITY)
                            if (available <= 0) continue
                            picked = candidate to available
                            break@outer
                        }
                    }
                    if (picked != null) {
                        val (item, qty) = picked
                        committedUtilities[name]!![utilitySlot] = item.code to qty
                        committedQuantities[item.code] = (committedQuantities[item.code] ?: 0) + qty
                        println("[$name] CoopOptimizer: $utilitySlot -> ${item.code} x$qty (role=$role, priority-first, high win rate ${(baselineWinRate * 100).toInt()}%)")
                    } else {
                        println("[$name] CoopOptimizer: $utilitySlot -> no available candidates (high win rate path)")
                    }
                } else {
                    // ── Sim-guided greedy path ──────────────────────────────────────
                    var bestCandidate: Pair<Item, Int>? = null
                    var bestScore: CoopScore = simToCoopScore(
                        buildCoopLoadoutsWith(participantNames, chars, gearPicks, committedUtilities, name, utilitySlot, null),
                        monsterCode
                    )

                    for (categoryPool in candidateGroups) {
                        if (categoryPool.isEmpty()) continue
                        for (candidate in categoryPool) {
                            val totalOwned = getOwnedQuantity(char, candidate.code)
                            val alreadyClaimed = committedQuantities.getOrDefault(candidate.code, 0)
                            val available = (totalOwned - alreadyClaimed).coerceAtMost(UTILITY_MAX_QUANTITY)
                            if (available <= 0) continue

                            val loadouts = buildCoopLoadoutsWith(
                                participantNames, chars, gearPicks, committedUtilities,
                                name, utilitySlot, candidate.code to available
                            )
                            val score = simToCoopScore(loadouts, monsterCode)
                            if (score > bestScore) {
                                bestCandidate = candidate to available
                                bestScore = score
                            }
                        }
                        if (bestCandidate != null) break
                    }

                    if (bestCandidate != null) {
                        val (item, qty) = bestCandidate
                        committedUtilities[name]!![utilitySlot] = item.code to qty
                        committedQuantities[item.code] = (committedQuantities[item.code] ?: 0) + qty
                        println("[$name] CoopOptimizer: $utilitySlot -> ${item.code} x$qty (role=$role, win rate ${(bestScore.winRate * 100).toInt()}%)")
                    } else {
                        println("[$name] CoopOptimizer: $utilitySlot -> no improvement found")
                    }
                }
            }
        }

        // 7. Final coop sim to record optimized win rate + turns
        val finalLoadouts = buildFinalCoopLoadouts(participantNames, chars, gearPicks, committedUtilities)
        val finalSim = try {
            helper.simulateCoopFight(monsterCode, finalLoadouts, COOP_SIM_ITERATIONS)
        } catch (e: Exception) {
            println("CoopOptimizer: final coop sim failed: ${e.message}")
            baselineSim
        }
        val optimizedWinRate = finalSim?.winrate ?: baselineWinRate
        val avgWinTurns = finalSim?.results?.filter { it.result == "win" }?.map { it.turns }
            ?.let { if (it.isEmpty()) 0.0 else it.average() } ?: 0.0
        println("CoopOptimizer: optimized coop win rate = ${(optimizedWinRate * 100).toInt()}%, avg win turns = ${"%.1f".format(avgWinTurns)}")

        // 8. Build per-participant plans (includes reserve potion + provisioning calculation)
        //
        // Transition cost pre-detection: find the boss tile once and compute the item cost
        // of entering the dungeon. Each character carries the entry cost + 1 spare set of
        // keys so that a restock trip re-enters without another bank visit.
        val bossTargetMap = try {
            helper.contentCache.findNearestAnyLayer(chars[participantNames.first()]!!, "monster", monsterCode)
        } catch (_: Exception) { null }

        // Use the first character's position as a representative starting point for
        // transition cost detection (all participants start from overworld).
        val transitionCosts: Map<String, Int> = if (bossTargetMap != null) {
            try {
                helper.getTransitionCosts(chars[participantNames.first()]!!, bossTargetMap)
            } catch (_: Exception) { emptyMap() }
        } else emptyMap()

        if (transitionCosts.isNotEmpty()) {
            println("CoopOptimizer: detected transition costs to $monsterCode: ${transitionCosts.entries.joinToString { "${it.value}x ${it.key}" }}")
        }

        // Gold cost warning: gold is on the character and not bank-withdrawable.
        // Warn if any character may not have enough gold for the round-trip (entry + spare trip).
        val goldCost = transitionCosts["gold"] ?: 0
        if (goldCost > 0) {
            val roundTripGold = goldCost * 2  // entry + restock re-entry
            for (name in participantNames) {
                val charGold = chars[name]!!.gold
                if (charGold < roundTripGold) {
                    println("[$name] CoopOptimizer: WARNING — gold transition cost ${goldCost}g/entry ($roundTripGold for round-trip), character has ${charGold}g")
                } else {
                    println("[$name] CoopOptimizer: gold transition cost ${goldCost}g — character has ${charGold}g (sufficient)")
                }
            }
        }

        val plans = participantNames.map { name ->
            val role = if (name == tankName) Role.TANK else Role.SUPPORT
            val char = chars[name]!!
            val gear = gearPicks[name]!!
            val utilities = committedUtilities[name]!!.toMap()

            // ── Transition cost inventory accounting ──────────────────────────────
            // Entry cost + 1 spare set of keys. Spare keys let the character re-enter
            // after a restock trip without going to the bank a second time.
            val spareKeys = transitionCosts.mapValues { 1 }  // 1 spare of each cost item
            val transitionSlots = transitionCosts.values.sum() + spareKeys.values.sum()

            // ── Reserve potion calculation ──────────────────────────────────────
            // Available reserve capacity:
            //   total_capacity - loot_reserve - food_buffer - transition_slots - currently_used
            // FOOD_BUFFER ensures slots are always free to withdraw and eat food between fights.
            // transition_slots reserves space for the entry key + spare key.
            val currentInventoryUsed = char.inventory.sumOf { it.quantity }
            val lootReserve = kotlin.math.ceil(char.inventoryMaxItems * LOOT_RESERVE_FRACTION).toInt()
            val availableForReserves = (char.inventoryMaxItems - lootReserve - FOOD_BUFFER - transitionSlots - currentInventoryUsed)
                .coerceAtLeast(0)

            val uniquePotionCodes = utilities.values.map { it.first }.distinct()
            val reservePotions = mutableMapOf<String, Int>()
            if (uniquePotionCodes.isNotEmpty() && availableForReserves > 0) {
                val perPotion = availableForReserves / uniquePotionCodes.size
                for (code in uniquePotionCodes) {
                    if (perPotion <= 0) break
                    val bankAvailable = helper.bankState.getQuantity(code)
                        .let { it - (committedQuantities[code] ?: 0) }
                        .coerceAtLeast(0)
                    val reserveQty = perPotion.coerceAtMost(bankAvailable)
                    if (reserveQty > 0) {
                        reservePotions[code] = reserveQty
                        println("[$name] CoopOptimizer: reserve $code x$reserveQty in inventory")
                    }
                }
            }

            // ── Food pre-load calculation ──────────────────────────────────────
            // Withdraw up to FOOD_BUFFER slots of the best available food. Food is consumed
            // out-of-combat between boss fights — carrying it avoids bank trips for healing.
            val foodInfo = try { helper.findBestFoodInBank(char) } catch (_: Exception) { null }
            val foodCode = foodInfo?.first
            val foodQuantity = if (foodInfo != null) {
                val bankQty = foodInfo.third
                minOf(bankQty, FOOD_BUFFER)
            } else 0

            if (foodCode != null && foodQuantity > 0) {
                println("[$name] CoopOptimizer: pre-load food $foodCode x$foodQuantity")
            }
            if (transitionCosts.isNotEmpty()) {
                println("[$name] CoopOptimizer: pre-load transition costs ${transitionCosts.entries.joinToString { "${it.value}x ${it.key}" }} + spare ${spareKeys.entries.joinToString { "${it.value}x ${it.key}" }}")
            }

            ParticipantPlan(
                characterName = name,
                role = role,
                equipActions = gear.equipActions,
                utilityActions = utilities.map { (slot, codeQty) ->
                    val (code, qty) = codeQty
                    val source = if (helper.getItemQuantity(char, code) >= qty) "inventory" else "bank"
                    GearOptimizer.UtilityEquipAction(slot, code, qty, source)
                },
                targetLoadout = gear.targetLoadout,
                targetUtilities = utilities,
                reservePotions = reservePotions,
                foodCode = foodCode,
                foodQuantity = foodQuantity,
                transitionCosts = transitionCosts,
                spareKeys = spareKeys
            )
        }

        return CoopOptimizationResult(
            monsterCode = monsterCode,
            participants = plans,
            tankName = tankName,
            baselineWinRate = baselineWinRate,
            optimizedWinRate = optimizedWinRate,
            avgWinTurns = avgWinTurns
        )
    }

    // ── Heuristic gear picker ──────────────────────────────────────────

    private data class GearPickResult(
        val equipActions: List<ActionHelper.EquipAction>,
        val targetLoadout: Map<String, String>
    )

    /**
     * Pick the best available gear for [char] against [monster] using deterministic
     * heuristic scoring. No simulation calls — this is fast and cheap.
     *
     * Scoring per candidate:
     *  - Weapon: expected damage output after boss resistance (existing findBestWeapon logic)
     *  - Defensive gear (shield/helmet/body_armor/leg_armor/boots): HP + weighted resistance
     *    vs boss's attack element output
     *  - Jewelry (rings/amulet): combined offense + defense score
     *  - Artifacts: combined score with UNIQUENESS constraint (no duplicate artifacts across slots)
     *  - Rune: highest general combat score
     *
     * Only considers items the character OWNS (inventory or bank). Artifacts and runes are
     * "owned only" — we don't auto-buy/trade them.
     */
    private suspend fun pickBestGearHeuristic(
        char: Character,
        monster: Monster,
        role: Role,
        claimedGear: MutableMap<String, Int> = mutableMapOf()
    ): GearPickResult {
        val bossDmg = mapOf(
            "fire"  to monster.attackFire,
            "earth" to monster.attackEarth,
            "water" to monster.attackWater,
            "air"   to monster.attackAir
        )
        val bossRes = mapOf(
            "fire"  to monster.resFire,
            "earth" to monster.resEarth,
            "water" to monster.resWater,
            "air"   to monster.resAir
        )

        val equipActions = mutableListOf<ActionHelper.EquipAction>()
        val targetLoadout = mutableMapOf<String, String>()
        val committedArtifactCodes = mutableSetOf<String>()

        for (slotInfo in GearOptimizer.GEAR_SLOTS) {
            val currentEquipped = helper.getEquippedInSlot(char, slotInfo.slot)

            val candidates = try {
                helper.getAvailableEquipmentForSlot(char, slotInfo)
                    .filter { it.source == "inventory" || it.source == "bank" }
                    .filter { slotInfo.slot != "weapon" || it.item.subtype != "tool" }
                    .filter { slotInfo.slot !in ARTIFACT_SLOTS || it.item.code !in committedArtifactCodes }
                    // Cross-character claim check: skip bank items already fully claimed by
                    // previous characters. Inventory items are per-character — no need to check.
                    .filter { option ->
                        if (option.source != "bank") return@filter true
                        val bankQty = helper.bankState.getQuantity(option.item.code)
                        val claimed = claimedGear.getOrDefault(option.item.code, 0)
                        (bankQty - claimed) > 0
                    }
            } catch (_: Exception) { emptyList() }

            // Include current equipped as a baseline — we shouldn't downgrade
            val currentItem = if (currentEquipped.isNotEmpty()) {
                try { helper.contentCache.getItemOrNull(currentEquipped) } catch (_: Exception) { null }
            } else null

            val allOptions = buildList {
                addAll(candidates)
                if (currentItem != null && slotInfo.slot !in ARTIFACT_SLOTS.filter { it != slotInfo.slot }) {
                    // Include current as a "keep" option (source = existing)
                    add(ActionHelper.EquipmentOption(currentItem, "equipped", 1))
                }
            }

            if (allOptions.isEmpty()) continue

            val bestOption = allOptions.maxByOrNull { option ->
                scoreItem(option.item, slotInfo.slot, bossDmg, bossRes, role)
            } ?: continue

            // Track target loadout (used for coop sim overrides)
            targetLoadout[slotInfo.slot] = bestOption.item.code

            // Track artifact uniqueness
            if (slotInfo.slot in ARTIFACT_SLOTS) {
                committedArtifactCodes.add(bestOption.item.code)
            }

            // Track cross-character bank claims so subsequent characters don't over-claim
            if (bestOption.source == "bank") {
                claimedGear[bestOption.item.code] = claimedGear.getOrDefault(bestOption.item.code, 0) + 1
            }

            // Emit equip action if a change is needed
            if (bestOption.item.code != currentEquipped && bestOption.source != "equipped") {
                equipActions.add(ActionHelper.EquipAction(
                    slot = slotInfo.slot,
                    itemCode = bestOption.item.code,
                    source = bestOption.source
                ))
            }
        }

        // ── Role-aware rune override ────────────────────────────────────────────
        // The generic GEAR_SLOTS loop above scores runes purely by stat sheet, which means
        // a healing-aura rune (heals allies) can beat a self-heal rune (heals self) for the
        // tank — wrong for survivability. Replace whatever the generic pass chose with the
        // best rune for this character's role.
        val bestRune = pickBestRune(char, monster, role, claimedGear)
        if (bestRune != null) {
            val currentRune = helper.getEquippedInSlot(char, "rune")
            targetLoadout["rune"] = bestRune.item.code
            // Remove any existing rune equip action from the generic pass, then add ours
            equipActions.removeAll { it.slot == "rune" }
            if (bestRune.item.code != currentRune && bestRune.source != "equipped") {
                // Track cross-character bank claim for runes
                if (bestRune.source == "bank") {
                    claimedGear[bestRune.item.code] = claimedGear.getOrDefault(bestRune.item.code, 0) + 1
                }
                equipActions.add(ActionHelper.EquipAction(
                    slot = "rune",
                    itemCode = bestRune.item.code,
                    source = bestRune.source
                ))
                println("[${char.name}] CoopOptimizer: rune -> ${bestRune.item.code} (role=$role, category=${classifyRune(bestRune.item)})")
            }
        }

        return GearPickResult(equipActions, targetLoadout)
    }

    /**
     * Heuristic value of an [item] in [slot] against a boss whose attack outputs are
     * [bossDmg] and whose resistances are [bossRes], for a character playing [role].
     *
     * Higher = better. Combines expected damage output (offense) with survivability
     * (HP + resistance to boss's attack elements), weighted by role.
     */
    private fun scoreItem(
        item: Item,
        slot: String,
        bossDmg: Map<String, Int>,
        bossRes: Map<String, Int>,
        role: Role
    ): Double {
        val effects = item.effects.associate { it.code to it.value }
        val globalDmgBonus = (effects["dmg"] ?: 0) / 100.0

        // ── Offense: expected damage after boss resistance ──
        val offense = bossRes.entries.sumOf { (element, res) ->
            val attackKey = "attack_$element"
            val dmgKey = "dmg_$element"
            val baseAttack = effects[attackKey] ?: 0
            val elementDmgBonus = (effects[dmgKey] ?: 0) / 100.0
            val resMult = (1.0 - res / 100.0).coerceAtLeast(0.0)
            baseAttack * (1.0 + globalDmgBonus + elementDmgBonus) * resMult
        }

        // ── Defense: HP + weighted resistance against boss's attack outputs ──
        val hp = (effects["hp"] ?: 0).toDouble()
        val defenseFromRes = bossDmg.entries.sumOf { (element, atk) ->
            val resKey = "res_$element"
            val resValue = (effects[resKey] ?: 0)
            // A point of resistance is worth roughly 1% of the boss's damage in that element
            atk * (resValue / 100.0)
        }
        val defense = hp + defenseFromRes

        // ── Threat: matters for tank role ──
        val threat = (effects["threat"] ?: 0).toDouble()

        // ── Utility stats ──
        val prospecting = (effects["prospecting"] ?: 0).toDouble()
        val wisdom = (effects["wisdom"] ?: 0).toDouble()
        val criticalStrike = (effects["critical_strike"] ?: 0).toDouble()
        val haste = (effects["haste"] ?: 0).toDouble()

        // ── Role weighting ──
        val (offenseWeight, defenseWeight, threatWeight) = when (role) {
            Role.TANK    -> Triple(0.6, 1.4, 2.0)   // Prioritize survival + threat
            // threatWeight = 0.0 for SUPPORT — supports must NOT compete for threat.
            // The user declared which character is the tank; incidental threat gain on a
            // support character would flip monster targeting and leave the support holding
            // the wrong utility loadout (splash-heal vs. self-heal). Any threat-bearing item
            // a support equips should win only on its other stats, never on threat.
            Role.SUPPORT -> Triple(1.4, 0.6, 0.0)   // Prioritize damage; ignore threat
        }

        // ── Slot-specific weighting ──
        // Weapons are pure offense; armor/shield are pure defense; jewelry/artifacts/rune are mixed
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

        return offense * offenseWeight * slotOffenseWeight +
               defense * defenseWeight * slotDefenseWeight +
               threat * threatWeight +
               (prospecting + wisdom) * 0.1 +
               criticalStrike * 2.0 +
               haste * 1.5
    }

    private val ARTIFACT_SLOTS = setOf("artifact1", "artifact2", "artifact3")

    // ── Utility candidate handling ─────────────────────────────────────

    private suspend fun orderedCandidatesByRole(
        char: Character,
        role: Role,
        monsterEffects: Set<String>,
        excludeCodes: Set<String>
    ): List<List<Item>> {
        val owned = getOwnedConsumables(char).filter { it.code !in excludeCodes }
        val byCategory: Map<UtilityCategory, List<Item>> = owned.groupBy { classifyUtility(it) }
        val hasPoison = "poison" in monsterEffects

        val tankOrder = buildList {
            // Tank: survive as long as possible — self-heal first, then HP boost, then
            // elemental resistances, then damage. Antipoison only when monster poisons.
            add(UtilityCategory.RESTORE)
            add(UtilityCategory.BOOST_HP)
            if (hasPoison) add(UtilityCategory.ANTIPOISON)
            add(UtilityCategory.BOOST_RES_FIRE)
            add(UtilityCategory.BOOST_RES_EARTH)
            add(UtilityCategory.BOOST_RES_WATER)
            add(UtilityCategory.BOOST_RES_AIR)
            add(UtilityCategory.BOOST_DMG)
            add(UtilityCategory.BOOST_DMG_FIRE)
            add(UtilityCategory.BOOST_DMG_EARTH)
            add(UtilityCategory.BOOST_DMG_WATER)
            add(UtilityCategory.BOOST_DMG_AIR)
            add(UtilityCategory.OTHER)
        }
        val supportOrder = listOf(
            UtilityCategory.SPLASH_RESTORE,
            UtilityCategory.BOOST_DMG,
            UtilityCategory.BOOST_DMG_FIRE,
            UtilityCategory.BOOST_DMG_EARTH,
            UtilityCategory.BOOST_DMG_WATER,
            UtilityCategory.BOOST_DMG_AIR,
            UtilityCategory.BOOST_CRITICAL_STRIKE,
            UtilityCategory.RESTORE,
            UtilityCategory.OTHER
        )
        val order = if (role == Role.TANK) tankOrder else supportOrder
        return order.map { byCategory[it] ?: emptyList() }
    }

    private fun classifyUtility(item: Item): UtilityCategory {
        val codes = item.effects.map { it.code }.toSet()
        return when {
            "boost_res_fire" in codes -> UtilityCategory.BOOST_RES_FIRE
            "boost_res_earth" in codes -> UtilityCategory.BOOST_RES_EARTH
            "boost_res_water" in codes -> UtilityCategory.BOOST_RES_WATER
            "boost_res_air" in codes -> UtilityCategory.BOOST_RES_AIR
            "boost_dmg_fire" in codes -> UtilityCategory.BOOST_DMG_FIRE
            "boost_dmg_earth" in codes -> UtilityCategory.BOOST_DMG_EARTH
            "boost_dmg_water" in codes -> UtilityCategory.BOOST_DMG_WATER
            "boost_dmg_air" in codes -> UtilityCategory.BOOST_DMG_AIR
            "boost_dmg" in codes -> UtilityCategory.BOOST_DMG
            "boost_critical_strike" in codes -> UtilityCategory.BOOST_CRITICAL_STRIKE
            "splash_restore" in codes -> UtilityCategory.SPLASH_RESTORE
            "restore" in codes -> UtilityCategory.RESTORE
            "boost_hp" in codes -> UtilityCategory.BOOST_HP
            "antipoison" in codes || "antidote" in codes -> UtilityCategory.ANTIPOISON
            else -> UtilityCategory.OTHER
        }
    }

    private suspend fun getOwnedConsumables(char: Character): List<Item> {
        val invCodes = char.inventory.filter { it.quantity > 0 }.map { it.code }
        val bankCodes = helper.bankState.snapshot.value.filter { it.value > 0 }.keys
        val ownedCodes = (invCodes + bankCodes).distinct().filter { it.isNotEmpty() }
        return ownedCodes.mapNotNull { code ->
            try {
                val item = helper.contentCache.getItemOrNull(code) ?: return@mapNotNull null
                // Utility slots hold items of type "utility" (subtype = "potion").
                // Note: type "consumable" is FOOD (used via /action/use), not utility slot items.
                if (item.type != "utility") return@mapNotNull null
                if (item.level > char.level) return@mapNotNull null
                if (item.effects.none { it.code in UTILITY_EFFECT_CODES }) return@mapNotNull null
                item
            } catch (_: Exception) { null }
        }
    }

    private fun getOwnedQuantity(char: Character, code: String): Int =
        helper.getItemQuantity(char, code) + helper.bankState.getQuantity(code)

    // ── Loadout builders for coop sim ─────────────────────────────────

    private fun buildCoopLoadoutsWith(
        participantNames: List<String>,
        chars: Map<String, Character>,
        gearPicks: Map<String, GearPickResult>,
        committedUtilities: Map<String, Map<String, Pair<String, Int>>>,
        experimentalName: String,
        experimentalSlot: String,
        experimental: Pair<String, Int>?
    ): List<ActionHelper.CoopParticipantLoadout> {
        return participantNames.map { name ->
            val char = chars[name]!!
            val gear = gearPicks[name]?.targetLoadout ?: emptyMap()
            val committed = committedUtilities[name] ?: emptyMap()
            val utilityOverrides = mutableMapOf<String, String>()
            val utilityQuantities = mutableMapOf<String, Int>()
            for ((slot, codeQty) in committed) {
                utilityOverrides[slot] = codeQty.first
                utilityQuantities[slot] = codeQty.second
            }
            if (name == experimentalName && experimental != null) {
                utilityOverrides[experimentalSlot] = experimental.first
                utilityQuantities[experimentalSlot] = experimental.second
            }
            ActionHelper.CoopParticipantLoadout(
                char = char,
                slotOverrides = gear,
                utilityOverrides = utilityOverrides,
                utilityQuantities = utilityQuantities
            )
        }
    }

    private fun buildFinalCoopLoadouts(
        participantNames: List<String>,
        chars: Map<String, Character>,
        gearPicks: Map<String, GearPickResult>,
        committedUtilities: Map<String, Map<String, Pair<String, Int>>>
    ): List<ActionHelper.CoopParticipantLoadout> {
        return participantNames.map { name ->
            val char = chars[name]!!
            val gear = gearPicks[name]?.targetLoadout ?: emptyMap()
            val committed = committedUtilities[name] ?: emptyMap()
            ActionHelper.CoopParticipantLoadout(
                char = char,
                slotOverrides = gear,
                utilityOverrides = committed.mapValues { it.value.first },
                utilityQuantities = committed.mapValues { it.value.second }
            )
        }
    }

    // ── Scoring ────────────────────────────────────────────────────────

    private data class CoopScore(
        val winRate: Double,
        val avgWinTurns: Double,
        val totalUtility: Int
    ) : Comparable<CoopScore> {
        override fun compareTo(other: CoopScore): Int {
            // Tightened thresholds: 0.5% win rate and 0.5 turn improvement both count.
            // The previous 1%/1-turn dead zone was too wide for marginal-but-real utility gains.
            if (this.winRate > other.winRate + 0.005) return 1
            if (this.winRate < other.winRate - 0.005) return -1
            if (this.avgWinTurns < other.avgWinTurns - 0.5) return 1
            if (this.avgWinTurns > other.avgWinTurns + 0.5) return -1
            return this.totalUtility.compareTo(other.totalUtility)
        }
    }

    private suspend fun simToCoopScore(
        loadouts: List<ActionHelper.CoopParticipantLoadout>,
        monsterCode: String
    ): CoopScore {
        val sim = try {
            helper.simulateCoopFight(monsterCode, loadouts, COOP_SIM_ITERATIONS)
        } catch (_: Exception) {
            return CoopScore(0.0, Double.MAX_VALUE, 0)
        }
        val avgTurns = sim.results.filter { it.result == "win" }.map { it.turns }
            .let { if (it.isEmpty()) Double.MAX_VALUE else it.average() }
        val totalUtility = loadouts.sumOf { it.char.prospecting + it.char.wisdom }
        return CoopScore(sim.winrate, avgTurns, totalUtility)
    }

    private fun emptyResult(monsterCode: String, participantNames: List<String>, chars: Map<String, Character>): CoopOptimizationResult {
        val plans = participantNames.map { name ->
            ParticipantPlan(
                characterName = name,
                role = Role.SUPPORT,
                equipActions = emptyList(),
                utilityActions = emptyList(),
                targetLoadout = emptyMap(),
                targetUtilities = emptyMap()
            )
        }
        return CoopOptimizationResult(
            monsterCode = monsterCode,
            participants = plans,
            tankName = participantNames.first(),
            baselineWinRate = 0.0,
            optimizedWinRate = 0.0,
            avgWinTurns = 0.0
        )
    }

    // ── Rune helpers ───────────────────────────────────────────────────

    /**
     * Classify a rune item by its primary combat effect, used for role-based rune selection.
     *
     * Priority of classification (first match wins):
     *  - Any "restore" effect on self → SELF_HEAL
     *  - Any "splash_restore" or "aura" healing effect → AURA_HEAL
     *  - Any direct damage boost effect → DAMAGE
     *  - Any resistance/HP effect → DEFENSIVE
     *  - Anything else → OTHER
     */
    private fun classifyRune(item: Item): RuneCategory {
        val codes = item.effects.map { it.code }.toSet()
        return when {
            // Self-heal: restores HP to the caster only (e.g. healing_rune — effect "healing")
            "healing" in codes || "restore" in codes -> RuneCategory.SELF_HEAL
            // Aura-heal: heals allies but NOT the caster (e.g. healing_aura_rune — effect "healing_aura")
            "healing_aura" in codes || codes.any { it.contains("aura") } -> RuneCategory.AURA_HEAL
            codes.any { it.startsWith("boost_dmg") || it.startsWith("attack_") || it == "dmg" } -> RuneCategory.DAMAGE
            codes.any { it.startsWith("res_") || it == "hp" || it.startsWith("boost_res_") || it == "boost_hp" } -> RuneCategory.DEFENSIVE
            else -> RuneCategory.OTHER
        }
    }

    /**
     * Pick the best rune for [char] playing [role] from the character's owned rune items.
     *
     * Role priorities:
     *  - TANK:    SELF_HEAL > DAMAGE > DEFENSIVE > AURA_HEAL > OTHER
     *  - SUPPORT: AURA_HEAL > DAMAGE > DEFENSIVE > SELF_HEAL > OTHER
     *
     * Within each priority tier, the rune with the highest [scoreItem] wins.
     * Returns null if the character owns no runes at or below their level.
     */
    private suspend fun pickBestRune(
        char: Character,
        monster: Monster,
        role: Role,
        claimedGear: Map<String, Int> = emptyMap()
    ): ActionHelper.EquipmentOption? {
        val bossDmg = mapOf(
            "fire"  to monster.attackFire,
            "earth" to monster.attackEarth,
            "water" to monster.attackWater,
            "air"   to monster.attackAir
        )
        val bossRes = mapOf(
            "fire"  to monster.resFire,
            "earth" to monster.resEarth,
            "water" to monster.resWater,
            "air"   to monster.resAir
        )

        val runeSlotInfo = GearOptimizer.GEAR_SLOTS.first { it.slot == "rune" }
        val candidates = try {
            helper.getAvailableEquipmentForSlot(char, runeSlotInfo)
                .filter { it.source == "inventory" || it.source == "bank" || it.source == "equipped" }
                // Cross-character claim check for bank runes
                .filter { option ->
                    if (option.source != "bank") return@filter true
                    val bankQty = helper.bankState.getQuantity(option.item.code)
                    val claimed = claimedGear.getOrDefault(option.item.code, 0)
                    (bankQty - claimed) > 0
                }
        } catch (_: Exception) { return null }

        if (candidates.isEmpty()) return null

        val tankOrder = listOf(
            RuneCategory.SELF_HEAL,   // healing_rune — self-sustain; tank must survive
            RuneCategory.DEFENSIVE,   // defensive rune — survivability before damage
            RuneCategory.DAMAGE,      // damage rune — lower priority for tank
            RuneCategory.AURA_HEAL,   // heals others not self — wrong for tank
            RuneCategory.OTHER
        )
        val supportOrder = listOf(
            RuneCategory.AURA_HEAL,   // healing_aura_rune — heals allies; correct for support
            RuneCategory.DAMAGE,
            RuneCategory.DEFENSIVE,
            RuneCategory.SELF_HEAL,   // self-heal is a fallback; support should heal team
            RuneCategory.OTHER
        )
        val order = if (role == Role.TANK) tankOrder else supportOrder

        val byCategory = candidates.groupBy { classifyRune(it.item) }

        for (category in order) {
            val pool = byCategory[category] ?: continue
            val best = pool.maxByOrNull { scoreItem(it.item, "rune", bossDmg, bossRes, role) }
            if (best != null) return best
        }
        return null
    }
}
