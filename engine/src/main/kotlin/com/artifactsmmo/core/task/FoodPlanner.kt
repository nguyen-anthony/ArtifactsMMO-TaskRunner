package com.artifactsmmo.core.task

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * Decides how much healing food a fighting character carries, when it heals, and when resting
 * beats eating.
 *
 * Each fight eats some food (freeing inventory) and drops some loot (filling it). Carrying
 * food so that both run out together means one bank trip handles both:
 * with C free units, f food eaten and d loot gained per fight, carry about C·f/d food.
 * (After n fights: food left = F − f·n, units used = F + (d − f)·n; both limits meet at F = C·f/d.)
 */
object FoodPlanner {
    /** Fights observed before the learned rates are trusted. */
    const val MIN_SAMPLES = 3
    /** Legacy heal threshold (fraction of max HP) used until damage is learned. */
    const val LEGACY_HEAL_BELOW = 0.90
    /** Rest cooldown: 1 second per 5 HP, minimum 3 seconds (API docs). */
    const val HP_PER_REST_SECOND = 5
    const val MIN_REST_SECONDS = 3
    /** Assumed cooldown of eating one batch of food. */
    const val USE_SECONDS = 3

    /**
     * Food units to carry after a bank visit, or null when there's no data yet (or adaptive is off).
     * @param capacity free inventory units once loot is deposited (food already carried counts as free).
     */
    fun target(capacity: Int, stats: FightRates?, cfg: FoodConfig = FoodConfig()): Int? {
        if (!cfg.adaptive || stats == null || stats.fights < MIN_SAMPLES || capacity <= 0) return null
        val f = stats.foodPerFight
        val d = stats.lootPerFight
        // At least one unit: food filling the room exactly would trip the full-inventory check at once.
        val reserve = ceil(d * cfg.lootReserveFights).toInt().coerceAtLeast(1)
        var max = (capacity - reserve).coerceAtLeast(0)
        if (cfg.maxFood > 0) max = minOf(max, cfg.maxFood)
        val ideal = when {
            f <= 0.0 -> cfg.minFood
            d <= f -> max
            else -> ceil(capacity * f / d).toInt()
        }
        return ideal.coerceAtMost(max).coerceAtLeast(minOf(cfg.minFood, max))
    }

    /**
     * Whether to heal before the next fight: once HP is at or below [FoodConfig.healSafety] times
     * the worst recent damage taken in one fight. Healing late and then to full needs fewer eat
     * actions than topping up after every fight. Null = no damage data yet (use the legacy 90%).
     */
    fun shouldHeal(hp: Int, maxHp: Int, stats: FightRates?, cfg: FoodConfig = FoodConfig()): Boolean? {
        if (!cfg.adaptive || stats == null || stats.fights < MIN_SAMPLES) return null
        if (hp >= maxHp) return false
        val needed = ceil(stats.worstHpLost * cfg.healSafety).toInt().coerceAtLeast(1)
        return hp <= needed
    }

    fun restSeconds(hpMissing: Int): Int =
        maxOf(MIN_REST_SECONDS, ceil(hpMissing.toDouble() / HP_PER_REST_SECOND).toInt())

    /**
     * Whether resting off [hpMissing] costs no more time than eating [foodUnits] of food. When
     * the food must first be fetched ([tripSeconds] > 0), each unit also carries its share of
     * the trip, which brings back [unitsPerTrip] units.
     */
    fun restIsCheaper(hpMissing: Int, foodUnits: Int, tripSeconds: Int = 0, unitsPerTrip: Int = 1): Boolean {
        val tripShare = if (tripSeconds > 0) tripSeconds.toDouble() * foodUnits / unitsPerTrip.coerceAtLeast(1) else 0.0
        return restSeconds(hpMissing) <= USE_SECONDS + tripShare
    }

    /**
     * Learned per-fight rates. [worstHpLost] is the largest HP loss in the current or previous
     * window, so one lucky stretch doesn't make healing too late.
     */
    data class FightRates(
        val fights: Double,
        val foodEaten: Double,
        val loot: Double,
        val worstHpLost: Int = 0,
        val previousWorstHpLost: Int = 0,
    ) {
        val foodPerFight get() = if (fights > 0) foodEaten / fights else 0.0
        val lootPerFight get() = if (fights > 0) loot / fights else 0.0
    }
}

/**
 * Rolling food/loot/damage rates per character and monster. Old samples fade (totals are halved
 * past [WINDOW] fights) so gear or level changes are picked up. A lost fight forgets the pair, so
 * the character falls back to the cautious legacy behaviour and relearns.
 */
class FightRateTracker {
    private val rates = ConcurrentHashMap<String, FoodPlanner.FightRates>()

    fun recordFight(character: String, monster: String, lootUnits: Int, hpLost: Int = 0) = update(character, monster) {
        it.copy(fights = it.fights + 1, loot = it.loot + lootUnits, worstHpLost = maxOf(it.worstHpLost, hpLost))
    }

    fun recordFoodEaten(character: String, monster: String, units: Int) = update(character, monster) {
        it.copy(foodEaten = it.foodEaten + units)
    }

    fun recordLoss(character: String, monster: String) {
        rates.remove(key(character, monster))
    }

    /** Rates with [FoodPlanner.FightRates.worstHpLost] covering both windows. */
    fun get(character: String, monster: String): FoodPlanner.FightRates? = rates[key(character, monster)]
        ?.let { it.copy(worstHpLost = maxOf(it.worstHpLost, it.previousWorstHpLost)) }

    private fun key(character: String, monster: String) = "$character|$monster"

    private fun update(character: String, monster: String, f: (FoodPlanner.FightRates) -> FoodPlanner.FightRates) {
        rates.compute(key(character, monster)) { _, old ->
            val next = f(old ?: FoodPlanner.FightRates(0.0, 0.0, 0.0))
            if (next.fights > WINDOW) {
                FoodPlanner.FightRates(next.fights / 2, next.foodEaten / 2, next.loot / 2, 0, next.worstHpLost)
            } else next
        }
    }

    companion object { const val WINDOW = 40.0 }
}
