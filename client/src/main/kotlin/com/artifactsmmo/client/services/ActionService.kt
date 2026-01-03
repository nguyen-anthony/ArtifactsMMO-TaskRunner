package com.artifactsmmo.client.services

import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.*
import io.ktor.client.*

/**
 * Service for character actions (movement, fighting, gathering, crafting, etc.)
 */
class ActionService(client: HttpClient) : BaseApiService(client) {

    /**
     * Move character to a destination
     */
    suspend fun move(characterName: String, x: Int, y: Int): CharacterMovementData {
        val body = mapOf("x" to x, "y" to y)
        return post<ApiResponse<CharacterMovementData>>("/my/$characterName/action/move", body).data
    }

    /**
     * Move character to a destination by map ID
     */
    suspend fun moveToMap(characterName: String, mapId: Int): CharacterMovementData {
        val body = mapOf("map_id" to mapId)
        return post<ApiResponse<CharacterMovementData>>("/my/$characterName/action/move", body).data
    }

    /**
     * Start a fight with a monster
     */
    suspend fun fight(characterName: String, participants: List<String> = emptyList()): CharacterFightData {
        val body = mapOf("participants" to participants)
        return post<ApiResponse<CharacterFightData>>("/my/$characterName/action/fight", body).data
    }

    /**
     * Gather resources at the character's current location
     */
    suspend fun gather(characterName: String): SkillData {
        return post<ApiResponse<SkillData>>("/my/$characterName/action/gathering").data
    }

    /**
     * Craft an item
     */
    suspend fun craft(characterName: String, itemCode: String, quantity: Int = 1): SkillData {
        val body = mapOf("code" to itemCode, "quantity" to quantity)
        return post<ApiResponse<SkillData>>("/my/$characterName/action/crafting", body).data
    }

    /**
     * Rest to restore HP
     */
    suspend fun rest(characterName: String): CharacterRestData {
        return post<ApiResponse<CharacterRestData>>("/my/$characterName/action/rest").data
    }

    /**
     * Equip an item
     */
    suspend fun equip(characterName: String, itemCode: String, slot: String, quantity: Int = 1): EquipmentData {
        val body = mapOf("code" to itemCode, "slot" to slot, "quantity" to quantity)
        return post<ApiResponse<EquipmentData>>("/my/$characterName/action/equip", body).data
    }

    /**
     * Unequip an item
     */
    suspend fun unequip(characterName: String, slot: String, quantity: Int = 1): EquipmentData {
        val body = mapOf("slot" to slot, "quantity" to quantity)
        return post<ApiResponse<EquipmentData>>("/my/$characterName/action/unequip", body).data
    }

    /**
     * Use a consumable item
     */
    suspend fun use(characterName: String, itemCode: String, quantity: Int = 1): UseItemData {
        val body = mapOf("code" to itemCode, "quantity" to quantity)
        return post<ApiResponse<UseItemData>>("/my/$characterName/action/use", body).data
    }

    /**
     * Delete an item from inventory
     */
    suspend fun deleteItem(characterName: String, itemCode: String, quantity: Int): Character {
        val body = mapOf("code" to itemCode, "quantity" to quantity)
        return post<ApiResponse<Character>>("/my/$characterName/action/delete", body).data
    }

    /**
     * Recycle an item
     */
    suspend fun recycle(characterName: String, itemCode: String, quantity: Int = 1): RecyclingData {
        val body = mapOf("code" to itemCode, "quantity" to quantity)
        return post<ApiResponse<RecyclingData>>("/my/$characterName/action/recycling", body).data
    }
}

