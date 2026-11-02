package com.artifactsmmo.core.offering

import com.artifactsmmo.client.models.CraftInfo
import com.artifactsmmo.client.models.DropRate
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Monster
import com.artifactsmmo.client.models.Resource
import com.artifactsmmo.client.models.SimpleItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AcquisitionPlannerTest {

    private class FakeWorld : AcquisitionWorld {
        val items = mutableMapOf<String, Item>()
        val resources = mutableListOf<Resource>()
        val monsters = mutableListOf<Monster>()
        val skills = mutableMapOf<String, Int>()
        val owned = mutableMapOf<String, Int>()
        val killSeconds = mutableMapOf<String, Double?>()
        val unreachable = mutableSetOf<String>()

        override suspend fun item(code: String) = items[code]
        override suspend fun resourcesDropping(code: String) = resources.filter { r -> r.drops.any { it.code == code } }
        override suspend fun monstersDropping(code: String) = monsters.filter { m -> m.drops.any { it.code == code } }
        override fun skillLevel(skill: String) = skills[skill] ?: 1
        override fun canReach(contentType: String, code: String) = code !in unreachable
        override suspend fun secondsPerKill(monsterCode: String) = killSeconds[monsterCode]
        override suspend fun owned(code: String) = owned[code] ?: 0

        fun craftable(code: String, skill: String, level: Int, vararg ingredients: Pair<String, Int>, yields: Int = 1) {
            items[code] = Item(
                name = code, code = code, level = level, type = "resource", subtype = "", description = "",
                craft = CraftInfo(skill, level, ingredients.map { SimpleItem(it.first, it.second) }, yields),
                tradeable = true,
            )
        }

        fun resource(code: String, skill: String, level: Int, drop: String, rate: Int = 1) {
            resources += Resource(code, code, skill, level, listOf(DropRate(drop, rate, 1, 1)))
        }

        fun monster(code: String, drop: String, rate: Int, secondsPerKill: Double?, type: String = "normal") {
            monsters += Monster(
                code, code, 10, type, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                minGold = 0, maxGold = 0, drops = listOf(DropRate(drop, rate, 1, 1)),
            )
            killSeconds[code] = secondsPerKill
        }
    }

    @Test
    fun readyWhenOwned() = runTest {
        val world = FakeWorld().apply { owned["copper_ore"] = 10 }
        assertEquals(AcquirePlan.Ready, AcquisitionPlanner(world).plan("copper_ore", 10))
    }

    @Test
    fun multiLevelCraftReturnsFirstMissingLeaf() = runTest {
        val world = FakeWorld().apply {
            craftable("health_potion", "alchemy", 5, "sunflower_extract" to 1, "algae" to 1)
            craftable("sunflower_extract", "alchemy", 1, "sunflower" to 3)
            resource("sunflower_field", "alchemy", 1, "sunflower")
            resource("algae_spot", "fishing", 1, "algae")
            skills["alchemy"] = 10
            owned["algae"] = 5
        }
        val plan = AcquisitionPlanner(world).plan("health_potion", 5)
        assertEquals(AcquireStep.Gather("sunflower_field", "alchemy", "sunflower"), (plan as AcquirePlan.Next).step)

        world.owned["sunflower"] = 15
        assertEquals(AcquireStep.Craft("sunflower_extract", "alchemy", 5), (AcquisitionPlanner(world).plan("health_potion", 5) as AcquirePlan.Next).step)

        world.owned["sunflower_extract"] = 5
        assertEquals(AcquireStep.Craft("health_potion", "alchemy", 5), (AcquisitionPlanner(world).plan("health_potion", 5) as AcquirePlan.Next).step)
    }

    @Test
    fun sharedIngredientIsNotDoubleCounted() = runTest {
        val world = FakeWorld().apply {
            craftable("combo", "cooking", 1, "egg" to 2, "egg_mix" to 1)
            craftable("egg_mix", "cooking", 1, "egg" to 2)
            monster("chicken", "egg", rate = 1, secondsPerKill = 10.0)
            owned["egg"] = 2
        }
        // 2 owned eggs cover the direct ingredient; egg_mix still needs 2 more.
        val plan = AcquisitionPlanner(world).plan("combo", 1)
        assertEquals(AcquireStep.Fight("chicken", "egg"), (plan as AcquirePlan.Next).step)
    }

    @Test
    fun picksCheaperOfFightAndGather() = runTest {
        val world = FakeWorld().apply {
            resource("slow_spot", "mining", 1, "gem", rate = 100)
            monster("gem_golem", "gem", rate = 5, secondsPerKill = 20.0)
        }
        assertEquals(AcquireStep.Fight("gem_golem", "gem"), (AcquisitionPlanner(world).plan("gem", 3) as AcquirePlan.Next).step)

        world.killSeconds["gem_golem"] = null
        assertEquals(AcquireStep.Gather("slow_spot", "mining", "gem"), (AcquisitionPlanner(world).plan("gem", 3) as AcquirePlan.Next).step)
    }

    @Test
    fun favorTargetIsPreferredWhenClose() = runTest {
        val world = FakeWorld().apply {
            resource("a_spot", "fishing", 1, "algae", rate = 10)
            resource("b_spot", "fishing", 1, "algae", rate = 12)
        }
        assertEquals("a_spot", ((AcquisitionPlanner(world).plan("algae", 4) as AcquirePlan.Next).step as AcquireStep.Gather).resourceCode)
        assertEquals(
            "b_spot",
            ((AcquisitionPlanner(world, preferResource = "b_spot").plan("algae", 4) as AcquirePlan.Next).step as AcquireStep.Gather).resourceCode,
        )
    }

    @Test
    fun infeasibleWhenUnbeatableBossOrLowSkill() = runTest {
        val world = FakeWorld().apply {
            monster("wolf", "wolf_bone", rate = 2, secondsPerKill = null)
            monster("king", "wolf_bone", rate = 1, secondsPerKill = 5.0, type = "boss")
            craftable("mithril_bar", "mining", 40, "mithril_ore" to 10)
            resource("mithril_rocks", "mining", 40, "mithril_ore")
            skills["mining"] = 20
        }
        val bone = AcquisitionPlanner(world).plan("wolf_bone", 4)
        assertIs<AcquirePlan.Infeasible>(bone)
        assertTrue("wolf" in bone.reason)
        assertIs<AcquirePlan.Infeasible>(AcquisitionPlanner(world).plan("mithril_bar", 1))
    }

    @Test
    fun unreachableSourceIsSkipped() = runTest {
        val world = FakeWorld().apply {
            resource("sand_spot", "mining", 1, "sand")
            unreachable += "sand_spot"
        }
        assertIs<AcquirePlan.Infeasible>(AcquisitionPlanner(world).plan("sand", 1))
    }

    @Test
    fun bracketChooserPrefersMatchingElseCheapest() {
        fun c(min: Int, max: Int, plan: AcquirePlan) = BracketChooser.Candidate("$min", min, max, plan)
        val ok = AcquirePlan.Next(AcquireStep.Craft("x", "cooking", 1), 500.0)
        val cheap = AcquirePlan.Next(AcquireStep.Craft("y", "cooking", 1), 50.0)
        val no = AcquirePlan.Infeasible("nope")

        assertEquals("21", BracketChooser.choose(25, listOf(c(10, 20, cheap), c(21, 30, ok)))?.offering)
        assertEquals("10", BracketChooser.choose(25, listOf(c(10, 20, cheap), c(21, 30, no), c(31, 40, ok)))?.offering)
        assertEquals("10", BracketChooser.choose(1, listOf(c(10, 20, ok), c(21, 30, cheap)))?.offering)
        assertNull(BracketChooser.choose(1, listOf(c(10, 20, no), c(21, 30, cheap))))
        // Own bracket infeasible: never fall back to a higher bracket, even a cheap one (algae in a salmon bracket).
        assertNull(BracketChooser.choose(1, listOf(c(1, 10, no), c(30, 40, cheap))))
        assertEquals("1", BracketChooser.choose(15, listOf(c(1, 10, ok), c(11, 20, no), c(30, 40, cheap)))?.offering)
        assertEquals("41", BracketChooser.choose(50, listOf(c(10, 20, cheap), c(41, 50, ok)))?.offering)
        assertNull(BracketChooser.choose(25, listOf(c(10, 20, no), c(21, 30, no))))
    }
}
