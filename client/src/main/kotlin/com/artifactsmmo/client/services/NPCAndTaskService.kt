package com.artifactsmmo.client.services

import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.*
import io.ktor.client.*

/**
 * Service for NPC trading operations
 */
class NPCService(client: HttpClient) : BaseApiService(client) {

    /**
     * Buy an item from an NPC
     */
    suspend fun buyItem(characterName: String, itemCode: String, quantity: Int): NPCTransactionData {
        val body = mapOf("code" to itemCode, "quantity" to quantity)
        return post<ApiResponse<NPCTransactionData>>("/my/$characterName/action/npc/buy", body).data
    }

    /**
     * Sell an item to an NPC
     */
    suspend fun sellItem(characterName: String, itemCode: String, quantity: Int): NPCTransactionData {
        val body = mapOf("code" to itemCode, "quantity" to quantity)
        return post<ApiResponse<NPCTransactionData>>("/my/$characterName/action/npc/sell", body).data
    }
}

/**
 * Service for task-related operations
 */
class TaskService(client: HttpClient) : BaseApiService(client) {

    /**
     * Accept a new task
     */
    suspend fun acceptNewTask(characterName: String): TaskData {
        return post<ApiResponse<TaskData>>("/my/$characterName/action/task/new").data
    }

    /**
     * Complete a task
     */
    suspend fun completeTask(characterName: String): RewardData {
        return post<ApiResponse<RewardData>>("/my/$characterName/action/task/complete").data
    }

    /**
     * Exchange task coins for rewards
     */
    suspend fun exchangeTaskCoins(characterName: String): RewardData {
        return post<ApiResponse<RewardData>>("/my/$characterName/action/task/exchange").data
    }

    /**
     * Trade items with a task master
     */
    suspend fun tradeTask(characterName: String, itemCode: String, quantity: Int): Character {
        val body = mapOf("code" to itemCode, "quantity" to quantity)
        return post<ApiResponse<Character>>("/my/$characterName/action/task/trade", body).data
    }

    /**
     * Cancel a task
     */
    suspend fun cancelTask(characterName: String): Character {
        return post<ApiResponse<Character>>("/my/$characterName/action/task/cancel").data
    }
}

