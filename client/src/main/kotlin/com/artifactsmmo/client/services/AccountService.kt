package com.artifactsmmo.client.services

import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.ApiResponse
import com.artifactsmmo.client.models.MyDetails
import io.ktor.client.*

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
}
