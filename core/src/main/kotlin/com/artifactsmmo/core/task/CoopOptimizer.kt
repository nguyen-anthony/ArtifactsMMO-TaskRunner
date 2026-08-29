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
        val targetUtilities: Map<String, Pair<String, Int>>
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
        val gearPicks = mutableMapOf<String, GearPickResult>()
        for (name in participantNames) {
            val role = if (name == tankName) Role.TANK else Role.SUPPORT
            println("[$name] CoopOptimizer: picking best gear (heuristic, role=$role) vs $monsterCode")
            gearPicks[name] = try {
                pickBestGearHeuristic(chars[name]!!, monster, role)
            } catch (e: Exception) {
                println("[$name] CoopOptimizer: heuristic gear pick failed: ${e.message}")
                GearPickResult(emptyList(), emptyMap())
            }
        }

        // 5. Baseline coop sim with heuristic gear locked in (no utilities yet)
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
        val monsterEffects = monster.effects.map { it.code }.toSet()
        val committedUtilities = mutableMapOf<String, MutableMap<String, Pair<String, Int>>>()
            .apply { participantNames.forEach { put(it, mutableMapOf()) } }

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

                var bestCandidate: Pair<Item, Int>? = null
                var bestScore: CoopScore = simToCoopScore(
                    buildCoopLoadoutsWith(participantNames, chars, gearPicks, committedUtilities, name, utilitySlot, null),
                    monsterCode
                )

                for (categoryPool in candidateGroups) {
                    if (categoryPool.isEmpty()) continue
                    for (candidate in categoryPool) {
                        val qty = getOwnedQuantity(char, candidate.code).coerceAtMost(UTILITY_MAX_QUANTITY)
                        if (qty <= 0) continue

                        val loadouts = buildCoopLoadoutsWith(
                            participantNames, chars, gearPicks, committedUtilities,
                            name, utilitySlot, candidate.code to qty
                        )
                        val score = simToCoopScore(loadouts, monsterCode)
                        if (score > bestScore) {
                            bestCandidate = candidate to qty
                            bestScore = score
                        }
                    }
                    if (bestCandidate != null) break
                }

                if (bestCandidate != null) {
                    val (item, qty) = bestCandidate
                    committedUtilities[name]!![utilitySlot] = item.code to qty
                    println("[$name] CoopOptimizer: $utilitySlot -> ${item.code} x$qty (role=$role, win rate ${(bestScore.winRate * 100).toInt()}%)")
                } else {
                    println("[$name] CoopOptimizer: $utilitySlot -> no improvement found")
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

        // 8. Build per-participant plans
        val plans = participantNames.map { name ->
            val role = if (name == tankName) Role.TANK else Role.SUPPORT
            val char = chars[name]!!
            val gear = gearPicks[name]!!
            val utilities = committedUtilities[name]!!.toMap()
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
                targetUtilities = utilities
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
        role: Role
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

            // Emit equip action if a change is needed
            if (bestOption.item.code != currentEquipped && bestOption.source != "equipped") {
                equipActions.add(ActionHelper.EquipAction(
                    slot = slotInfo.slot,
                    itemCode = bestOption.item.code,
                    source = bestOption.source
                ))
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
            Role.SUPPORT -> Triple(1.4, 0.6, 0.5)   // Prioritize damage
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
            add(UtilityCategory.BOOST_RES_FIRE)
            add(UtilityCategory.BOOST_RES_EARTH)
            add(UtilityCategory.BOOST_RES_WATER)
            add(UtilityCategory.BOOST_RES_AIR)
            add(UtilityCategory.RESTORE)
            add(UtilityCategory.BOOST_HP)
            if (hasPoison) add(UtilityCategory.ANTIPOISON)
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
            if (this.winRate > other.winRate + 0.01) return 1
            if (this.winRate < other.winRate - 0.01) return -1
            if (this.avgWinTurns < other.avgWinTurns - 1.0) return 1
            if (this.avgWinTurns > other.avgWinTurns + 1.0) return -1
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
}
