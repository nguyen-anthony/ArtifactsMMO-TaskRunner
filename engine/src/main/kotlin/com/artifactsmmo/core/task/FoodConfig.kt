package com.artifactsmmo.core.task

import kotlinx.serialization.Serializable

/**
 * Account-wide fight food settings (config kind `food`, key [KEY]); see [FoodPlanner].
 *
 * @property adaptive learn food/loot/damage per fight and size food loads and heal timing from it.
 *   Off = legacy: heal below 90% HP, carry heal + 25 food, keep 25 cooked drops.
 * @property lootReserveFights loot room always left free, in fights' worth of drops.
 * @property minFood smallest food load once rates are learned.
 * @property maxFood largest food load (0 = no cap besides inventory room).
 * @property healSafety heal once HP drops to this multiple of the worst recent damage taken in one fight.
 * @property restWhenCheaper rest instead of eating / fetching food when that costs less time.
 */
@Serializable
data class FoodConfig(
    val adaptive: Boolean = true,
    val lootReserveFights: Int = 3,
    val minFood: Int = 5,
    val maxFood: Int = 0,
    val healSafety: Double = 1.5,
    val restWhenCheaper: Boolean = true,
) {
    /** Values clamped to sane ranges (the API stores whatever the UI sends). */
    fun sanitized() = copy(
        lootReserveFights = lootReserveFights.coerceIn(0, 20),
        minFood = minFood.coerceIn(0, 200),
        maxFood = maxFood.coerceIn(0, 1000),
        healSafety = healSafety.coerceIn(1.0, 5.0),
    )

    companion object { const val KEY = "default" }
}
