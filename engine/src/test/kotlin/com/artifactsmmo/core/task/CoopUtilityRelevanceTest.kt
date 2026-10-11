package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleEffect
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoopUtilityRelevanceTest {
    private val fireBoss = mapOf("fire" to 100, "earth" to 0, "water" to 0, "air" to 0)
    private val earthWeapon = mapOf("earth" to 30)

    @Test
    fun resistancePotionForAnElementTheBossDoesNotUseIsIrrelevant() {
        assertNull(CoopOptimizer.utilityRelevance(potion("water_res_potion", "boost_res_water" to 10), fireBoss, earthWeapon))
        assertNotNull(CoopOptimizer.utilityRelevance(potion("fire_res_potion", "boost_res_fire" to 10), fireBoss, earthWeapon))
    }

    @Test
    fun allElementResistanceIsValuedByTheBossAttackItCovers() {
        val diabolic = potion(
            "diabolic_elixir",
            "boost_res_fire" to 12, "boost_res_earth" to 12, "boost_res_water" to 12, "boost_res_air" to 12
        )
        val fireRes = potion("fire_res_potion", "boost_res_fire" to 10)
        val diabolicValue = CoopOptimizer.utilityRelevance(diabolic, fireBoss, earthWeapon)!!
        val fireResValue = CoopOptimizer.utilityRelevance(fireRes, fireBoss, earthWeapon)!!
        assertTrue(diabolicValue > fireResValue)
    }

    @Test
    fun elementalDamagePotionMustMatchThePlannedWeapon() {
        assertNull(CoopOptimizer.utilityRelevance(potion("fire_boost_potion", "boost_dmg_fire" to 12), fireBoss, earthWeapon))
        assertNotNull(CoopOptimizer.utilityRelevance(potion("earth_boost_potion", "boost_dmg_earth" to 12), fireBoss, earthWeapon))
        assertNull(CoopOptimizer.utilityRelevance(potion("earth_boost_potion", "boost_dmg_earth" to 12), fireBoss, emptyMap()))
    }

    @Test
    fun healingPotionsAreAlwaysRelevant() {
        assertNotNull(CoopOptimizer.utilityRelevance(potion("health_splash_potion", "splash_restore" to 150), fireBoss, emptyMap()))
    }

    @Test
    fun teamWhoseTankSurvivesOutranksHigherDamageTeamWhoseTankDies() {
        // Boss 3000 HP: 300 team damage -> 10 turns; 600 team damage -> 5 turns.
        val survives = CoopOptimizer.teamGearScore(tankEffectiveHp = 12.0, teamDamage = 300.0, monsterHp = 3000.0)
        val dies = CoopOptimizer.teamGearScore(tankEffectiveHp = 4.0, teamDamage = 600.0, monsterHp = 3000.0)
        val survivesMoreDamage = CoopOptimizer.teamGearScore(tankEffectiveHp = 12.0, teamDamage = 400.0, monsterHp = 3000.0)
        assertTrue(survives > dies)
        assertTrue(survivesMoreDamage > survives)
    }

    private fun potion(code: String, vararg effects: Pair<String, Int>) = Item(
        name = code, code = code, level = 1, type = "utility", subtype = "potion", description = "",
        effects = effects.map { SimpleEffect(it.first, it.second, "") }, tradeable = true
    )
}
