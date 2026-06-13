package com.artifactsmmo.client.services

import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.Achievement
import com.artifactsmmo.client.models.ApiResponse
import com.artifactsmmo.client.models.DataPage
import com.artifactsmmo.client.models.MyDetails
import io.ktor.client.*
import io.ktor.client.request.parameter

/**
 * Service for account-level operations (requires authentication).
 */
class AccountService(client: HttpClient) : BaseApiService(client) {

    /**
     * Get the authenticated account's details (username, status, gems, etc.).
     * Endpoint: GET /my/details
     */
    suspend fun getMyDetails(): MyDetails {
        return get<ApiResponse<MyDetails>>("/my/details").data
    }

    /**
     * Get all achievements for the given account username, paginated.
     * Endpoint: GET /accounts/{username}/achievements
     */
    suspend fun getAccountAchievements(username: String, page: Int = 1, size: Int = 50): DataPage<Achievement> {
        return get<DataPage<Achievement>>("/accounts/$username/achievements") {
            parameter("page", page)
            parameter("size", size)
        }
    }

    /**
     * Fetch every achievement for [username] (all pages) and return the set of
     * codes that have been completed (non-null completed_at).
     */
    suspend fun getCompletedAchievementCodes(username: String): Set<String> {
        val completed = mutableSetOf<String>()
        var page = 1
        while (true) {
            val result = try {
                getAccountAchievements(username, page = page, size = 50)
            } catch (_: Exception) { break }

            for (achievement in result.data) {
                if (achievement.isCompleted) completed.add(achievement.code)
            }

            if (page >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 50) break
            page++
        }
        return completed
    }
}
