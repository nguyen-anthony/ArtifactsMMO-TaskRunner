package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Monster
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Local, API-free combat simulator.
 *
 * Implements the exact damage / resistance / critical-strike formulas documented at
 * https://docs.artifactsmmo.com/concepts/stats_and_fights/
 *
 * Damage formula (per element):
 *   elementalAttack = round(baseAttack × (1 + (globalDmg + elemDmg) / 100))
 *   finalDamage     = round(elementalAttack × (1 − resistance / 100))
 *   if random < critChance/100 → finalDamage = round(finalDamage × 1.5)
 *
 * Turn order: higher initiative goes first; tiebreak = higher HP; further tie = random.
 * Max 100 turns — if unresolved, the side that went first (attacker) loses.
 *
 * Monster effects (Burn, Poison, Healing, Barrier, etc.) are NOT simulated.
 * When a monster has effects the [Result.effectsIgnored] flag is set to true and
 * the caller should apply a more conservative win-rate threshold (e.g. 0.98 instead of 0.90).
 */
object LocalFightSimulator {

    /** Threshold multiplier used for critical-strike damage. */
    private const val CRIT_MULTIPLIER = 1.5

    /** Maximum turns per fight — after this the first-mover loses. */
    private const val MAX_TURNS = 100

    data class Result(
        val wins: Int,
        val losses: Int,
        val winRate: Double,
        /** Average turn count across winning fights only. MAX_VALUE if no wins. */
        val avgWinTurns: Double,
        /**
         * True when the monster has effects that were not modelled in the simulation.
         * The simulated win-rate will be optimistic in that case — use a stricter threshold.
         */
        val effectsIgnored: Boolean
    )

    /**
     * Simulate [iterations] fights between [character] (at full HP) and [monster].
     * Returns aggregate win/loss counts, win rate, and average turns on wins.
     */
    fun simulate(
        character: Character,
        monster: Monster,
        iterations: Int = 100,
        rng: Random = Random.Default
    ): Result {
        val effectsIgnored = monster.effects.isNotEmpty()
        var wins = 0
        var losses = 0
        var winTurnsTotal = 0L

        repeat(iterations) {
            val (won, turns) = simulateSingleFight(character, monster, rng)
            if (won) {
                wins++
                winTurnsTotal += turns
            } else {
                losses++
            }
        }

        val winRate = if (iterations > 0) wins.toDouble() / iterations else 0.0
        val avgWinTurns = if (wins > 0) winTurnsTotal.toDouble() / wins else Double.MAX_VALUE
        return Result(wins, losses, winRate, avgWinTurns, effectsIgnored)
    }

    /**
     * Simulate a single fight. Returns (won, turnsElapsed).
     */
    private fun simulateSingleFight(
        character: Character,
        monster: Monster,
        rng: Random
    ): Pair<Boolean, Int> {
        var charHp = character.maxHp
        var monsterHp = monster.hp

        // Determine turn order: higher initiative acts first.
        // Tiebreak: higher current HP. Further tie: random.
        val charGoesFirst = when {
            character.initiative > monster.initiative -> true
            character.initiative < monster.initiative -> false
            charHp > monsterHp -> true
            charHp < monsterHp -> false
            else -> rng.nextBoolean()
        }

        var charactersTurn = charGoesFirst

        for (turn in 1..MAX_TURNS) {
            if (charactersTurn) {
                // Character attacks monster
                monsterHp -= calculateDamage(
                    attackFire  = character.attackFire,
                    attackEarth = character.attackEarth,
                    attackWater = character.attackWater,
                    attackAir   = character.attackAir,
                    globalDmg   = character.dmg,
                    dmgFire     = character.dmgFire,
                    dmgEarth    = character.dmgEarth,
                    dmgWater    = character.dmgWater,
                    dmgAir      = character.dmgAir,
                    resFire     = monster.resFire,
                    resEarth    = monster.resEarth,
                    resWater    = monster.resWater,
                    resAir      = monster.resAir,
                    critChance  = character.criticalStrike,
                    rng         = rng
                )
                if (monsterHp <= 0) return true to turn  // Character wins
            } else {
                // Monster attacks character
                charHp -= calculateDamage(
                    attackFire  = monster.attackFire,
                    attackEarth = monster.attackEarth,
                    attackWater = monster.attackWater,
                    attackAir   = monster.attackAir,
                    globalDmg   = 0,
                    dmgFire     = 0,
                    dmgEarth    = 0,
                    dmgWater    = 0,
                    dmgAir      = 0,
                    resFire     = character.resFire,
                    resEarth    = character.resEarth,
                    resWater    = character.resWater,
                    resAir      = character.resAir,
                    critChance  = monster.criticalStrike,
                    rng         = rng
                )
                if (charHp <= 0) return false to turn  // Character loses
            }

            charactersTurn = !charactersTurn
        }

        // 100 turns exhausted — the first-mover (attacker) loses.
        // If character went first, character is the attacker and loses.
        return (!charGoesFirst) to MAX_TURNS
    }

    /**
     * Calculate total damage dealt by an attacker (all 4 elements combined).
     *
     * Each element is independent:
     *   elementalAttack = round(baseAttack × (1 + (globalDmg + elemDmg) / 100))
     *   finalDamage     = round(elementalAttack × (1 − resistance / 100))
     *   crit            = if (rng < critChance/100): round(finalDamage × 1.5)
     */
    private fun calculateDamage(
        attackFire: Int, attackEarth: Int, attackWater: Int, attackAir: Int,
        globalDmg: Int,
        dmgFire: Int, dmgEarth: Int, dmgWater: Int, dmgAir: Int,
        resFire: Int, resEarth: Int, resWater: Int, resAir: Int,
        critChance: Int,
        rng: Random
    ): Int {
        var total = 0
        total += elementDamage(attackFire,  globalDmg, dmgFire,  resFire,  critChance, rng)
        total += elementDamage(attackEarth, globalDmg, dmgEarth, resEarth, critChance, rng)
        total += elementDamage(attackWater, globalDmg, dmgWater, resWater, critChance, rng)
        total += elementDamage(attackAir,   globalDmg, dmgAir,   resAir,   critChance, rng)
        return total
    }

    /**
     * Calculate damage for a single element.
     * Returns 0 if base attack is 0 (weapon doesn't use this element).
     */
    private fun elementDamage(
        baseAttack: Int,
        globalDmg: Int,
        elemDmg: Int,
        resistance: Int,
        critChance: Int,
        rng: Random
    ): Int {
        if (baseAttack <= 0) return 0

        // Apply damage bonuses to base attack
        val elementalAttack = (baseAttack * (1.0 + (globalDmg + elemDmg) / 100.0)).roundToInt()

        // Apply resistance
        val finalDamage = (elementalAttack * (1.0 - resistance / 100.0)).roundToInt()
        if (finalDamage <= 0) return 0

        // Apply critical strike
        return if (critChance > 0 && rng.nextFloat() < critChance / 100f) {
            (finalDamage * CRIT_MULTIPLIER).roundToInt()
        } else {
            finalDamage
        }
    }
}
