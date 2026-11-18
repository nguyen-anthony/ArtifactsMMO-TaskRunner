package com.artifactsmmo.core.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FoodPlannerTest {
    private fun rates(fights: Int, food: Int, loot: Int) =
        FoodPlanner.FightRates(fights.toDouble(), food.toDouble(), loot.toDouble())

    @Test
    fun `no target until enough fights are observed`() {
        assertNull(FoodPlanner.target(100, null))
        assertNull(FoodPlanner.target(100, rates(2, 4, 6)))
    }

    @Test
    fun `food and loot room run out together`() {
        // 2 food eaten and 3 loot per fight with 100 free -> ~67 food (~33 fights per trip).
        assertEquals(67, FoodPlanner.target(100, rates(10, 20, 30)))
    }

    @Test
    fun `eating faster than looting fills the room minus the loot reserve`() {
        // reserve = ceil(1 loot/fight * 3) = 3
        assertEquals(97, FoodPlanner.target(100, rates(10, 50, 10)))
        // no loot still keeps one unit free so the full-inventory check doesn't trip immediately
        assertEquals(99, FoodPlanner.target(100, rates(10, 50, 0)))
    }

    @Test
    fun `fights that need no food carry only a small buffer`() {
        assertEquals(FoodConfig().minFood, FoodPlanner.target(100, rates(10, 0, 30)))
    }

    @Test
    fun `settings cap the load and turn learning off`() {
        assertEquals(40, FoodPlanner.target(100, rates(10, 20, 30), FoodConfig(maxFood = 40)))
        assertNull(FoodPlanner.target(100, rates(10, 20, 30), FoodConfig(adaptive = false)))
    }

    @Test
    fun `heal only when HP nears the worst recent hit`() {
        val r = rates(10, 20, 30).copy(worstHpLost = 100)
        assertNull(FoodPlanner.shouldHeal(200, 500, rates(2, 0, 0)))
        assertEquals(false, FoodPlanner.shouldHeal(151, 500, r)) // 1.5 × 100 = 150
        assertEquals(true, FoodPlanner.shouldHeal(150, 500, r))
        assertEquals(false, FoodPlanner.shouldHeal(500, 500, r.copy(worstHpLost = 1000)))
    }

    @Test
    fun `rest beats eating only for small wounds or long food trips`() {
        assertTrue(FoodPlanner.restIsCheaper(hpMissing = 15, foodUnits = 1))
        assertFalse(FoodPlanner.restIsCheaper(hpMissing = 16, foodUnits = 1))
        // 300 HP = 60s of rest vs 3s eating + 2/60 of a 106s trip
        assertFalse(FoodPlanner.restIsCheaper(300, foodUnits = 2, tripSeconds = 106, unitsPerTrip = 60))
        // a long trip that only brings back 2 units makes resting cheaper
        assertTrue(FoodPlanner.restIsCheaper(100, foodUnits = 2, tripSeconds = 200, unitsPerTrip = 2))
    }

    @Test
    fun `tracker averages per character and monster and fades old samples`() {
        val tracker = FightRateTracker()
        repeat(40) {
            tracker.recordFight("a", "wolf", 3, hpLost = if (it == 0) 90 else 10)
            tracker.recordFoodEaten("a", "wolf", 2)
        }
        tracker.recordFight("a", "wolf", 3, hpLost = 10)
        val r = tracker.get("a", "wolf")!!
        assertEquals(20.5, r.fights)
        assertEquals(3.0, r.lootPerFight)
        assertEquals(90, r.worstHpLost) // previous window's worst still counts
        assertNull(tracker.get("b", "wolf"))

        tracker.recordLoss("a", "wolf")
        assertNull(tracker.get("a", "wolf"))
    }
}
