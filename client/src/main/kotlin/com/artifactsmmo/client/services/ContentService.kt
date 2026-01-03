package com.artifactsmmo.client.services

import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.*
import io.ktor.client.*
import io.ktor.client.request.parameter

/**
 * Service for game content queries (items, monsters, resources, maps, etc.)
 */
class ContentService(client: HttpClient) : BaseApiService(client) {

    /**
     * Get all items with optional filters
     */
    suspend fun getItems(
        name: String? = null,
        minLevel: Int? = null,
        maxLevel: Int? = null,
        type: String? = null,
        craftSkill: String? = null,
        craftMaterial: String? = null,
        page: Int = 1,
        size: Int = 50
    ): DataPage<Item> {
        return get<DataPage<Item>>("/items") {
            name?.let { parameter("name", it) }
            minLevel?.let { parameter("min_level", it) }
            maxLevel?.let { parameter("max_level", it) }
            type?.let { parameter("type", it) }
            craftSkill?.let { parameter("craft_skill", it) }
            craftMaterial?.let { parameter("craft_material", it) }
            parameter("page", page)
            parameter("size", size)
        }
    }

    /**
     * Get a specific item by code
     */
    suspend fun getItem(code: String): Item {
        return get<ApiResponse<Item>>("/items/$code").data
    }

    /**
     * Get all monsters with optional filters
     */
    suspend fun getMonsters(
        name: String? = null,
        minLevel: Int? = null,
        maxLevel: Int? = null,
        drop: String? = null,
        page: Int = 1,
        size: Int = 50
    ): DataPage<Monster> {
        return get<DataPage<Monster>>("/monsters") {
            name?.let { parameter("name", it) }
            minLevel?.let { parameter("min_level", it) }
            maxLevel?.let { parameter("max_level", it) }
            drop?.let { parameter("drop", it) }
            parameter("page", page)
            parameter("size", size)
        }
    }

    /**
     * Get a specific monster by code
     */
    suspend fun getMonster(code: String): Monster {
        return get<ApiResponse<Monster>>("/monsters/$code").data
    }

    /**
     * Get all resources with optional filters
     */
    suspend fun getResources(
        minLevel: Int? = null,
        maxLevel: Int? = null,
        skill: String? = null,
        drop: String? = null,
        page: Int = 1,
        size: Int = 50
    ): DataPage<Resource> {
        return get<DataPage<Resource>>("/resources") {
            minLevel?.let { parameter("min_level", it) }
            maxLevel?.let { parameter("max_level", it) }
            skill?.let { parameter("skill", it) }
            drop?.let { parameter("drop", it) }
            parameter("page", page)
            parameter("size", size)
        }
    }

    /**
     * Get a specific resource by code
     */
    suspend fun getResource(code: String): Resource {
        return get<ApiResponse<Resource>>("/resources/$code").data
    }

    /**
     * Get all maps with optional filters
     */
    suspend fun getMaps(
        layer: String? = null,
        contentType: String? = null,
        contentCode: String? = null,
        hideBlockedMaps: Boolean = false,
        page: Int = 1,
        size: Int = 50
    ): DataPage<MapInfo> {
        return get<DataPage<MapInfo>>("/maps") {
            layer?.let { parameter("layer", it) }
            contentType?.let { parameter("content_type", it) }
            contentCode?.let { parameter("content_code", it) }
            parameter("hide_blocked_maps", hideBlockedMaps)
            parameter("page", page)
            parameter("size", size)
        }
    }

    /**
     * Get a map by coordinates
     */
    suspend fun getMapByPosition(layer: String, x: Int, y: Int): MapInfo {
        return get<ApiResponse<MapInfo>>("/maps/$layer/$x/$y").data
    }

    /**
     * Get a map by ID
     */
    suspend fun getMapById(mapId: Int): MapInfo {
        return get<ApiResponse<MapInfo>>("/maps/id/$mapId").data
    }

    /**
     * Get all NPCs with optional filters
     */
    suspend fun getNPCs(
        name: String? = null,
        type: String? = null,
        page: Int = 1,
        size: Int = 50
    ): DataPage<NPC> {
        return get<DataPage<NPC>>("/npcs/details") {
            name?.let { parameter("name", it) }
            type?.let { parameter("type", it) }
            parameter("page", page)
            parameter("size", size)
        }
    }

    /**
     * Get a specific NPC by code
     */
    suspend fun getNPC(code: String): NPC {
        return get<ApiResponse<NPC>>("/npcs/details/$code").data
    }

    /**
     * Get items sold/bought by an NPC
     */
    suspend fun getNPCItems(
        npcCode: String,
        page: Int = 1,
        size: Int = 50
    ): DataPage<NPCItem> {
        return get<DataPage<NPCItem>>("/npcs/items/$npcCode") {
            parameter("page", page)
            parameter("size", size)
        }
    }
}

