package com.artifactsmmo.client.services

import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.*
import io.ktor.client.*
import io.ktor.client.request.*

/**
 * Service for character-related operations
 */
class CharacterService(client: HttpClient) : BaseApiService(client) {

    /**
     * Get details of a specific character
     */
    suspend fun getCharacter(name: String): Character {
        return get<ApiResponse<Character>>("/characters/$name").data
    }

    /**
     * Get all your characters
     */
    suspend fun getMyCharacters(): List<Character> {
        return get<ApiResponse<List<Character>>>("/my/characters").data
    }

    /**
     * Create a new character
     */
    suspend fun createCharacter(name: String, skin: String): Character {
        val body = mapOf("name" to name, "skin" to skin)
        return post<ApiResponse<Character>>("/characters/create", body).data
    }

    /**
     * Delete a character
     */
    suspend fun deleteCharacter(name: String): Character {
        val body = mapOf("name" to name)
        return post<ApiResponse<Character>>("/characters/delete", body).data
    }
}

