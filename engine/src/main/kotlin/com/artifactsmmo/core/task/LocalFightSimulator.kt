package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Monster
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * API-free combat simulator for the documented baseline combat rules.
 *
 * Supported mechanics:
 *  - one to three characters against one monster
 *  - initiative order, with higher HP then random as tie breakers
 *  - independent elemental damage and resistance rounding
 *  - one critical-strike roll per attack, applied to every elemental hit
 *  - cooperative monster targeting by threat/current HP
 *  - a 100-global-turn limit, which is always a character-team loss
 *
 * Item, utility, rune, and monster effects are not modeled yet. [Result.effectsIgnored]
 * identifies loadouts for which the baseline result is incomplete.
 */
object LocalFightSimulator {
    private const val CRIT_MULTIPLIER = 1.5
    private const val MAX_TURNS = 100
    private const val THREAT_TARGET_CHANCE = 0.90

    data class Result(
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        /** Average global turn count across winning fights only. MAX_VALUE if no wins. */
        val avgWinTurns: Double,
        val effectsIgnored: Boolean
    )

    internal data class ElementalValues(
        val fire: Int = 0,
        val earth: Int = 0,
        val water: Int = 0,
        val air: Int = 0
    ) {
        val total: Int get() = fire + earth + water + air
    }

    internal data class AttackResult(
        val damage: ElementalValues,
        val critical: Boolean
    ) {
        val totalDamage: Int get() = damage.total
    }

    internal data class TargetCandidate(
        val id: String,
        val threat: Int,
        val hp: Int
    )

    private enum class Side { CHARACTERS, MONSTER }

    private data class FighterState(
        val id: String,
        val side: Side,
        val initiative: Int,
        val threat: Int,
        val maxHp: Int,
        val attacks: ElementalValues,
        val damageBonuses: ElementalValues,
        val globalDamageBonus: Int,
        val resistances: ElementalValues,
        val criticalStrike: Int,
        val orderTieBreaker: Int,
        var hp: Int = maxHp,
        var playedTurns: Int = 0
    )

    fun simulate(
        character: Character,
        monster: Monster,
        iterations: Int = 100,
        rng: Random = Random.Default
    ): Result = simulate(listOf(character), monster, iterations, rng)

    /** Simulate a cooperative fight with up to three characters. */
    fun simulate(
        characters: List<Character>,
        monster: Monster,
        iterations: Int = 100,
        rng: Random = Random.Default
    ): Result {
        require(characters.isNotEmpty()) { "At least one character is required" }
        require(characters.size <= 3) { "At most three characters are supported" }
        require(iterations > 0) { "Iterations must be greater than zero" }

        val effectsIgnored = monster.effects.isNotEmpty() || characters.any {
            it.effects.isNotEmpty() || it.runeSlot.isNotEmpty() ||
                it.utility1Slot.isNotEmpty() || it.utility2Slot.isNotEmpty()
        }
        var wins = 0
        var winTurnsTotal = 0L

        repeat(iterations) {
            val (won, turns) = simulateSingleFight(characters, monster, rng)
            if (won) {
                wins++
                winTurnsTotal += turns
            }
        }

        val losses = iterations - wins
        return Result(
            wins = wins,
            losses = losses,
            winRate = wins.toDouble() / iterations,
            avgWinTurns = if (wins > 0) winTurnsTotal.toDouble() / wins else Double.MAX_VALUE,
            effectsIgnored = effectsIgnored
        )
    }

    private fun simulateSingleFight(
        characters: List<Character>,
        monster: Monster,
        rng: Random
    ): Pair<Boolean, Int> {
        val fighters = buildList {
            characters.forEach { character ->
                add(
                    FighterState(
                        id = character.name,
                        side = Side.CHARACTERS,
                        initiative = character.initiative,
                        threat = character.threat,
                        maxHp = character.maxHp,
                        attacks = ElementalValues(
                            character.attackFire,
                            character.attackEarth,
                            character.attackWater,
                            character.attackAir
                        ),
                        damageBonuses = ElementalValues(
                            character.dmgFire,
                            character.dmgEarth,
                            character.dmgWater,
                            character.dmgAir
                        ),
                        globalDamageBonus = character.dmg,
                        resistances = ElementalValues(
                            character.resFire,
                            character.resEarth,
                            character.resWater,
                            character.resAir
                        ),
                        criticalStrike = character.criticalStrike,
                        orderTieBreaker = rng.nextInt()
                    )
                )
            }
            add(
                FighterState(
                    id = monster.code,
                    side = Side.MONSTER,
                    initiative = monster.initiative,
                    threat = 0,
                    maxHp = monster.hp,
                    attacks = ElementalValues(
                        monster.attackFire,
                        monster.attackEarth,
                        monster.attackWater,
                        monster.attackAir
                    ),
                    damageBonuses = ElementalValues(),
                    globalDamageBonus = 0,
                    resistances = ElementalValues(
                        monster.resFire,
                        monster.resEarth,
                        monster.resWater,
                        monster.resAir
                    ),
                    criticalStrike = monster.criticalStrike,
                    orderTieBreaker = rng.nextInt()
                )
            )
        }

        val turnOrder = fighters.sortedWith(
            compareByDescending<FighterState> { it.initiative }
                .thenByDescending { it.hp }
                .thenBy { it.orderTieBreaker }
        )
        val monsterState = fighters.single { it.side == Side.MONSTER }

        var globalTurn = 0
        var actorIndex = 0
        while (globalTurn < MAX_TURNS) {
            val actor = turnOrder[actorIndex % turnOrder.size]
            actorIndex++
            if (actor.hp <= 0) continue
            globalTurn++
            actor.playedTurns++

            if (actor.side == Side.CHARACTERS) {
                val attack = calculateAttack(
                    attacks = actor.attacks,
                    globalDamageBonus = actor.globalDamageBonus,
                    elementalDamageBonuses = actor.damageBonuses,
                    resistances = monsterState.resistances,
                    criticalStrike = actor.criticalStrike,
                    rng = rng
                )
                monsterState.hp -= attack.totalDamage
                if (monsterState.hp <= 0) return true to globalTurn
            } else {
                val livingCharacters = fighters.filter { it.side == Side.CHARACTERS && it.hp > 0 }
                if (livingCharacters.isEmpty()) return false to globalTurn
                val targetId = selectMonsterTarget(
                    livingCharacters.map { TargetCandidate(it.id, it.threat, it.hp) },
                    useThreat = rng.nextDouble() < THREAT_TARGET_CHANCE,
                    rng = rng
                )
                val target = livingCharacters.single { it.id == targetId }
                val attack = calculateAttack(
                    attacks = actor.attacks,
                    globalDamageBonus = actor.globalDamageBonus,
                    elementalDamageBonuses = actor.damageBonuses,
                    resistances = target.resistances,
                    criticalStrike = actor.criticalStrike,
                    rng = rng
                )
                target.hp -= attack.totalDamage
                if (fighters.none { it.side == Side.CHARACTERS && it.hp > 0 }) {
                    return false to globalTurn
                }
            }
        }

        return false to MAX_TURNS
    }

    /**
     * Resolve one complete attack. Critical chance is rolled once and shared by every
     * nonzero elemental hit, while each element retains its own rounding stages.
     */
    internal fun calculateAttack(
        attacks: ElementalValues,
        globalDamageBonus: Int,
        elementalDamageBonuses: ElementalValues,
        resistances: ElementalValues,
        criticalStrike: Int,
        rng: Random
    ): AttackResult {
        val critical = criticalStrike > 0 && rng.nextDouble() < criticalStrike.coerceAtMost(100) / 100.0
        return AttackResult(
            damage = ElementalValues(
                fire = calculateElementDamage(attacks.fire, globalDamageBonus, elementalDamageBonuses.fire, resistances.fire, critical),
                earth = calculateElementDamage(attacks.earth, globalDamageBonus, elementalDamageBonuses.earth, resistances.earth, critical),
                water = calculateElementDamage(attacks.water, globalDamageBonus, elementalDamageBonuses.water, resistances.water, critical),
                air = calculateElementDamage(attacks.air, globalDamageBonus, elementalDamageBonuses.air, resistances.air, critical)
            ),
            critical = critical
        )
    }

    private fun calculateElementDamage(
        baseAttack: Int,
        globalDamageBonus: Int,
        elementalDamageBonus: Int,
        resistance: Int,
        critical: Boolean
    ): Int {
        if (baseAttack <= 0) return 0
        val elementalAttack = (
            baseAttack * (1.0 + (globalDamageBonus + elementalDamageBonus) / 100.0)
        ).roundToInt()
        val resistedDamage = (
            elementalAttack * (1.0 - resistance / 100.0)
        ).roundToInt().coerceAtLeast(0)
        return if (critical) (resistedDamage * CRIT_MULTIPLIER).roundToInt() else resistedDamage
    }

    /** Select a living target using the documented threat/HP branch and tie breakers. */
    internal fun selectMonsterTarget(
        candidates: List<TargetCandidate>,
        useThreat: Boolean,
        rng: Random
    ): String {
        require(candidates.isNotEmpty()) { "At least one target candidate is required" }

        val primaryPool = if (useThreat) {
            val highestThreat = candidates.maxOf { it.threat }
            candidates.filter { it.threat == highestThreat }
        } else {
            val lowestHp = candidates.minOf { it.hp }
            candidates.filter { it.hp == lowestHp }
        }
        val lowestHp = primaryPool.minOf { it.hp }
        val finalists = primaryPool.filter { it.hp == lowestHp }
        return finalists[rng.nextInt(finalists.size)].id
    }
}
