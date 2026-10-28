package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.CombatSimulationData
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Monster

/**
 * Cooperative fight optimizer for boss fights.
 *
 * Strategy:
 *  - **Gear**: per-character whole-loadout HEURISTIC search ([GearOptimizer.searchCoopLoadout]
 *    / [GearOptimizer.evaluateLoadout]) — solo simulation against a boss is useless (one
 *    character loses 100% of the time). Teams are then ranked by [teamGearScore] (the boss's
 *    predicted target — highest threat, ties → lowest max HP — outlasts the fight with its
 *    healing and allies' healing auras, given the team's summed damage net of boss healing;
 *    then total damage) and the top
 *    finalists are validated with the multi-character sim. The team's CURRENT gear is always a
 *    finalist. Finalists are simulated without potions first; if none wins without them, each
 *    is re-simulated with seed utilities (currently equipped potions, else role priority) and
 *    judged by its better result, so gear that only wins with potions is not thrown away.
 *  - **Utilities**: none when the chosen team already wins ≥ [HIGH_WIN_RATE_THRESHOLD].
 *    Otherwise a coop-sim-judged pass, tank first: tank self-heal/survival, supports splash
 *    heal + damage; only potions relevant to the boss's attack elements / the planned
 *    weapon's elements are considered. Losing teams are ranked by the boss HP they leave.
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

    /**
     * Utility candidate category — used for role-based priority ordering. Elemental
     * resistance/damage potions share one category each (an all-element potion such as
     * diabolic_elixir is a single item), ranked within it by relevance to this fight.
     */
    private enum class UtilityCategory {
        BOOST_RES,
        RESTORE, BOOST_HP, ANTIPOISON,
        SPLASH_RESTORE,
        BOOST_DMG, BOOST_DMG_ELEMENT,
        BOOST_CRITICAL_STRIKE,
        OTHER
    }

    private data class CoopGearCandidate(
        val pick: GearPickResult,
        val bankDemand: Map<String, Int>,
        val heuristicScore: Double,
        val projectedThreat: Int,
        val stableKey: String,
        val expectedDamage: Double = 0.0,
        val effectiveHp: Double = 0.0,
        val maxHp: Int = 0,
        val selfHealPerTurn: Double = 0.0,
        val allyHealPercentPerTurn: Double = 0.0
    ) {
        fun toTeamCandidate(name: String) = TeamLoadoutCandidate(
            characterName = name, value = pick, bankDemand = bankDemand,
            heuristicScore = heuristicScore, threat = projectedThreat, stableKey = stableKey,
            expectedDamage = expectedDamage, effectiveHp = effectiveHp, maxHp = maxHp,
            selfHealPerTurn = selfHealPerTurn, allyHealPercentPerTurn = allyHealPercentPerTurn
        )
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
        val avgWinTurns: Double,
        /** Bank quantities this team's plans will withdraw (gear, utilities, reserves, food, keys). */
        val bankDemand: Map<String, Int> = emptyMap()
    ) {
        /** False = not beatable with what the team currently owns (gear + potions). */
        val winnable: Boolean get() = optimizedWinRate > 0.0
    }

    companion object {
        const val COOP_SIM_ITERATIONS = 100
        const val UTILITY_MAX_QUANTITY = 100
        /**
         * Gear-only team win rate at or above which NO utility potions are planned (same
         * rule as solo [GearOptimizer.NO_UTILITY_MIN_WINRATE]); the potion pass also stops
         * adding potions once the running team win rate reaches it.
         */
        const val HIGH_WIN_RATE_THRESHOLD = 0.95
        /**
         * Below this win rate every candidate still loses, so the sim can't compare them;
         * potions are assigned by role priority (relevance-filtered) instead. Potions can
         * still swing an otherwise-unwinnable fight into winnable.
         */
        const val LOW_WIN_RATE_THRESHOLD = 0.05
        /**
         * Sim-guided utility pick: a higher-role-priority potion is preferred over the
         * single best-simulating one if its win rate is within this margin.
         */
        const val UTILITY_PRIORITY_WINRATE_MARGIN = 0.02
        /** Offset that ranks every team whose tank survives above every team whose tank doesn't. */
        const val SURVIVING_TEAM_BONUS = 1_000_000.0
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
        const val TEAM_GEAR_CANDIDATES_PER_CHARACTER = 6
        const val TEAM_GEAR_API_FINALISTS = 6
        /** Boss-HP-left difference (fraction of max) that counts as a real gap between two losing teams. */
        const val LOSS_BOSS_HP_EPSILON = 0.02

        /** Boss threat tie → lowest max HP; berserk is assumed active for this share of the fight (its last 25% HP). */
        const val BERSERK_FIGHT_SHARE = 0.25
        /** Healing never cuts incoming damage below this fraction (keeps survival turns finite and conservative). */
        const val MIN_NET_INCOMING_FRACTION = 0.1

        /** Team damage per round minus the boss's own `healing` (value% of its HP every 3 of its turns). */
        internal fun netTeamDamage(teamDamage: Double, monster: Monster): Double {
            val bossHealPerTurn = monster.effects.filter { it.code == "healing" }.sumOf { it.value } / 100.0 / 3.0 * monster.hp
            return (teamDamage - bossHealPerTurn).coerceAtLeast(teamDamage * MIN_NET_INCOMING_FRACTION)
        }

        /**
         * Rounds [target] survives as the boss's target: incoming damage per round (raised by the
         * boss's berserker rage) minus the target's own healing and every OTHER member's healing
         * aura (auras don't heal the caster). Falls back to the plain effective HP when max HP is unknown.
         */
        internal fun <T> sustainedSurvivalTurns(
            target: TeamLoadoutCandidate<T>,
            team: Collection<TeamLoadoutCandidate<T>>,
            monster: Monster
        ): Double {
            if (target.maxHp <= 0 || target.effectiveHp <= 0.0) return target.effectiveHp
            val berserk = monster.effects.filter { it.code == "berserker_rage" }.sumOf { it.value } / 100.0
            val incoming = target.maxHp / target.effectiveHp * (1.0 + berserk * BERSERK_FIGHT_SHARE)
            val auraPercent = team.filter { it.characterName != target.characterName }.sumOf { it.allyHealPercentPerTurn }
            val heal = target.selfHealPerTurn + auraPercent / 100.0 * target.maxHp
            return target.maxHp / (incoming - heal).coerceAtLeast(incoming * MIN_NET_INCOMING_FRACTION)
        }

        /**
         * Which gear finalist to fight with, and whether it should start from its seed
         * utilities. [gearOnly] = each finalist's no-potion score; [withUtilities] = the same
         * gear with seed utilities (null = not simulated).
         *
         * If any team wins ≥ [HIGH_WIN_RATE_THRESHOLD] without potions, the best such team wins
         * (no potions are wasted). Otherwise teams are compared by their better score (with or
         * without potions), so gear that only wins with potions isn't discarded because every
         * team loses without them.
         */
        internal fun chooseGearFinalist(gearOnly: List<CoopScore>, withUtilities: List<CoopScore?>): Pair<Int, Boolean>? {
            if (gearOnly.isEmpty()) return null
            val noPotionWinners = gearOnly.indices.filter { gearOnly[it].winRate >= HIGH_WIN_RATE_THRESHOLD }
            if (noPotionWinners.isNotEmpty()) {
                return noPotionWinners.reduce { best, i -> if (gearOnly[i] > gearOnly[best]) i else best } to false
            }
            fun effective(i: Int): Pair<CoopScore, Boolean> {
                val seeded = withUtilities.getOrNull(i)
                return if (seeded != null && seeded > gearOnly[i]) seeded to true else gearOnly[i] to false
            }
            val best = gearOnly.indices.reduce { best, i -> if (effective(i).first > effective(best).first) i else best }
            return best to effective(best).second
        }

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

        private val ELEMENTS = listOf("fire", "earth", "water", "air")

        /**
         * Team gear ranking: teams whose tank outlasts the fight (tank effective HP ≥ boss HP
         * / summed team damage) rank above all others and are ordered by total damage; the
         * rest are ordered by how close the tank gets to outlasting it.
         */
        internal fun teamGearScore(tankEffectiveHp: Double, teamDamage: Double, monsterHp: Double): Double {
            val killTurns = monsterHp / teamDamage.coerceAtLeast(1.0)
            return if (tankEffectiveHp >= killTurns) SURVIVING_TEAM_BONUS + teamDamage
                   else tankEffectiveHp / killTurns.coerceAtLeast(1e-9) * 1000.0
        }

        /**
         * How much [item] helps in this fight, or null if it can't help at all:
         *  - resistance potions: Σ boost × the monster's attack in that element,
         *  - elemental damage potions: Σ boost × the planned weapon's attack in that element,
         *  - anything else: the sum of its utility effect values.
         */
        internal fun utilityRelevance(item: Item, monsterAttack: Map<String, Int>, weaponAttack: Map<String, Int>): Double? {
            val effects = item.effects.associate { it.code to it.value }
            val res = ELEMENTS.sumOf { (effects["boost_res_$it"] ?: 0) * (monsterAttack[it] ?: 0).toDouble() }
            val elementDmg = ELEMENTS.sumOf { (effects["boost_dmg_$it"] ?: 0) * (weaponAttack[it] ?: 0).toDouble() }
            val hasRes = ELEMENTS.any { "boost_res_$it" in effects }
            val hasElementDmg = ELEMENTS.any { "boost_dmg_$it" in effects }
            if ((hasRes || hasElementDmg) && res + elementDmg <= 0.0) return null
            val other = item.effects.filter { it.code in UTILITY_EFFECT_CODES && !it.code.startsWith("boost_res_") &&
                ELEMENTS.none { e -> it.code == "boost_dmg_$e" } }.sumOf { it.value }.toDouble()
            return res + elementDmg + other
        }
    }

    // ── Public API ─────────────────────────────────────────────────────

    /**
     * [bankReserved] = bank quantities already promised to other parties planned before this
     * one (e.g. a second party for the same boss event). They are treated as unavailable for
     * gear, utilities, reserve potions, food and keys, so the parties' plans fit the bank together.
     * Bank items reserved by other characters' planned work ([BankState.reservations]) are
     * added on top.
     */
    suspend fun optimizeForBossFight(
        participantNames: List<String>,
        monsterCode: String,
        tankOverride: String? = null,
        plannedReserved: Map<String, Int> = emptyMap()
    ): CoopOptimizationResult {
        require(participantNames.isNotEmpty()) { "Must have at least one participant" }
        require(participantNames.size <= 3) { "Coop fight supports at most 3 characters" }

        // Utility planning reserves shared bank stock across the entire team. Reconcile the
        // bank mirror once before calculating those reservations so a stale snapshot cannot
        // select a potion that has already been withdrawn by another active runner.
        try {
            helper.bankState.refresh()
        } catch (e: Exception) {
            println("CoopOptimizer: bank refresh before utility planning failed: ${e.message}")
        }
        val bankReserved = plannedReserved.toMutableMap().apply {
            val raw = helper.bankState.snapshot.value
            val visible = helper.bankState.availableFor(participantNames.toSet())
            raw.forEach { (code, qty) ->
                val claimedByOthers = qty - (visible[code] ?: 0)
                if (claimedByOthers > 0) merge(code, claimedByOthers, Int::plus)
            }
        }.toMap()

        // 1. Fetch fresh Character objects
        val stableParticipantNames = participantNames.distinct().sorted()
        val chars = stableParticipantNames.associateWith { name ->
            client.characters.getCharacter(name)
        }

        // 2. Fetch monster once — needed for all heuristic scoring
        val monster = try { helper.contentCache.getMonster(monsterCode) }
                      catch (e: Exception) {
                          println("CoopOptimizer: could not fetch monster $monsterCode: ${e.message}")
                          return emptyResult(monsterCode, participantNames, chars)
                      }

        // 3. Tank = explicit override, else the initiator (first entry of the ORIGINAL,
        //    caller-supplied order — group creation always puts the initiator first).
        val tankName = tankOverride?.takeIf { it in participantNames } ?: participantNames.first()
        println("CoopOptimizer: tank = $tankName" + (if (tankOverride == null) " (initiator, default)" else " (override)"))

        // 4. Generate bounded role-aware candidates per character, then allocate one candidate
        // per participant under a frozen shared-bank quantity snapshot. This replaces implicit
        // tank-first claiming with explicit team-level feasibility and deterministic ranking.
        val bankSnapshot = helper.bankState.snapshot.value
            .mapValues { (code, qty) -> (qty - bankReserved.getOrDefault(code, 0)).coerceAtLeast(0) }
        val candidateSets = mutableMapOf<String, List<TeamLoadoutCandidate<GearPickResult>>>()
        for (name in stableParticipantNames) {
            val role = if (name == tankName) Role.TANK else Role.SUPPORT
            val generated = generateCoopGearCandidates(chars.getValue(name), monster, role, stableParticipantNames.size)
            candidateSets[name] = generated.map { it.toTeamCandidate(name) }
        }

        for ((name, list) in candidateSets) {
            println("CoopOptimizer: $name — ${list.size} gear candidate(s), heuristic ${list.minOfOrNull { it.heuristicScore }?.let { "%.1f".format(it) }}..${list.maxOfOrNull { it.heuristicScore }?.let { "%.1f".format(it) }}")
        }

        val participantOrder = listOf(tankName) + stableParticipantNames.filter { it != tankName }

        // Per-character candidates above are generated independently, each picking its own
        // best gear with no awareness of what the others also want — so their "best" lists
        // can all lean on the same scarce bank items, leaving no combination that fits the
        // shared bank at once (enumeration then returns empty and everyone got stuck with
        // current gear, however unsuitable it is for a fight — see incident: 3 characters all
        // wanting the same trophy left NO fitting team, when each could have gotten SOME
        // upgrade). A shared-claim greedy pass fixes that: it builds ONE team, tank first,
        // where each character's pick is filtered against what predecessors already claimed
        // (via pickBestGearHeuristic's existing claimedGear plumbing) — so it always fits by
        // construction, even if a scarce item goes to whoever needed it most (the tank).
        //
        // The team is judged on whoever the boss will actually attack (highest threat, ties ->
        // lowest max HP ? not necessarily the designated tank), with that character's healing
        // (own rune/lifesteal + allies' healing auras) and the boss's own healing/berserk.
        val teamScore: (Map<String, TeamLoadoutCandidate<GearPickResult>>) -> Double = { team ->
            val target = TeamLoadoutAllocator.predictedTarget(team)?.let { team[it] }
            teamGearScore(
                tankEffectiveHp = target?.let { sustainedSurvivalTurns(it, team.values, monster) } ?: 0.0,
                teamDamage = netTeamDamage(team.values.sumOf { it.expectedDamage }, monster),
                monsterHp = monster.hp.toDouble()
            )
        }
        val greedyTeam = sharedClaimGreedyTeam(tankName, participantOrder, chars, monster, bankReserved, teamScore)
        val enumerated = TeamLoadoutAllocator.enumerate(
            participantOrder = participantOrder,
            candidates = candidateSets,
            bankQuantities = bankSnapshot,
            tankName = tankName,
            teamScore = teamScore
        )
        if (enumerated.isEmpty()) {
            val demandTotals = mutableMapOf<String, Int>()
            for (list in candidateSets.values) {
                list.maxByOrNull { it.heuristicScore }?.bankDemand?.forEach { (code, qty) -> demandTotals[code] = (demandTotals[code] ?: 0) + qty }
            }
            val contested = demandTotals.filter { (code, qty) -> qty > bankSnapshot.getOrDefault(code, 0) }
            println("CoopOptimizer: independently-best gear doesn't fit the shared bank" +
                (if (contested.isNotEmpty()) " (contested: ${contested.entries.joinToString { "${it.value}x ${it.key} needed, ${bankSnapshot.getOrDefault(it.key, 0)} in stock" }})" else "") +
                " — using the shared-claim greedy team instead")
        }
        // Greedy first: it's guaranteed feasible and prioritizes the tank, so it anchors the
        // finalist simulation even when enumeration found nothing (or something worse).
        // Never simulate a team where a support out-threatens the tank if a valid one exists:
        // the boss would target the support, so the plan's roles (and win rate) don't hold.
        val teamCandidates = TeamLoadoutAllocator.preferThreatValid((listOf(greedyTeam) + enumerated).distinctBy { it.stableKey })
        if (teamCandidates.firstOrNull()?.threatViolation?.let { it > 0 } == true) {
            println("CoopOptimizer: WARNING — every gear team has a support out-threatening $tankName (or tied on threat " +
                "with less max HP; smallest gap ${teamCandidates.first().threatViolation}); the boss may target a support")
        }
        // The team's CURRENT gear is always a finalist: a loadout the player already wins with
        // must never lose to unvalidated heuristic picks just because the heuristic (damage-first
        // supports, tank-only survival) under-ranks it and prunes it before simulation.
        val currentTeam = currentGearTeam(tankName, participantOrder, chars, monster, teamScore)
        fun gearKey(team: TeamLoadoutSelection<GearPickResult>) =
            stableParticipantNames.joinToString("|") { team.byCharacter.getValue(it).stableKey }
        val currentKey = gearKey(currentTeam)
        val finalists = (teamCandidates.take(TEAM_GEAR_API_FINALISTS) + currentTeam).distinctBy { gearKey(it) }

        val monsterEffects = monster.effects.map { it.code }.toSet()
        val monsterAttack = mapOf(
            "fire" to monster.attackFire, "earth" to monster.attackEarth,
            "water" to monster.attackWater, "air" to monster.attackAir
        )
        fun picksOf(team: TeamLoadoutSelection<GearPickResult>): Map<String, GearPickResult> =
            stableParticipantNames.associateWith { team.byCharacter.getValue(it).value }

        // Seed utilities for judging a gear team WITH potions: each character keeps the potions
        // it has equipped now (if still in stock), empty slots get the highest role-priority
        // relevant owned potion. Shared bank stock is reserved across the team as it's assigned.
        class UtilitySeed(
            val utilities: Map<String, MutableMap<String, Pair<String, Int>>>,
            val committedBank: MutableMap<String, Int>,
            val score: CoopScore
        )
        suspend fun seedUtilities(team: TeamLoadoutSelection<GearPickResult>): Pair<Map<String, MutableMap<String, Pair<String, Int>>>, MutableMap<String, Int>> {
            val committedBank = bankReserved.toMutableMap()
            val seeds = stableParticipantNames.associateWith { mutableMapOf<String, Pair<String, Int>>() }
            // Potion roles follow whoever the boss will actually attack with THIS gear.
            val target = TeamLoadoutAllocator.predictedTarget(team.byCharacter) ?: tankName
            for (name in listOf(target) + stableParticipantNames.filter { it != target }) {
                val char = chars.getValue(name)
                val role = if (name == target) Role.TANK else Role.SUPPORT
                val seed = seeds.getValue(name)
                for (slot in listOf("utility1", "utility2")) {
                    val taken = seed.values.map { it.first }.toSet()
                    val equipped = if (slot == "utility1") char.utility1Slot else char.utility2Slot
                    var pick: Pair<String, Int>? = null
                    if (equipped.isNotEmpty() && equipped !in taken) {
                        val qty = getAvailableUtilityQuantity(char, equipped, committedBank)
                        if (qty > 0) pick = equipped to qty
                    }
                    if (pick == null) {
                        val weaponAttack = weaponAttackFor(char, team.byCharacter.getValue(name).value.targetLoadout)
                        pick = orderedCandidatesByRole(char, role, monsterEffects, monsterAttack, weaponAttack, taken)
                            .flatten()
                            .firstNotNullOfOrNull { item ->
                                getAvailableUtilityQuantity(char, item.code, committedBank).takeIf { it > 0 }?.let { item.code to it }
                            }
                    }
                    if (pick != null) {
                        seed[slot] = pick
                        reserveBankUtilityQuantity(char, pick.first, pick.second, committedBank)
                    }
                }
            }
            return seeds to committedBank
        }

        // 5. Simulate the finalists without potions first (a team that wins without them wastes
        //    nothing); if none does, simulate each again with seed utilities, so gear that only
        //    wins WITH potions isn't discarded because every team loses without them.
        class Finalist(val team: TeamLoadoutSelection<GearPickResult>, val label: String, val sim: CombatSimulationData, val score: CoopScore)
        val simulated = mutableListOf<Finalist>()
        for ((rank, team) in finalists.withIndex()) {
            val label = if (gearKey(team) == currentKey) "current gear" else "gear finalist #$rank"
            val loadouts = buildFinalCoopLoadouts(stableParticipantNames, chars, picksOf(team), emptyMap())
            val sim = try {
                helper.simulateCoopFight(monsterCode, loadouts, COOP_SIM_ITERATIONS)
            } catch (e: Exception) {
                println("CoopOptimizer: $label simulation failed: ${e.message}")
                continue
            }
            val score = simToCoopScore(loadouts, monsterCode, precomputed = sim)
            println("CoopOptimizer: $label — threatViolation=${team.threatViolation} " +
                "heuristic=${"%.1f".format(team.heuristicScore)} winRate=${(sim.winrate * 100).toInt()}% (no potions)" +
                (if (score.lossBossHpRemaining > 0) ", boss HP left in losses ${(score.lossBossHpRemaining * 100).toInt()}%" else ""))
            simulated += Finalist(team, label, sim, score)
        }
        val anyWinsWithoutPotions = simulated.any { it.score.winRate >= HIGH_WIN_RATE_THRESHOLD }
        val seeded: List<UtilitySeed?> = simulated.map { finalist ->
            if (anyWinsWithoutPotions) return@map null
            val (utilities, committedBank) = seedUtilities(finalist.team)
            if (utilities.values.all { it.isEmpty() }) return@map null
            val loadouts = buildFinalCoopLoadouts(stableParticipantNames, chars, picksOf(finalist.team), utilities)
            val score = simToCoopScore(loadouts, monsterCode)
            println("CoopOptimizer: ${finalist.label} with utilities " +
                utilities.entries.joinToString("; ") { (n, u) -> "$n=${u.values.joinToString(",") { it.first }.ifEmpty { "-" }}" } +
                " — winRate=${(score.winRate * 100).toInt()}%" +
                (if (score.lossBossHpRemaining > 0) ", boss HP left in losses ${(score.lossBossHpRemaining * 100).toInt()}%" else ""))
            UtilitySeed(utilities, committedBank, score)
        }
        val choice = chooseGearFinalist(simulated.map { it.score }, seeded.map { it?.score })
        val selectedTeam = choice?.let { simulated[it.first].team } ?: teamCandidates.firstOrNull()
        var baselineSim: CombatSimulationData? = choice?.let { simulated[it.first].sim }
        val bestScore: CoopScore? = choice?.let { simulated[it.first].score }
        val startingSeed: UtilitySeed? = choice?.takeIf { it.second }?.let { seeded[it.first] }
        if (choice != null) {
            println("CoopOptimizer: selected ${simulated[choice.first].label}" +
                (if (startingSeed != null) " (judged with utilities, ${(startingSeed.score.winRate * 100).toInt()}%)" else ""))
        }
        // The boss attacks the highest-threat member (ties -> lowest max HP), whoever was
        // designated tank; potion roles follow the real target.
        val bossTarget = selectedTeam?.let { TeamLoadoutAllocator.predictedTarget(it.byCharacter) } ?: tankName
        if (bossTarget != tankName) {
            val t = selectedTeam?.byCharacter?.get(bossTarget)
            val k = selectedTeam?.byCharacter?.get(tankName)
            println("CoopOptimizer: WARNING ? with this gear the boss targets $bossTarget (threat ${t?.threat}, max HP ${t?.maxHp}), " +
                "not $tankName (threat ${k?.threat}, max HP ${k?.maxHp}); potions are planned with $bossTarget as the target")
        }

        val gearPicks = mutableMapOf<String, GearPickResult>()
        if (selectedTeam != null) {
            for (name in stableParticipantNames) {
                gearPicks[name] = selectedTeam.byCharacter.getValue(name).value
            }
        } else {
            for (name in stableParticipantNames) {
                gearPicks[name] = currentGearPick(chars.getValue(name))
            }
        }
        if (baselineSim == null) {
            val fallbackLoadouts = stableParticipantNames.map { name ->
                ActionHelper.CoopParticipantLoadout(chars.getValue(name), gearPicks.getValue(name).targetLoadout)
            }
            baselineSim = try {
                helper.simulateCoopFight(monsterCode, fallbackLoadouts, COOP_SIM_ITERATIONS)
            } catch (_: Exception) { null }
        }
        val baselineWinRate = baselineSim?.winrate ?: 0.0
        println("CoopOptimizer: baseline (gear only) coop win rate = ${(baselineWinRate * 100).toInt()}%")

        // 6. Utility pass — tank first, then supports. Coop sim is essential here: Splash
        //    Restore heals allies, so only a team sim can judge it.
        //
        //    - Team win rate (gear + seed utilities, if the gear was judged with them) >=
        //      HIGH_WIN_RATE_THRESHOLD: no further potions — they would only be consumed for nothing.
        //    - Otherwise sim-guided per slot: every relevant owned potion is simulated in place of
        //      the slot's current pick; the highest role-priority one within
        //      UTILITY_PRIORITY_WINRATE_MARGIN of the best win rate (that beats the current pick)
        //      is taken. When every option still loses (best < LOW_WIN_RATE_THRESHOLD) the best
        //      score wins instead — [CoopScore] ranks losing teams by the boss HP they leave.
        //    - Nothing simulates better, the slot is empty and the team still loses: assign the
        //      highest role-priority relevant potion (potions may only help together).
        //
        //    Relevance (see [utilityRelevance]): resistance potions only for elements the boss
        //    attacks with, elemental damage potions only for elements the planned weapon has.
        //
        //    Committed quantity tracking: each time a potion is committed to a character, its
        //    contribution is subtracted from the available pool so that subsequent characters
        //    see the real remaining stock.
        val committedUtilities = mutableMapOf<String, MutableMap<String, Pair<String, Int>>>()
            .apply {
                stableParticipantNames.forEach { name ->
                    put(name, startingSeed?.utilities?.get(name)?.toMutableMap() ?: mutableMapOf())
                }
            }
        // Shared-bank quantities reserved by previous characters (and by earlier parties via
        // [bankReserved]). Personal inventory and equipped stacks remain available only to
        // their owning character. Reserve potions, food and keys are committed here too.
        val committedBankUtilities = startingSeed?.committedBank?.toMutableMap() ?: bankReserved.toMutableMap()

        // Team score with the utilities committed so far (gear-only or seeded to start).
        var runningScore: CoopScore? = startingSeed?.score ?: bestScore
        val startWinRate = runningScore?.winRate ?: baselineWinRate
        val usePotions = startWinRate < HIGH_WIN_RATE_THRESHOLD
        if (!usePotions) {
            println(if (startingSeed == null) "CoopOptimizer: gear-only win rate ${(baselineWinRate * 100).toInt()}% — no utility potions needed"
                    else "CoopOptimizer: team wins ${(startWinRate * 100).toInt()}% with its seed utilities — keeping them")
        }

        for (name in listOf(bossTarget) + stableParticipantNames.filter { it != bossTarget }) {
            if (!usePotions) break
            val role = if (name == bossTarget) Role.TANK else Role.SUPPORT
            val char = chars[name]!!
            val weaponAttack = weaponAttackFor(char, gearPicks[name]?.targetLoadout.orEmpty())
            val slots = committedUtilities.getValue(name)

            for (utilitySlot in listOf("utility1", "utility2")) {
                if ((runningScore?.winRate ?: baselineWinRate) >= HIGH_WIN_RATE_THRESHOLD) break
                val alreadyPickedForOtherSlot = slots.filterKeys { it != utilitySlot }.values.map { it.first }.toSet()

                val candidateGroups = orderedCandidatesByRole(char, role, monsterEffects, monsterAttack, weaponAttack, alreadyPickedForOtherSlot)
                if (candidateGroups.all { it.isEmpty() }) {
                    println("[$name] CoopOptimizer: no relevant owned utility candidates for $utilitySlot")
                    continue
                }

                // Free this slot's current (seed) pick so its stock can be re-tried or replaced.
                val previous = slots[utilitySlot]
                if (previous != null) releaseBankUtilityQuantity(char, previous.first, previous.second, committedBankUtilities)
                val currentScore = runningScore ?: simToCoopScore(
                    buildFinalCoopLoadouts(stableParticipantNames, chars, gearPicks, committedUtilities),
                    monsterCode
                )

                data class Trial(val priority: Int, val item: Item, val quantity: Int, val score: CoopScore)
                val trials = mutableListOf<Trial>()
                for ((priority, categoryPool) in candidateGroups.withIndex()) {
                    for (candidate in categoryPool) {
                        if (candidate.code == previous?.first) continue
                        val available = getAvailableUtilityQuantity(char, candidate.code, committedBankUtilities)
                        if (available <= 0) continue
                        val loadouts = buildCoopLoadoutsWith(
                            stableParticipantNames, chars, gearPicks, committedUtilities,
                            name, utilitySlot, candidate.code to available
                        )
                        val score = simToCoopScore(loadouts, monsterCode)
                        if (score > currentScore) trials += Trial(priority, candidate, available, score)
                    }
                }
                val bestWinRate = trials.maxOfOrNull { it.score.winRate }
                val chosen = when {
                    bestWinRate == null -> null
                    bestWinRate < LOW_WIN_RATE_THRESHOLD -> trials.maxWith(compareBy { it.score })
                    else -> trials
                        .filter { it.score.winRate >= bestWinRate - UTILITY_PRIORITY_WINRATE_MARGIN }
                        .sortedWith(compareBy<Trial> { it.priority }.thenByDescending { it.score })
                        .first()
                }

                if (chosen != null) {
                    slots[utilitySlot] = chosen.item.code to chosen.quantity
                    reserveBankUtilityQuantity(char, chosen.item.code, chosen.quantity, committedBankUtilities)
                    runningScore = chosen.score
                    println("[$name] CoopOptimizer: $utilitySlot -> ${chosen.item.code} x${chosen.quantity} (role=$role, team win rate ${(chosen.score.winRate * 100).toInt()}%)" +
                        (if (previous != null) ", replaces ${previous.first}" else ""))
                    continue
                }
                if (previous != null) {
                    reserveBankUtilityQuantity(char, previous.first, previous.second, committedBankUtilities)
                    runningScore = currentScore
                    println("[$name] CoopOptimizer: $utilitySlot keeps ${previous.first} x${previous.second} (no better option)")
                    continue
                }
                if (currentScore.winRate < LOW_WIN_RATE_THRESHOLD) {
                    val fallback = candidateGroups.flatten().firstNotNullOfOrNull { item ->
                        getAvailableUtilityQuantity(char, item.code, committedBankUtilities).takeIf { it > 0 }?.let { item to it }
                    }
                    if (fallback != null) {
                        val (item, qty) = fallback
                        slots[utilitySlot] = item.code to qty
                        reserveBankUtilityQuantity(char, item.code, qty, committedBankUtilities)
                        runningScore = null
                        println("[$name] CoopOptimizer: $utilitySlot -> ${item.code} x$qty (role=$role, priority fallback, team still losing)")
                    } else {
                        runningScore = currentScore
                        println("[$name] CoopOptimizer: $utilitySlot -> no available stock for any candidate")
                    }
                    continue
                }
                runningScore = currentScore
                println("[$name] CoopOptimizer: $utilitySlot -> no improvement found")
            }
        }

        // 7. Final coop sim to record optimized win rate + turns
        val finalLoadouts = buildFinalCoopLoadouts(stableParticipantNames, chars, gearPicks, committedUtilities)
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
            helper.contentCache.findNearestAnyLayer(chars[stableParticipantNames.first()]!!, "monster", monsterCode)
        } catch (_: Exception) { null }

        // Use the first character's position as a representative starting point for
        // transition cost detection (all participants start from overworld).
        val transitionCosts: Map<String, Int> = if (bossTargetMap != null) {
            try {
                helper.getTransitionCosts(chars[stableParticipantNames.first()]!!, bossTargetMap)
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
            for (name in stableParticipantNames) {
                val charGold = chars[name]!!.gold
                if (charGold < roundTripGold) {
                    println("[$name] CoopOptimizer: WARNING — gold transition cost ${goldCost}g/entry ($roundTripGold for round-trip), character has ${charGold}g")
                } else {
                    println("[$name] CoopOptimizer: gold transition cost ${goldCost}g — character has ${charGold}g (sufficient)")
                }
            }
        }

        val plans = stableParticipantNames.map { name ->
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
                        .let { it - (committedBankUtilities[code] ?: 0) }
                        .coerceAtLeast(0)
                    val reserveQty = perPotion.coerceAtMost(bankAvailable)
                    if (reserveQty > 0) {
                        reservePotions[code] = reserveQty
                        committedBankUtilities[code] = committedBankUtilities.getOrDefault(code, 0) + reserveQty
                        println("[$name] CoopOptimizer: reserve $code x$reserveQty in inventory")
                    }
                }
            }

            // ── Food pre-load calculation ──────────────────────────────────────
            // Withdraw up to FOOD_BUFFER slots of the best available food. Food is consumed
            // out-of-combat between boss fights — carrying it avoids bank trips for healing.
            val foodInfo = try { helper.findBestFoodInBank(char, committedBankUtilities) } catch (_: Exception) { null }
            val foodCode = foodInfo?.first
            val foodQuantity = if (foodInfo != null) {
                val bankQty = foodInfo.third
                minOf(bankQty, FOOD_BUFFER)
            } else 0
            if (foodCode != null && foodQuantity > 0) {
                committedBankUtilities[foodCode] = committedBankUtilities.getOrDefault(foodCode, 0) + foodQuantity
            }
            (transitionCosts.entries + spareKeys.entries).filter { it.key != "gold" }.forEach { (code, qty) ->
                committedBankUtilities[code] = committedBankUtilities.getOrDefault(code, 0) + qty
            }

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

        // This team's own bank draws: everything committed beyond what earlier parties
        // reserved, plus the selected gear's bank-sourced copies.
        val bankDemand = mutableMapOf<String, Int>()
        committedBankUtilities.forEach { (code, qty) ->
            val own = qty - bankReserved.getOrDefault(code, 0)
            if (own > 0) bankDemand[code] = own
        }
        selectedTeam?.bankDemand?.forEach { (code, qty) -> bankDemand[code] = bankDemand.getOrDefault(code, 0) + qty }

        return CoopOptimizationResult(
            monsterCode = monsterCode,
            participants = plans,
            tankName = tankName,
            baselineWinRate = baselineWinRate,
            optimizedWinRate = optimizedWinRate,
            avgWinTurns = avgWinTurns,
            bankDemand = bankDemand
        )
    }

    // ── Heuristic gear picker ──────────────────────────────────────────

    private data class GearPickResult(
        val equipActions: List<ActionHelper.EquipAction>,
        val targetLoadout: Map<String, String>
    )

    /**
     * Build one gear pick per team member, tank first, where each pick is filtered against
     * what earlier picks already claimed from the bank ([pickBestGearHeuristic]'s
     * `claimedGear` parameter) — so the resulting team ALWAYS fits the shared bank, unlike
     * [generateCoopGearCandidates] + [TeamLoadoutAllocator], which independently rank each
     * character's own best options and can end up with no combination that fits together.
     * Worst case (everything scarce/contested) each character still gets their current gear,
     * since [pickBestGearHeuristic] always offers "keep current" as a fallback option.
     */
    private suspend fun sharedClaimGreedyTeam(
        tankName: String,
        order: List<String>,
        chars: Map<String, Character>,
        monster: Monster,
        bankReserved: Map<String, Int> = emptyMap(),
        teamScore: (Map<String, TeamLoadoutCandidate<GearPickResult>>) -> Double,
    ): TeamLoadoutSelection<GearPickResult> {
        val claimedGear = bankReserved.toMutableMap()
        val byCharacter = linkedMapOf<String, TeamLoadoutCandidate<GearPickResult>>()
        for (name in order) {
            val role = if (name == tankName) Role.TANK else Role.SUPPORT
            val char = chars.getValue(name)
            val pick = pickBestGearHeuristic(
                char, monster, role, claimedGear = claimedGear,
                avoidThreat = role == Role.SUPPORT, seekThreat = role == Role.TANK,
                partySize = order.size
            )
            val candidate = toCoopGearCandidate(char, monster, role, pick, order.size)
            byCharacter[name] = candidate.toTeamCandidate(name)
        }
        return teamSelection(tankName, order, byCharacter, teamScore, "greedy:")
    }

    /** Everyone keeps the gear they have equipped now (no bank demand). */
    private suspend fun currentGearTeam(
        tankName: String,
        order: List<String>,
        chars: Map<String, Character>,
        monster: Monster,
        teamScore: (Map<String, TeamLoadoutCandidate<GearPickResult>>) -> Double,
    ): TeamLoadoutSelection<GearPickResult> {
        val byCharacter = linkedMapOf<String, TeamLoadoutCandidate<GearPickResult>>()
        for (name in order) {
            val role = if (name == tankName) Role.TANK else Role.SUPPORT
            val char = chars.getValue(name)
            val candidate = toCoopGearCandidate(char, monster, role, currentGearPick(char), order.size)
            byCharacter[name] = candidate.toTeamCandidate(name)
        }
        return teamSelection(tankName, order, byCharacter, teamScore, "current:")
    }

    private fun teamSelection(
        tankName: String,
        order: List<String>,
        byCharacter: Map<String, TeamLoadoutCandidate<GearPickResult>>,
        teamScore: (Map<String, TeamLoadoutCandidate<GearPickResult>>) -> Double,
        keyPrefix: String,
    ): TeamLoadoutSelection<GearPickResult> {
        val violation = TeamLoadoutAllocator.threatViolation(byCharacter, tankName)
        val totalDemand = mutableMapOf<String, Int>()
        byCharacter.values.forEach { c -> c.bankDemand.forEach { (code, qty) -> totalDemand[code] = (totalDemand[code] ?: 0) + qty } }
        return TeamLoadoutSelection(
            byCharacter = byCharacter,
            bankDemand = totalDemand,
            heuristicScore = teamScore(byCharacter),
            threatViolation = violation,
            stableKey = keyPrefix + order.joinToString("|") { byCharacter.getValue(it).stableKey }
        )
    }

    private suspend fun generateCoopGearCandidates(
        char: Character,
        monster: Monster,
        role: Role,
        partySize: Int
    ): List<CoopGearCandidate> {
        val current = currentGearPick(char)
        val unrestricted = pickBestGearHeuristic(char, monster, role, partySize = partySize)
        val personalOnly = pickBestGearHeuristic(char, monster, role, allowBank = false, partySize = partySize)
        val candidates = mutableListOf(current, unrestricted, personalOnly)

        // Supports: a variant that avoids threat-carrying gear entirely, so a support never
        // wins the tank's targeting priority just because the globally-best armor happens to
        // carry threat. The tank gets the opposite: a variant that actively seeks threat,
        // in case its best-stat item doesn't.
        candidates += pickBestGearHeuristic(char, monster, role, avoidThreat = role == Role.SUPPORT, seekThreat = role == Role.TANK, partySize = partySize)

        val scarceBankCodes = bankDemandForLoadout(char, unrestricted.targetLoadout).keys.sorted()
        for (code in scarceBankCodes) {
            candidates += pickBestGearHeuristic(
                char = char,
                monster = monster,
                role = role,
                excludedBankCodes = setOf(code),
                partySize = partySize
            )
        }

        val scored = candidates
            .distinctBy { stableGearKey(it.targetLoadout) }
            .map { pick -> toCoopGearCandidate(char, monster, role, pick, partySize) }
            .sortedWith(
                compareByDescending<CoopGearCandidate> { it.heuristicScore }
                    .thenBy { it.bankDemand.values.sum() }
                    .thenBy { it.stableKey }
            )
        // Ranking is damage-first for supports, but any of them can end up the boss's target
        // (threat ties go to the lowest max HP), so the current gear and the most survivable
        // loadout always make the cut alongside the top-ranked ones.
        val currentKey = stableGearKey(current.targetLoadout)
        val mostSurvivable = scored.maxByOrNull { c ->
            c.toTeamCandidate(char.name).let { sustainedSurvivalTurns(it, listOf(it), monster) }
        }
        val mustKeep = listOfNotNull(scored.firstOrNull { it.stableKey == currentKey }, mostSurvivable).distinct()
        return (mustKeep + scored.filter { it !in mustKeep }.take((TEAM_GEAR_CANDIDATES_PER_CHARACTER - mustKeep.size).coerceAtLeast(0)))
            .sortedWith(
                compareByDescending<CoopGearCandidate> { it.heuristicScore }
                    .thenBy { it.bankDemand.values.sum() }
                    .thenBy { it.stableKey }
            )
    }

    private suspend fun toCoopGearCandidate(
        char: Character,
        monster: Monster,
        role: Role,
        pick: GearPickResult,
        partySize: Int
    ): CoopGearCandidate {
        val executablePick = GearPickResult(
            equipActions = actionsForTarget(char, pick.targetLoadout),
            targetLoadout = pick.targetLoadout
        )
        // Whole-loadout stats, so element synergy (weapon × armor dmg bonus) and survival
        // are judged together — same model as the beam search that produced the pick.
        var stats = char
        var threat = char.threat
        for ((slot, code) in pick.targetLoadout) {
            val currentCode = helper.getEquippedInSlot(char, slot)
            if (currentCode == code) continue
            val item = try { helper.contentCache.getItemOrNull(code) } catch (_: Exception) { null } ?: continue
            val currentItem = if (currentCode.isNotEmpty()) {
                try { helper.contentCache.getItemOrNull(currentCode) } catch (_: Exception) { null }
            } else null
            stats = stats.applyItemDelta(currentItem, item)
            threat -= currentItem?.effects?.filter { it.code == "threat" }?.sumOf { it.value } ?: 0
            threat += item.effects.filter { it.code == "threat" }.sumOf { it.value }
        }
        val loadout = GearOptimizer.evaluateLoadout(stats, monster)
        // Sustain from the whole planned loadout (unlisted slots keep the equipped item):
        // `healing` = value% max HP every 3 own turns, `lifesteal` = value% of damage on crits,
        // `healing_aura` = value% of each ally's max HP every 2 own turns.
        val loadoutEffects = GearOptimizer.GEAR_SLOTS.mapNotNull { slotInfo ->
            val code = pick.targetLoadout[slotInfo.slot] ?: helper.getEquippedInSlot(char, slotInfo.slot)
            if (code.isEmpty()) null else try { helper.contentCache.getItemOrNull(code) } catch (_: Exception) { null }
        }.flatMap { it.effects }
        fun effect(code: String) = loadoutEffects.filter { it.code == code }.sumOf { it.value }.toDouble()
        val selfHealPerTurn = effect("healing") / 100.0 / 3.0 * stats.maxHp +
            effect("lifesteal") / 100.0 * stats.criticalStrike.coerceIn(0, 100) / 100.0 * loadout.expectedDamage
        val allyHealPercentPerTurn = effect("healing_aura") / 2.0
        val candidate = CoopGearCandidate(
            pick = executablePick,
            bankDemand = bankDemandForLoadout(char, pick.targetLoadout),
            heuristicScore = 0.0,
            projectedThreat = threat,
            stableKey = stableGearKey(pick.targetLoadout),
            expectedDamage = loadout.expectedDamage,
            effectiveHp = loadout.effectiveHp,
            maxHp = stats.maxHp,
            selfHealPerTurn = selfHealPerTurn,
            allyHealPercentPerTurn = allyHealPercentPerTurn
        )
        // Per-character ranking (candidate pruning): supports by damage; the tank by whether it
        // outlasts (with its own healing) a team of [partySize] dealing comparable damage, then by damage.
        val heuristic = when (role) {
            Role.SUPPORT -> loadout.expectedDamage
            Role.TANK -> {
                val self = candidate.toTeamCandidate(char.name)
                teamGearScore(
                    sustainedSurvivalTurns(self, listOf(self), monster),
                    netTeamDamage(loadout.expectedDamage * partySize, monster),
                    monster.hp.toDouble()
                )
            }
        }
        return candidate.copy(heuristicScore = heuristic)
    }

    private fun currentGearPick(char: Character): GearPickResult {
        val target = GearOptimizer.GEAR_SLOTS.mapNotNull { slotInfo ->
            helper.getEquippedInSlot(char, slotInfo.slot).takeIf { it.isNotEmpty() }?.let { slotInfo.slot to it }
        }.toMap()
        return GearPickResult(emptyList(), target)
    }

    private fun bankDemandForLoadout(char: Character, target: Map<String, String>): Map<String, Int> {
        val demand = target.values.groupingBy { it }.eachCount().toMutableMap()
        for ((slot, code) in target) {
            if (helper.getEquippedInSlot(char, slot) == code) {
                demand[code] = demand.getOrDefault(code, 0) - 1
            }
        }
        for (inventory in char.inventory.filter { it.quantity > 0 }) {
            demand[inventory.code] = demand.getOrDefault(inventory.code, 0) - inventory.quantity
        }
        return demand.filterValues { it > 0 }
    }

    private fun actionsForTarget(char: Character, target: Map<String, String>): List<ActionHelper.EquipAction> {
        val inventory = char.inventory.filter { it.quantity > 0 }
            .associate { it.code to it.quantity }.toMutableMap()
        val equipped = GearOptimizer.GEAR_SLOTS
            .map { helper.getEquippedInSlot(char, it.slot) }
            .filter { it.isNotEmpty() }
            .groupingBy { it }
            .eachCount()
            .toMutableMap()
        val actions = mutableListOf<ActionHelper.EquipAction>()
        for (slotInfo in GearOptimizer.GEAR_SLOTS) {
            val code = target[slotInfo.slot] ?: continue
            if (helper.getEquippedInSlot(char, slotInfo.slot) == code) {
                equipped[code] = equipped.getOrDefault(code, 0) - 1
                continue
            }
            val source = when {
                inventory.getOrDefault(code, 0) > 0 -> {
                    inventory[code] = inventory.getValue(code) - 1
                    "inventory"
                }
                equipped.getOrDefault(code, 0) > 0 -> {
                    equipped[code] = equipped.getValue(code) - 1
                    "equipped"
                }
                else -> "bank"
            }
            actions += ActionHelper.EquipAction(slotInfo.slot, code, source)
        }
        return actions
    }

    private fun stableGearKey(target: Map<String, String>): String =
        GearOptimizer.GEAR_SLOTS.joinToString("|") { "${it.slot}=${target[it.slot].orEmpty()}" }

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
        claimedGear: MutableMap<String, Int> = mutableMapOf(),
        excludedBankCodes: Set<String> = emptySet(),
        allowBank: Boolean = true,
        /** Support variant: never pick a threat-carrying item (see [generateCoopGearCandidates]). */
        avoidThreat: Boolean = false,
        /** Tank variant: break ties in favor of the higher-threat item. */
        seekThreat: Boolean = false,
        partySize: Int = 3,
    ): GearPickResult {
        // Whole-loadout search (combines elements across slots — a fire weapon pairs with
        // fire armor instead of each slot being scored alone) — see GearOptimizer.searchCoopLoadout.
        val coopLoadout = gearOptimizer.searchCoopLoadout(
            char, monster, role, claimedGear, excludedBankCodes, allowBank, partySize, excludeThreat = avoidThreat
        )

        val equipActions = mutableListOf<ActionHelper.EquipAction>()
        val targetLoadout = mutableMapOf<String, String>()
        val committedArtifactCodes = mutableSetOf<String>()

        for (slotInfo in GearOptimizer.GEAR_SLOTS) {
            if (slotInfo.slot == "rune") continue
            val code = coopLoadout.targetLoadout[slotInfo.slot] ?: continue
            val currentEquipped = helper.getEquippedInSlot(char, slotInfo.slot)

            // avoidThreat is enforced inside the search (excludeThreat), which also swaps out
            // CURRENTLY equipped threat gear whenever an owned threat-free alternative exists.
            val finalCode = code

            targetLoadout[slotInfo.slot] = finalCode
            if (slotInfo.slot in ARTIFACT_SLOTS) committedArtifactCodes.add(finalCode)

            if (finalCode != currentEquipped) {
                val source = when {
                    char.inventory.any { it.code == finalCode && it.quantity > 0 } -> "inventory"
                    else -> "bank"
                }
                if (source == "bank") claimedGear[finalCode] = claimedGear.getOrDefault(finalCode, 0) + 1
                equipActions.add(ActionHelper.EquipAction(slotInfo.slot, finalCode, source))
            }
        }
        // seekThreat (tank) is a pure tie-breaker the search doesn't need special-casing for:
        // among otherwise-equal picks it already prefers more damage/HP, and threat-bearing
        // armor is common enough among top defensive picks that this rarely matters in
        // practice; left as a no-op hook here for a future finer-grained pass if needed.

        // ── Role-aware rune override ────────────────────────────────────────────
        // The generic GEAR_SLOTS loop above scores runes purely by stat sheet, which means
        // a healing-aura rune (heals allies) can beat a self-heal rune (heals self) for the
        // tank — wrong for survivability. Replace whatever the generic pass chose with the
        // best rune for this character's role.
        val bestRune = pickBestRune(char, monster, role, claimedGear, excludedBankCodes, allowBank)
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

    /**
     * Owned utility potions grouped by role priority, irrelevant ones removed (see
     * [utilityRelevance]) and each group sorted by relevance to this fight.
     *
     *  - TANK (targeted by the boss): self-heal, HP boost, antipoison (if the boss poisons),
     *    resistance, then damage.
     *  - SUPPORT: splash heal (heals another character below 50% HP ? i.e. the tank), then
     *    damage, crit, and self-heal as a fallback.
     */
    private suspend fun orderedCandidatesByRole(
        char: Character,
        role: Role,
        monsterEffects: Set<String>,
        monsterAttack: Map<String, Int>,
        weaponAttack: Map<String, Int>,
        excludeCodes: Set<String>
    ): List<List<Item>> {
        val relevant = getOwnedConsumables(char)
            .filter { it.code !in excludeCodes }
            .mapNotNull { item -> utilityRelevance(item, monsterAttack, weaponAttack)?.let { item to it } }
        val byCategory = relevant.groupBy { classifyUtility(it.first) }
        val hasPoison = "poison" in monsterEffects

        val tankOrder = buildList {
            add(UtilityCategory.RESTORE)
            add(UtilityCategory.BOOST_HP)
            if (hasPoison) add(UtilityCategory.ANTIPOISON)
            add(UtilityCategory.BOOST_RES)
            add(UtilityCategory.BOOST_DMG)
            add(UtilityCategory.BOOST_DMG_ELEMENT)
            add(UtilityCategory.OTHER)
        }
        val supportOrder = listOf(
            UtilityCategory.SPLASH_RESTORE,
            UtilityCategory.BOOST_DMG,
            UtilityCategory.BOOST_DMG_ELEMENT,
            UtilityCategory.BOOST_CRITICAL_STRIKE,
            UtilityCategory.RESTORE,
            UtilityCategory.OTHER
        )
        val order = if (role == Role.TANK) tankOrder else supportOrder
        return order.map { category ->
            (byCategory[category] ?: emptyList()).sortedWith(
                compareByDescending<Pair<Item, Double>> { it.second }
                    .thenByDescending { it.first.level }.thenBy { it.first.code }
            ).map { it.first }
        }
    }

    private fun classifyUtility(item: Item): UtilityCategory {
        val codes = item.effects.map { it.code }.toSet()
        return when {
            codes.any { it.startsWith("boost_res_") } -> UtilityCategory.BOOST_RES
            codes.any { it.startsWith("boost_dmg_") } -> UtilityCategory.BOOST_DMG_ELEMENT
            "boost_dmg" in codes -> UtilityCategory.BOOST_DMG
            "boost_critical_strike" in codes -> UtilityCategory.BOOST_CRITICAL_STRIKE
            "splash_restore" in codes -> UtilityCategory.SPLASH_RESTORE
            "restore" in codes -> UtilityCategory.RESTORE
            "boost_hp" in codes -> UtilityCategory.BOOST_HP
            "antipoison" in codes || "antidote" in codes -> UtilityCategory.ANTIPOISON
            else -> UtilityCategory.OTHER
        }
    }

    /** Per-element base attack of the weapon in [targetLoadout] (falls back to the equipped one). */
    private suspend fun weaponAttackFor(char: Character, targetLoadout: Map<String, String>): Map<String, Int> {
        val code = targetLoadout["weapon"] ?: char.weaponSlot
        if (code.isEmpty()) return emptyMap()
        val weapon = try { helper.contentCache.getItemOrNull(code) } catch (_: Exception) { null } ?: return emptyMap()
        return ELEMENTS.associateWith { element -> weapon.effects.filter { it.code == "attack_$element" }.sumOf { it.value } }
            .filterValues { it > 0 }
    }

    private suspend fun getOwnedConsumables(char: Character): List<Item> {
        val invCodes = char.inventory.filter { it.quantity > 0 }.map { it.code }
        val bankCodes = helper.bankState.snapshot.value.filter { it.value > 0 }.keys
        val equippedCodes = listOf(char.utility1Slot, char.utility2Slot).filter { it.isNotEmpty() }
        val ownedCodes = (invCodes + bankCodes + equippedCodes).distinct().filter { it.isNotEmpty() }.sorted()
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

    private fun personalUtilityQuantity(char: Character, code: String): Int =
        helper.getItemQuantity(char, code) +
            (if (char.utility1Slot == code) char.utility1SlotQuantity else 0) +
            (if (char.utility2Slot == code) char.utility2SlotQuantity else 0)

    private fun getAvailableUtilityQuantity(
        char: Character,
        code: String,
        committedBankUtilities: Map<String, Int>
    ): Int {
        val personal = personalUtilityQuantity(char, code)
        val bankAvailable = (helper.bankState.getQuantity(code) - committedBankUtilities.getOrDefault(code, 0))
            .coerceAtLeast(0)
        return (personal + bankAvailable).coerceAtMost(UTILITY_MAX_QUANTITY)
    }

    private fun reserveBankUtilityQuantity(
        char: Character,
        code: String,
        selectedQuantity: Int,
        committedBankUtilities: MutableMap<String, Int>
    ) {
        val bankNeeded = (selectedQuantity - personalUtilityQuantity(char, code)).coerceAtLeast(0)
        if (bankNeeded > 0) {
            committedBankUtilities[code] = committedBankUtilities.getOrDefault(code, 0) + bankNeeded
        }
    }

    private fun releaseBankUtilityQuantity(
        char: Character,
        code: String,
        selectedQuantity: Int,
        committedBankUtilities: MutableMap<String, Int>
    ) {
        val bankNeeded = (selectedQuantity - personalUtilityQuantity(char, code)).coerceAtLeast(0)
        if (bankNeeded > 0) {
            committedBankUtilities[code] = (committedBankUtilities.getOrDefault(code, 0) - bankNeeded).coerceAtLeast(0)
        }
    }

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

    internal data class CoopScore(
        val winRate: Double,
        val avgWinTurns: Double,
        val totalUtility: Int,
        /**
         * Average boss HP fraction left in LOST fights (0.0 when there were none). Separates
         * teams that all lose (or win equally often): the one that gets the boss lower is closer
         * to winning, so 0% teams can still be ranked instead of tying.
         */
        val lossBossHpRemaining: Double = 0.0
    ) : Comparable<CoopScore> {
        override fun compareTo(other: CoopScore): Int {
            // Tightened thresholds: 0.5% win rate and 0.5 turn improvement both count.
            // The previous 1%/1-turn dead zone was too wide for marginal-but-real utility gains.
            if (this.winRate > other.winRate + 0.005) return 1
            if (this.winRate < other.winRate - 0.005) return -1
            if (this.avgWinTurns < other.avgWinTurns - 0.5) return 1
            if (this.avgWinTurns > other.avgWinTurns + 0.5) return -1
            if (this.lossBossHpRemaining < other.lossBossHpRemaining - LOSS_BOSS_HP_EPSILON) return 1
            if (this.lossBossHpRemaining > other.lossBossHpRemaining + LOSS_BOSS_HP_EPSILON) return -1
            return this.totalUtility.compareTo(other.totalUtility)
        }
    }

    private suspend fun simToCoopScore(
        loadouts: List<ActionHelper.CoopParticipantLoadout>,
        monsterCode: String,
        precomputed: CombatSimulationData? = null
    ): CoopScore {
        val sim = precomputed ?: try {
            helper.simulateCoopFight(monsterCode, loadouts, COOP_SIM_ITERATIONS)
        } catch (_: Exception) {
            return CoopScore(0.0, Double.MAX_VALUE, 0, lossBossHpRemaining = 1.0)
        }
        val avgTurns = sim.results.filter { it.result == "win" }.map { it.turns }
            .let { if (it.isEmpty()) Double.MAX_VALUE else it.average() }
        val totalUtility = loadouts.sumOf { it.char.prospecting + it.char.wisdom }
        val lossBossHp = sim.results.filter { it.result != "win" }
            .map { it.monsterHpRemainingFraction() ?: 1.0 }
            .let { if (it.isEmpty()) 0.0 else it.average() }
        return CoopScore(sim.winrate, avgTurns, totalUtility, lossBossHp)
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
        claimedGear: Map<String, Int> = emptyMap(),
        excludedBankCodes: Set<String> = emptySet(),
        allowBank: Boolean = true
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
            buildList {
                addAll(helper.getAvailableEquipmentForSlot(char, runeSlotInfo))
                val currentCode = char.runeSlot
                if (currentCode.isNotEmpty()) {
                    helper.contentCache.getItemOrNull(currentCode)?.let {
                        add(ActionHelper.EquipmentOption(it, "equipped", 1))
                    }
                }
            }
                .filter { it.source == "inventory" || it.source == "bank" || it.source == "equipped" }
                // Cross-character claim check for bank runes
                .filter { option ->
                    if (option.source != "bank") return@filter true
                    if (!allowBank || option.item.code in excludedBankCodes) return@filter false
                    val bankQty = helper.bankState.getQuantity(option.item.code)
                    val claimed = claimedGear.getOrDefault(option.item.code, 0)
                    (bankQty - claimed) > 0
                }
        } catch (_: Exception) { return null }

        if (candidates.isEmpty()) return null

        val tankOrder = listOf(
            RuneCategory.SELF_HEAL,   // healing_rune — self-sustain; tank must survive
            RuneCategory.DAMAGE,      // damage rune — lower priority for tank
            RuneCategory.DEFENSIVE,   // defensive rune — fallback survivability
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
            val best = pool.sortedWith(
                compareByDescending<ActionHelper.EquipmentOption> {
                    scoreItem(it.item, "rune", bossDmg, bossRes, role)
                }.thenByDescending { it.item.level }.thenBy { it.item.code }
            ).firstOrNull()
            if (best != null) return best
        }
        return null
    }
}
