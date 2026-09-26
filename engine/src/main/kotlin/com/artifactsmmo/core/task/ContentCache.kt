package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Condition
import com.artifactsmmo.client.models.DataPage
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.MapInfo
import com.artifactsmmo.client.models.Monster
import com.artifactsmmo.client.models.NPCItem
import com.artifactsmmo.client.models.Resource
import com.artifactsmmo.client.services.ContentService
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * In-memory content cache wrapping [ContentService].
 *
 * Two caching tiers:
 *  1. Map tiles — loaded on startup via [preWarmMaps] (from all_maps.json) into a
 *     [RegionGraph]; findNearest and planRoute are pure in-memory with zero API calls.
 *  2. Items — lazy, cache-first with a 24-hour TTL:
 *     - [getItem]/[getItemOrNull]: keyed by item code, max 2 000 entries.
 *     - [getItemsBySkill]: full paginated list per craft skill, max 50 skills.
 *     - [getItemsByType]: full paginated list per item type, max 50 types.
 */
class ContentCache(private val contentService: ContentService) {

    private val mapJson = Json { ignoreUnknownKeys = true }

    // ── Map cache (pre-warmed, no eviction) ──────────────────────────────────

    @Volatile
    private var allMaps: List<MapInfo> = emptyList()

    /** Walkability/region model + route planner, rebuilt on every [preWarmMaps]. */
    @Volatile
    var regionGraph: RegionGraph? = null
        private set

    @Volatile
    var routePlanner: RoutePlanner? = null
        private set

    @Volatile
    private var completedAchievements: Set<String> = emptySet()

    /**
     * Load every map tile and keep the accessible ones in memory, then build the
     * [RegionGraph] / [RoutePlanner].
     *
     * Source: the static [mapFile] (`all_maps.json`, full grid including blocked tiles —
     * the map never changes within a season; refresh the file at season start). Falls back
     * to paging the `/maps` API when the file is missing or unreadable.
     *
     * Access filtering:
     *  - `blocked`: skipped (water, void, walls).
     *  - `standard` / `conditional`: included only if all conditions are met. The only
     *    operator the game uses is `achievement_unlocked`; unknown operators → excluded.
     *  - `restricted`: included (only walkable from other restricted tiles — the region
     *    graph enforces that, e.g. the inner Enchanted Forest behind the gold gate).
     */
    suspend fun preWarmMaps(
        completedAchievementCodes: Set<String> = emptySet(),
        mapFile: File = File("all_maps.json"),
    ) {
        completedAchievements = completedAchievementCodes
        val raw = loadMapFile(mapFile) ?: fetchMapsFromApi()
        setMaps(accessibleTiles(raw, completedAchievementCodes))
    }

    companion object {
        /** The access filter described on [preWarmMaps]. */
        fun accessibleTiles(raw: List<MapInfo>, completedAchievementCodes: Set<String>): List<MapInfo> =
            raw.filter { tile ->
                when (tile.access.type) {
                    "standard", "conditional" -> (tile.access.conditions ?: emptyList()).all { c ->
                        c.operator == "achievement_unlocked" && c.code in completedAchievementCodes
                    }
                    "restricted" -> true
                    else -> false
                }
            }
    }

    /** Replace the tile set directly (tests, or callers that already hold tiles). */
    fun setMaps(maps: List<MapInfo>) {
        allMaps = maps
        val graph = RegionGraph(maps)
        regionGraph = graph
        routePlanner = RoutePlanner(graph)
        val fragmented = graph.fragmentedNames()
        println("Map graph: ${maps.size} accessible tiles, ${graph.regionCount} regions" +
            (if (fragmented.isNotEmpty()) " (split areas: ${fragmented.entries.joinToString { "${it.key}×${it.value}" }})" else ""))
    }

    private fun loadMapFile(file: File): List<MapInfo>? {
        if (!file.isFile) return null
        return runCatching { mapJson.decodeFromString<DataPage<MapInfo>>(file.readText()).data }
            .onFailure { println("Map file ${file.path} unreadable (${it.message}) — falling back to /maps API") }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private suspend fun fetchMapsFromApi(): List<MapInfo> {
        val maps = mutableListOf<MapInfo>()
        var page = 1
        while (true) {
            val result = contentService.getMaps(hideBlockedMaps = true, page = page, size = 100)
            maps += result.data
            if (page >= (result.pages ?: Int.MAX_VALUE) || result.data.size < 100) break
            page++
        }
        return maps
    }

    /** Gate check that only knows about achievements (items/gold assumed obtainable). */
    fun achievementGateAllowed(conditions: List<Condition>): Boolean =
        conditions.all { it.operator != "achievement_unlocked" || it.code in completedAchievements }

    // ── Item caches (lazy, 24-hour TTL) ──────────────────────────────────────

    private val itemCache: Cache<String, Item> = Caffeine.newBuilder()
        .maximumSize(2000)
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build()

    private val itemsBySkillCache: Cache<String, List<Item>> = Caffeine.newBuilder()
        .maximumSize(50)
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build()

    private val itemsByTypeCache: Cache<String, List<Item>> = Caffeine.newBuilder()
        .maximumSize(50)
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build()

    /**
     * Cache for resource lookups by drop code (item code → list of resources that drop it).
     * Keyed by item code. 24-hour TTL — game content does not change within a session.
     * Eliminates repeated GET /resources?drop=<code> calls from findTaskItemSource(),
     * which previously fired on every tick of a TaskMaster items task.
     */
    private val resourcesByDropCache: Cache<String, List<Resource>> = Caffeine.newBuilder()
        .maximumSize(200)
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build()

    /**
     * Cache for monster lookups by code. 24-hour TTL.
     * Used by the local fight simulator so it doesn't fire a GET /monsters/{code}
     * on every simulation call.
     */
    private val monsterCache: Cache<String, Monster> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build()

    // ── Map queries (synchronous after pre-warming) ───────────────────────────

    /**
     * Find the tile matching [contentType]/[contentCode] that is cheapest to actually
     * REACH from [char] (route cost over the region graph, including gates), not merely the
     * closest by straight-line distance — a tile two squares away across water may be many
     * transitions away. [layer] restricts the candidates (null = all layers).
     *
     * [gateAllowed] decides which conditional transitions may be used; the default only
     * rules out gates needing achievements the account lacks. Falls back to Manhattan
     * distance if the graph isn't built or nothing is reachable.
     */
    fun findNearest(
        char: Character,
        contentType: String,
        contentCode: String? = null,
        layer: String? = "overworld",
        gateAllowed: (List<Condition>) -> Boolean = ::achievementGateAllowed,
    ): MapInfo? {
        val candidates = allMaps.filter { map ->
            val content = map.interactions.content
            content != null &&
                content.type == contentType &&
                (contentCode == null || content.code == contentCode) &&
                (layer == null || map.layer == layer)
        }
        if (candidates.isEmpty()) return null
        routePlanner?.planBest(TilePos(char.x, char.y, char.layer), candidates, gateAllowed)
            ?.let { return it.target }
        return candidates.minByOrNull { manhattan(it, char) }
    }

    /**
     * Route to the cheapest-to-reach tile of [contentType]/[contentCode], or null if none is
     * reachable with [gateAllowed] (unlike [findNearest], no straight-line fallback).
     * Returns null too if the graph isn't built — callers should treat that as "unknown".
     */
    fun routeToNearest(
        from: TilePos,
        contentType: String,
        contentCode: String?,
        gateAllowed: (List<Condition>) -> Boolean = ::achievementGateAllowed,
    ): Route? {
        val candidates = allMaps.filter {
            it.interactions.content?.type == contentType && (contentCode == null || it.interactions.content?.code == contentCode)
        }
        return routePlanner?.planBest(from, candidates, gateAllowed)
    }

    val hasRouteGraph: Boolean get() = routePlanner != null

    /** Route from [from] to [target] (null if unreachable or graph not built). */
    fun planRoute(
        from: TilePos,
        target: MapInfo,
        gateAllowed: (List<Condition>) -> Boolean = ::achievementGateAllowed,
        potions: List<PotionOption> = emptyList(),
    ): Route? = routePlanner?.plan(from, target, gateAllowed, potions)

    /** Route from [from] to the cheapest-to-reach bank. */
    fun planRouteToBank(
        from: TilePos,
        gateAllowed: (List<Condition>) -> Boolean = ::achievementGateAllowed,
        potions: List<PotionOption> = emptyList(),
    ): Route? = routePlanner?.planBest(from, allMaps.filter { it.interactions.content?.type == "bank" }, gateAllowed, potions)

    /**
     * Reactive fallback after a 595/596 (server A* found no path): nearest same-layer
     * transition on [char]'s layer whose destination is closest to (toX,toY). Only hit if
     * the region graph over-merged tiles that aren't really mutually walkable.
     */
    fun findNearestSameLayerTransitionToward(
        char: Character,
        toX: Int, toY: Int, toLayer: String
    ): MapInfo? {
        return allMaps
            .filter { tile ->
                tile.layer == char.layer &&
                tile.layer == toLayer &&
                tile.interactions.transition?.layer == toLayer
            }
            .minByOrNull { tile ->
                val dest = tile.interactions.transition!!
                abs(dest.x - toX) + abs(dest.y - toY)
            }
    }

    /**
     * Content codes of [contentType] (e.g. "resource", "monster") with at least one tile the
     * account can reach from spawn. Achievement gates count only if unlocked; gold/key gates
     * are assumed payable (keys can be obtained). Event-only content has no map tile, so it
     * is never included.
     */
    fun reachableContentCodes(contentType: String): Set<String> {
        val planner = routePlanner ?: return emptySet()
        val reachable = planner.reachableTiles(TilePos(0, 0, "overworld"), ::achievementGateAllowed)
        return allMaps.filter { it.interactions.content?.type == contentType && it.pos in reachable }
            .mapNotNullTo(HashSet()) { it.interactions.content?.code }
    }

    fun findNearestBank(char: Character): MapInfo? = findNearest(char, "bank", layer = null)

    /** Look up a tile by its map_id. Used by [TeleportAdvisor] to resolve potion destinations. */
    fun getTileById(mapId: Int): MapInfo? = allMaps.find { it.mapId == mapId }

    /** First accessible tile with this exact content. Useful when no character position exists yet. */
    fun findMapByContent(contentType: String, contentCode: String): MapInfo? =
        allMaps.firstOrNull {
            it.interactions.content?.type == contentType && it.interactions.content?.code == contentCode
        }

    fun findNearestWorkshop(char: Character, skill: String): MapInfo? =
        findNearest(char, "workshop", skill)

    fun findNearestTasksMaster(char: Character, type: String): MapInfo? =
        findNearest(char, "tasks_master", type)

    /** Cheapest-to-reach tile of [contentType]/[contentCode] on any layer. */
    fun findNearestAnyLayer(
        char: Character,
        contentType: String,
        contentCode: String? = null
    ): MapInfo? = findNearest(char, contentType, contentCode, layer = null)

    private fun manhattan(tile: MapInfo, char: Character): Int =
        abs(tile.x - char.x) + abs(tile.y - char.y) + if (tile.layer == char.layer) 0 else 1000

    // ── Item queries (suspend, cached) ────────────────────────────────────────

    /** Fetch item by [code], hitting the cache first. Throws on API failure. */
    suspend fun getItem(code: String): Item {
        itemCache.getIfPresent(code)?.let { return it }
        val item = contentService.getItem(code)
        itemCache.put(code, item)
        return item
    }

    /** Like [getItem] but returns null instead of throwing on failure. */
    suspend fun getItemOrNull(code: String): Item? {
        return try { getItem(code) } catch (_: Exception) { null }
    }

    /**
     * Return all items craftable with [skill] (all pages merged), hitting the
     * cache first. Results are never filtered by level — callers filter in memory.
     */
    suspend fun getItemsBySkill(skill: String): List<Item> {
        itemsBySkillCache.getIfPresent(skill)?.let { return it }
        val items = mutableListOf<Item>()
        var page = 1
        while (true) {
            val result = contentService.getItems(craftSkill = skill, page = page, size = 100)
            items.addAll(result.data)
            if (page >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            page++
        }
        itemsBySkillCache.put(skill, items)
        return items
    }

    /**
     * Return all items of [type] (all pages merged), hitting the cache first.
     * Results are never filtered by level — callers filter by [Item.level] in memory.
     */
    suspend fun getItemsByType(type: String): List<Item> {
        itemsByTypeCache.getIfPresent(type)?.let { return it }
        val items = mutableListOf<Item>()
        var page = 1
        while (true) {
            val result = contentService.getItems(type = type, page = page, size = 100)
            items.addAll(result.data)
            if (page >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            page++
        }
        itemsByTypeCache.put(type, items)
        return items
    }

    /**
     * Return all resources that drop [dropCode] (all pages merged), hitting the cache first.
     * Replaces direct GET /resources?drop=<code> calls in findTaskItemSource(), which fired
     * on every TaskMaster items-task tick for the task item and each of its ingredients.
     * After the first call per item code the result is free (pure in-memory) for 24 hours.
     */
    suspend fun getResourcesByDrop(dropCode: String): List<Resource> {
        resourcesByDropCache.getIfPresent(dropCode)?.let { return it }
        val resources = mutableListOf<Resource>()
        var page = 1
        while (true) {
            val result = contentService.getResources(drop = dropCode, page = page, size = 100)
            resources.addAll(result.data)
            if (page >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            page++
        }
        resourcesByDropCache.put(dropCode, resources)
        return resources
    }

    /** Fetch monster by [code], hitting the cache first. Throws on API failure. */
    suspend fun getMonster(code: String): Monster {
        monsterCache.getIfPresent(code)?.let { return it }
        val monster = contentService.getMonster(code)
        monsterCache.put(code, monster)
        return monster
    }

    /** Like [getMonster] but returns null instead of throwing on failure. */
    suspend fun getMonsterOrNull(code: String): Monster? {
        return try { getMonster(code) } catch (_: Exception) { null }
    }

    // ── Resource cache (lazy, 24-hour TTL) ───────────────────────────────────

    private val resourceCache: Cache<String, Resource> = Caffeine.newBuilder()
        .maximumSize(500)
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build()

    /** Fetch resource by [code], hitting the cache first. Throws on API failure. */
    suspend fun getResource(code: String): Resource {
        resourceCache.getIfPresent(code)?.let { return it }
        val resource = contentService.getResource(code)
        resourceCache.put(code, resource)
        return resource
    }

    // ── NPC item cache (lazy, 24-hour TTL) ───────────────────────────────────
    /**
     * Reverse-lookup map: item code → list of [NPCItem] entries from any NPC that sells it.
     * Populated lazily on first call to [getNpcItemsByCode] by fetching all NPCs then their
     * item catalogues. The entire map is rebuilt together so we only page through NPCs once.
     * Keyed by a sentinel string in the Caffeine cache to reuse the same TTL machinery.
     */
    private val npcItemsByCodeCache: Cache<String, Map<String, List<NPCItem>>> = Caffeine.newBuilder()
        .maximumSize(1)
        .expireAfterWrite(24, TimeUnit.HOURS)
        .build()

    private val NPC_CACHE_KEY = "all"

    /**
     * Return all [NPCItem] entries (across every NPC) that have this [itemCode] for sale
     * (i.e. have a non-null [NPCItem.buyPrice]). Returns an empty list if no NPC sells it.
     */
    suspend fun getNpcItemsByCode(itemCode: String): List<NPCItem> {
        return getNpcReverseMap()[itemCode] ?: emptyList()
    }

    /**
     * Build (or return cached) the full reverse map: item code → sellable NPCItem entries.
     */
    private suspend fun getNpcReverseMap(): Map<String, List<NPCItem>> {
        npcItemsByCodeCache.getIfPresent(NPC_CACHE_KEY)?.let { return it }

        val reverseMap = mutableMapOf<String, MutableList<NPCItem>>()

        // Page through all NPCs
        var npcPage = 1
        while (true) {
            val npcResult = try {
                contentService.getNPCs(page = npcPage, size = 100)
            } catch (_: Exception) { break }

            for (npc in npcResult.data) {
                // Page through items this NPC sells
                var itemPage = 1
                while (true) {
                    val itemResult = try {
                        contentService.getNPCItems(npc.code, page = itemPage, size = 100)
                    } catch (_: Exception) { break }

                    for (npcItem in itemResult.data) {
                        // Only include entries the character can BUY (has a buyPrice)
                        if (npcItem.buyPrice != null) {
                            reverseMap.getOrPut(npcItem.code) { mutableListOf() }.add(npcItem)
                        }
                    }

                    if (itemPage >= (itemResult.pages ?: Int.MAX_VALUE)) break
                    if (itemResult.data.size < 100) break
                    itemPage++
                }
            }

            if (npcPage >= (npcResult.pages ?: Int.MAX_VALUE)) break
            if (npcResult.data.size < 100) break
            npcPage++
        }

        val immutable: Map<String, List<NPCItem>> = reverseMap
        npcItemsByCodeCache.put(NPC_CACHE_KEY, immutable)
        return immutable
    }
}
