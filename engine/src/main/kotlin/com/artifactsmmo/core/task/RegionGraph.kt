package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.MapInfo
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/** A tile position on a specific layer. */
data class TilePos(val x: Int, val y: Int, val layer: String) {
    override fun toString() = "($x,$y,$layer)"
}

val MapInfo.pos: TilePos get() = TilePos(x, y, layer)

/**
 * Static walkability model of the game map, built once from the accessible tile list.
 *
 * **Region** = a maximal set of same-layer tiles mutually reachable by plain walking
 * (4-neighbour steps) without using a transition. Regions come from flood-fill over the
 * real tile grid, NOT from tile `name`s — e.g. "Mine" is three disconnected regions.
 *
 * Walkability rules:
 *  - Only tiles present in [tiles] are walkable. Callers pass the access-filtered list:
 *    `blocked` tiles (water, void, walls) and conditional tiles whose achievement isn't met
 *    are absent, so they act as walls.
 *  - `restricted` tiles (e.g. the inner Enchanted Forest) are only walkable from other
 *    restricted tiles, so a step between a restricted and a non-restricted tile is not
 *    allowed. They are reached via a transition instead.
 *
 * Intra-region walking distances are computed with BFS over the real grid (so detours
 * around water count), memoized per source tile — the map is static.
 */
class RegionGraph(tiles: List<MapInfo>) {

    private val byPos: Map<TilePos, MapInfo> = tiles.associateBy { it.pos }
    private val restricted: Set<TilePos> =
        tiles.filter { it.access.type == "restricted" }.mapTo(HashSet()) { it.pos }

    /** Region id per walkable tile. */
    private val regionOfTile: Map<TilePos, Int>

    /** Tiles with an outgoing transition, grouped by the region they sit in. */
    private val transitionSourcesByRegion: Map<Int, List<MapInfo>>

    /** Human-readable label per region (dominant tile name + layer), for logs. */
    val regionLabels: Map<Int, String>

    private val bfsCache = ConcurrentHashMap<TilePos, Map<TilePos, Int>>()

    init {
        val region = HashMap<TilePos, Int>()
        var next = 0
        for (start in byPos.keys) {
            if (start in region) continue
            val id = next++
            val queue = ArrayDeque<TilePos>().apply { add(start) }
            region[start] = id
            while (queue.isNotEmpty()) {
                val p = queue.poll()
                for (n in walkableNeighbours(p)) {
                    if (n !in region) { region[n] = id; queue.add(n) }
                }
            }
        }
        regionOfTile = region

        transitionSourcesByRegion = tiles
            .filter { it.interactions.transition != null }
            .groupBy { region.getValue(it.pos) }

        regionLabels = region.entries.groupBy({ it.value }, { it.key }).mapValues { (id, members) ->
            val name = members.groupingBy { byPos.getValue(it).name }.eachCount().maxBy { it.value }.key
            "${members.first().layer}/$name#$id"
        }
    }

    val regionCount: Int get() = regionLabels.size

    fun regionCount(layer: String): Int = regionLabels.values.count { it.startsWith("$layer/") }

    fun tile(pos: TilePos): MapInfo? = byPos[pos]

    fun regionOf(pos: TilePos): Int? = regionOfTile[pos]

    fun transitionSourcesIn(regionId: Int): List<MapInfo> = transitionSourcesByRegion[regionId].orEmpty()

    /** Names that span more than one region (e.g. "Mine") — diagnostic only. */
    fun fragmentedNames(): Map<String, Int> = regionOfTile.entries
        .groupBy({ byPos.getValue(it.key).layer + "/" + byPos.getValue(it.key).name }, { it.value })
        .mapValues { it.value.toSet().size }
        .filterValues { it > 1 }

    /**
     * Walking distance (tiles) from [from] to every tile reachable on foot. [from] itself may
     * be a tile outside the walkable set (e.g. a character on an unexpected tile); it then
     * starts the search as if it were a normal tile of its restricted-ness.
     */
    fun walkDistances(from: TilePos): Map<TilePos, Int> = bfsCache.getOrPut(from) {
        val dist = HashMap<TilePos, Int>()
        dist[from] = 0
        val queue = ArrayDeque<TilePos>().apply { add(from) }
        while (queue.isNotEmpty()) {
            val p = queue.poll()
            val d = dist.getValue(p)
            for (n in walkableNeighbours(p)) {
                if (n !in dist) { dist[n] = d + 1; queue.add(n) }
            }
        }
        dist
    }

    fun walkDistance(from: TilePos, to: TilePos): Int? = walkDistances(from)[to]

    private fun walkableNeighbours(p: TilePos): List<TilePos> {
        val fromRestricted = p in restricted
        return listOf(
            TilePos(p.x + 1, p.y, p.layer), TilePos(p.x - 1, p.y, p.layer),
            TilePos(p.x, p.y + 1, p.layer), TilePos(p.x, p.y - 1, p.layer),
        ).filter { it in byPos && (it in restricted) == fromRestricted }
    }
}
