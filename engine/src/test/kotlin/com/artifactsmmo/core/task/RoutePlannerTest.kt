package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.DataPage
import com.artifactsmmo.client.models.MapInfo
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Route planning against the real, complete map (`all_maps.json` in the repo root,
 * blocked tiles included). These are the multi-hop cases the old greedy navigation got wrong.
 */
class RoutePlannerTest {

    private val rawTiles: List<MapInfo> by lazy {
        val file = listOf(File("../all_maps.json"), File("all_maps.json")).first { it.isFile }
        Json { ignoreUnknownKeys = true }.decodeFromString<DataPage<MapInfo>>(file.readText()).data
    }

    private val allAchievements = setOf("tasks_farmer", "clean_the_lab", "secure_the_island")

    private fun graph(achievements: Set<String> = allAchievements) =
        RegionGraph(ContentCache.accessibleTiles(rawTiles, achievements))

    private fun planner(achievements: Set<String> = allAchievements) = RoutePlanner(graph(achievements))

    private fun tile(x: Int, y: Int, layer: String) =
        rawTiles.single { it.x == x && it.y == y && it.layer == layer }

    private fun content(code: String) = rawTiles.filter { it.interactions.content?.code == code }

    private val noKeys: (List<com.artifactsmmo.client.models.Condition>) -> Boolean =
        { cs -> cs.none { it.operator == "cost" && it.code != "gold" } }

    private val start = TilePos(0, 0, "overworld")

    private fun Route.sources() = transitions.map { it.source.pos }

    @Test
    fun regionsComeFromConnectivityNotNames() {
        val g = graph()
        // Main landmass, Sandwhisper Isle, and the restricted inner Enchanted Forest.
        assertEquals(3, g.regionCount("overworld"))
        assertEquals(9, g.regionCount("underground"))
        assertEquals(3, g.regionCount("interior"))
        assertEquals(3, g.fragmentedNames()["underground/Mine"])
    }

    @Test
    fun restrictedTilesAreNotWalkableFromOutside() {
        val g = graph()
        val outside = g.regionOf(TilePos(-4, 9, "overworld"))
        val inside = g.regionOf(TilePos(-5, 8, "overworld"))
        assertTrue(outside != inside)
    }

    @Test
    fun overworldGateThenUnderground() {
        // Forest → gold gate → Sandwhisper Isle → Sandwhisper Mine
        val route = assertNotNull(planner().plan(start, tile(-5, 18, "underground")))
        assertEquals(
            listOf(TilePos(2, 16, "overworld"), TilePos(-4, 18, "overworld")),
            route.sources(),
        )
    }

    @Test
    fun chainedSubLayerTransitionsIntoKeyGatedLava() {
        // Forest → Abandoned House (interior) → underground → Lava Underground → key gate → god_of_the_sun
        val target = tile(7, 1, "underground")
        val route = assertNotNull(planner().plan(start, target))
        val layers = route.transitions.map { it.destination.layer }
        assertEquals(listOf("interior", "underground", "underground", "underground"), layers)
        assertTrue(route.transitions.last().conditions.any { it.code == "sonnengott_key" })

        // Without the key there is no way in.
        assertNull(planner().plan(start, target, gateAllowed = noKeys))
    }

    @Test
    fun keyGatedMineFragment() {
        val priestess = tile(1, -4, "underground")
        val route = assertNotNull(planner().plan(start, priestess))
        assertEquals(TilePos(3, -4, "underground"), route.sources().last())
        assertNull(planner().plan(start, priestess, gateAllowed = noKeys))
    }

    @Test
    fun restrictedEnchantedForestUsesGoldGate() {
        val route = assertNotNull(planner().plan(start, tile(-5, 8, "overworld")))
        assertEquals(listOf(TilePos(-4, 9, "overworld")), route.sources())
    }

    @Test
    fun enchantedPotionTeleportsStraightIntoInnerForest() {
        // Resolve the real potion's landing tile from the potion + map files (map_id 715).
        val potionFile = listOf(File("../all_teleport_potions.json"), File("all_teleport_potions.json")).first { it.isFile }
        val mapId = Regex(""""code":\s*"enchanted_potion".*?"code":\s*"teleport",\s*"value":\s*(\d+)""", RegexOption.DOT_MATCHES_ALL)
            .find(potionFile.readText())!!.groupValues[1].toInt()
        val landing = rawTiles.single { it.mapId == mapId }
        assertEquals("restricted", landing.access.type)  // lands inside the inner forest

        val dryad = tile(-5, 8, "overworld")
        val potion = PotionOption("enchanted_potion", landing.pos, fromBank = false)

        // From spawn the potion beats walking to the gold gate: no transitions, no gold spent.
        val fromSpawn = assertNotNull(planner().plan(start, dryad, potions = listOf(potion)))
        assertEquals(RouteStep.Potion("enchanted_potion", landing.pos, false), fromSpawn.steps.single())
        assertTrue(fromSpawn.transitions.isEmpty())

        // Standing next to the gate, paying the gate is cheaper than drinking the potion.
        val atGate = assertNotNull(planner().plan(TilePos(-4, 9, "overworld"), dryad, potions = listOf(potion)))
        assertEquals(listOf(TilePos(-4, 9, "overworld")), atGate.sources())
        assertTrue(atGate.steps.none { it is RouteStep.Potion })

        // With the gate unaffordable, the potion is the only way in, even from right beside the gate.
        val noGold: (List<com.artifactsmmo.client.models.Condition>) -> Boolean = { cs -> cs.none { it.code == "gold" } }
        assertNull(planner().plan(TilePos(-4, 9, "overworld"), dryad, gateAllowed = noGold))
        val potionOnly = assertNotNull(planner().plan(TilePos(-4, 9, "overworld"), dryad, gateAllowed = noGold, potions = listOf(potion)))
        assertEquals(RouteStep.Potion("enchanted_potion", landing.pos, false), potionOnly.steps.single())
    }

    @Test
    fun reachableContentDependsOnAchievements() {
        fun npcs(ach: Set<String>): Set<String> {
            val tiles = ContentCache.accessibleTiles(rawTiles, ach)
            val reach = RoutePlanner(RegionGraph(tiles)).reachableTiles(start) { cs ->
                cs.all { it.operator != "achievement_unlocked" || it.code in ach }
            }
            return tiles.filter { it.interactions.content?.type == "npc" && it.pos in reach }
                .mapNotNull { it.interactions.content?.code }.toSet()
        }
        // Isle NPCs are behind a gold gate only, so reachable without achievements.
        assertTrue("sandwhisper_trader" in npcs(emptySet()))
        // The tasks_trader tile only exists with the tasks_farmer achievement.
        assertTrue("tasks_trader" !in npcs(emptySet()))
        assertTrue("tasks_trader" in npcs(allAchievements))
        // Deep underground (interior -> underground -> lava) is reachable.
        assertTrue("sorceress" in npcs(emptySet()))
    }

    @Test
    fun returnFromDeepUndergroundToBank() {
        val banks = content("bank")
        val route = assertNotNull(planner().planBest(TilePos(7, 1, "underground"), banks))
        assertEquals("overworld", route.target.layer)
        assertTrue(route.transitions.isNotEmpty())
    }

    @Test
    fun nearestBankIsByRouteNotDistance() {
        val banks = content("bank")
        val onIsle = TilePos(-2, 20, "overworld")
        // With the island achievement the isle bank is right there.
        val withAch = assertNotNull(planner().planBest(onIsle, banks))
        assertEquals(TilePos(-2, 19, "overworld"), withAch.target.pos)
        assertTrue(withAch.steps.isEmpty())
        // Without it, the bank tile is a wall: cross back over the gold gate to the mainland.
        val without = assertNotNull(planner(emptySet()).planBest(onIsle, banks.filter { it.access.type != "conditional" }))
        assertEquals(listOf(TilePos(-2, 21, "overworld")), without.sources())
    }

    @Test
    fun potionIsUsedOnlyWhenItSavesEnough() {
        val target = tile(-5, 18, "underground")
        val nearTarget = PotionOption("isle_potion", TilePos(-4, 18, "overworld"), fromBank = false)
        val useless = PotionOption("home_potion", TilePos(0, 1, "overworld"), fromBank = false)
        val route = assertNotNull(planner().plan(start, target, potions = listOf(nearTarget, useless)))
        assertEquals(RouteStep.Potion("isle_potion", nearTarget.landing, false), route.steps.first())
        assertEquals(listOf(TilePos(-4, 18, "overworld")), route.sources())

        val walkOnly = assertNotNull(planner().plan(start, target, potions = listOf(useless)))
        assertTrue(walkOnly.steps.none { it is RouteStep.Potion })
    }
}
