package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.MapInfo
import com.artifactsmmo.client.models.Monster
import com.artifactsmmo.client.models.NPCItem
import com.artifactsmmo.client.models.Resource
import com.artifactsmmo.client.services.ContentService
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * In-memory content cache wrapping [ContentService].
 *
 * Two caching tiers:
 *  1. Map tiles — pre-warmed on startup via [preWarmMaps]; all findNearest* calls are
 *     pure in-memory afterward with zero API calls.
 *  2. Items — lazy, cache-first with a 24-hour TTL:
 *     - [getItem]/[getItemOrNull]: keyed by item code, max 2 000 entries.
 *     - [getItemsBySkill]: full paginated list per craft skill, max 50 skills.
 *     - [getItemsByType]: full paginated list per item type, max 50 types.
 */
class ContentCache(private val contentService: ContentService) {

    // ── Map cache (pre-warmed, no eviction) ──────────────────────────────────

    @Volatile
    private var allMaps: List<MapInfo> = emptyList()

    /**
     * Maps (destX, destY, destLayer) → the tile you stand on to reach that destination.
     * "To be deposited at (dx,dy,dl) after a transition, stand on this tile."
     *
     * Built during [preWarmMaps]. Used by:
     *  - [findSameLayerTransitionTo]: proactive detection of same-layer transition gates.
     *  - [findNearestSameLayerTransitionToward]: reactive fallback after 595/596 errors.
     */
    @Volatile
    private var transitionDestToSource: Map<Triple<Int,Int,String>, MapInfo> = emptyMap()

    /**
     * Fetch every accessible map tile from the API (all pages) and store them in memory.
     *
     * Tiles with access type "blocked" are excluded via the API's hideBlockedMaps filter.
     * Tiles with access type "conditional" are included only if the account has met every
     * condition on that tile. The only condition operator currently used by the game is
     * "achievement_unlocked" — a tile is accessible when its required achievement code is
     * present in [completedAchievementCodes].
     *
     * Tiles whose conditions cannot be evaluated (unknown operator) are excluded by default
     * to avoid 496 errors.
     *
     * Should be called once during application startup before any findNearest* method is used.
     *
     * @param completedAchievementCodes Set of achievement codes the account has completed.
     *   Pass an empty set to treat all conditional tiles as inaccessible (safe fallback).
     */
    suspend fun preWarmMaps(completedAchievementCodes: Set<String> = emptySet()) {
        val maps = mutableListOf<MapInfo>()
        var page = 1
        while (true) {
            val result = contentService.getMaps(hideBlockedMaps = true, page = page, size = 100)
            for (tile in result.data) {
                when (tile.access.type) {
                    "standard", "conditional" -> {
                        val conditions = tile.access.conditions ?: emptyList()
                        // Include the tile only if ALL conditions are satisfied.
                        // Standard tiles with no conditions pass (empty list → all {} = true).
                        val allMet = conditions.all { condition ->
                            when (condition.operator) {
                                "achievement_unlocked" -> condition.code in completedAchievementCodes
                                else -> false  // unknown operator — exclude to be safe
                            }
                        }
                        if (allMet) maps.add(tile)
                    }
                    "restricted" -> {
                        // Restricted tiles are only walkable from other restricted maps
                        // (they're behind a transition, e.g. boss instances like the Lich Tomb).
                        // We still include them so findNearest() can return the tile as a target
                        // for navigateToTile(), which will route through the transition.
                        // A* moveTo() to these tiles from a non-restricted layer will fail — but
                        // navigateToTile handles that by transitioning first.
                        maps.add(tile)
                    }
                    // "blocked" and anything else — skip
                }
            }
            if (page >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            page++
        }
        allMaps = maps

        // Build the transition destination lookup — used for same-layer transition detection.
        transitionDestToSource = maps
            .filter { it.interactions.transition != null }
            .associate { tile ->
                val t = tile.interactions.transition!!
                Triple(t.x, t.y, t.layer) to tile
            }
    }

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
     * Find the nearest map tile matching [contentType] and optionally [contentCode]
     * using Manhattan distance. Returns null if no match or maps not yet loaded.
     *
     * By default only tiles on the overworld layer are searched. Pass [layer] to
     * restrict results to a specific layer (e.g. "underground" or "interior").
     * Pass null to search all layers.
     */
    fun findNearest(
        char: Character,
        contentType: String,
        contentCode: String? = null,
        layer: String? = "overworld"
    ): MapInfo? {
        return allMaps
            .filter { map ->
                val content = map.interactions.content
                content != null &&
                content.type == contentType &&
                (contentCode == null || content.code == contentCode) &&
                (layer == null || map.layer == layer)
            }
            .minByOrNull { abs(it.x - char.x) + abs(it.y - char.y) }
    }

    /**
     * Find the overworld transition tile that leads to [targetLayer] and whose destination
     * coordinates are closest (by Manhattan distance) to [destX]/[destY] on that layer.
     *
     * When multiple transitions lead to the same layer (e.g. two separate interior
     * entrances), this selects the one that deposits the character nearest to the actual
     * target, avoiding wrong-building situations.
     *
     * When [destX]/[destY] are null (destination unknown), falls back to the transition
     * tile nearest to the character by Manhattan distance.
     */
    fun findTransitionTile(
        char: Character,
        targetLayer: String,
        destX: Int? = null,
        destY: Int? = null
    ): MapInfo? {
        // Always pick the transition whose destination lands closest to the target —
        // regardless of whether it has a condition. This is essential for correctness:
        // sub-layers can contain multiple disconnected regions, and each region is
        // reachable only through a specific transition. Choosing a DIFFERENT transition
        // just because it's "free" will drop the character in the wrong region, from
        // which A* movement to the true target is impossible (595 no path).
        //
        // If the closest transition has a condition (e.g. a key item cost), the caller
        // must satisfy it — see [ActionHelper.satisfyTransitionConditions]. If the
        // condition can't be satisfied, the caller should abort the task.
        val candidates = allMaps.filter { map ->
            map.layer == "overworld" &&
            map.interactions.transition?.layer == targetLayer
        }
        return if (destX != null && destY != null) {
            candidates.minByOrNull { map ->
                val tx = map.interactions.transition!!.x
                val ty = map.interactions.transition!!.y
                abs(tx - destX) + abs(ty - destY)
            }
        } else {
            candidates.minByOrNull { abs(it.x - char.x) + abs(it.y - char.y) }
        }
    }

    /**
     * Find the nearest tile on [char]'s current layer that has an outgoing transition
     * back to the OVERWORLD — the hub layer that all sub-layers connect through.
     *
     * Only overworld exits are considered (not "any other layer"). Sub-layer regions
     * (interior, underground) can have multiple exit tiles leading to different layers;
     * picking the nearest one regardless of destination layer previously caused characters
     * to wander into the wrong layer (e.g. exiting interior into underground when overworld
     * was needed), producing a routing loop that never reached the actual destination.
     *
     * Restricting to overworld exits matches [getTransitionCosts]'s existing assumption
     * that sub-layer exits always route through overworld.
     *
     * Uses raw Manhattan distance to the character.
     */
    fun findExitTransitionTile(char: Character): MapInfo? {
        return allMaps
            .filter { map ->
                map.layer == char.layer &&
                map.interactions.transition?.layer == "overworld"
            }
            .minByOrNull { abs(it.x - char.x) + abs(it.y - char.y) }
    }

    /**
     * If the tile at (toX, toY, toLayer) can only be reached via a same-layer transition
     * (i.e. it is a known transition destination AND the source tile is on the same layer),
     * returns that source tile — the tile the character must stand on to use the transition.
     *
     * Returns null if the tile is freely walkable or if it is a destination of a cross-layer
     * return transition (e.g. underground→overworld return deposits at an overworld tile, but
     * that overworld tile is still freely walkable from the rest of the overworld).
     *
     * Used by [ActionHelper.navigateToTile] for proactive same-layer transition detection.
     */
    fun findSameLayerTransitionTo(toX: Int, toY: Int, toLayer: String): MapInfo? {
        val sourceTile = transitionDestToSource[Triple(toX, toY, toLayer)] ?: return null
        // Only a same-layer gate if the source tile is on the SAME layer as the destination.
        // Cross-layer return transitions (e.g. interior(-3,12)→overworld(-3,12)) have a source
        // on a different layer — those overworld tiles are freely walkable.
        return if (sourceTile.layer == toLayer) sourceTile else null
    }

    /**
     * When a moveTo fails with 595 (no path) or 596 (map blocked), search for the nearest
     * same-layer transition tile on [char]'s layer whose destination is closest to (toX,toY).
     *
     * This is the reactive fallback in [ActionHelper.navigateToTile]: the character tried
     * to walk somewhere and hit a wall, water, or gate that requires a transition to cross.
     *
     * Returns null if no same-layer transition is found.
     */
    fun findNearestSameLayerTransitionToward(
        char: Character,
        toX: Int, toY: Int, toLayer: String
    ): MapInfo? {
        return allMaps
            .filter { tile ->
                tile.layer == char.layer &&                         // reachable by character
                tile.interactions.transition?.layer == toLayer &&   // leads to target layer
                tile.layer == toLayer                              // same-layer (source == dest layer)
            }
            .minByOrNull { tile ->
                val dest = tile.interactions.transition!!
                abs(dest.x - toX) + abs(dest.y - toY)
            }
    }

    fun findNearestBank(char: Character): MapInfo? = findNearest(char, "bank")

    /** Find nearest accessible bank from an arbitrary (x,y,layer), not the character's position. */
    fun findNearestBankFrom(x: Int, y: Int, layer: String = "overworld"): MapInfo? {
        return allMaps
            .filter { it.interactions.content?.type == "bank" && it.layer == layer }
            .minByOrNull { abs(it.x - x) + abs(it.y - y) }
    }

    /** Look up a tile by its map_id. Used by [TeleportAdvisor] to resolve potion destinations. */
    fun getTileById(mapId: Int): MapInfo? = allMaps.find { it.mapId == mapId }

    fun findNearestWorkshop(char: Character, skill: String): MapInfo? =
        findNearest(char, "workshop", skill)

    fun findNearestTasksMaster(char: Character, type: String): MapInfo? =
        findNearest(char, "tasks_master", type)

    /**
     * Find the nearest map tile of [contentType]/[contentCode] on any layer (overworld,
     * underground, or interior). Returns the tile along with the layer it lives on so
     * callers can decide whether a map transition is required.
     */
    fun findNearestAnyLayer(
        char: Character,
        contentType: String,
        contentCode: String? = null
    ): MapInfo? {
        return findNearest(char, contentType, contentCode, layer = null)
    }

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
