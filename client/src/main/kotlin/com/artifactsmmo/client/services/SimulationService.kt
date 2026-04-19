package com.artifactsmmo.client.services

import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.Json

/**
 * Service for combat simulation operations
 */
class SimulationService(client: HttpClient) : BaseApiService(client) {

    private val json = Json {
        explicitNulls = false
    }

    /**
     * Simulate combat with a character's equipment against a monster.
     * Requires a Member or Founder account.
     */
    suspend fun simulateFight(request: CombatSimulationRequest): CombatSimulationData {
        val bodyJson = json.encodeToString(CombatSimulationRequest.serializer(), request)
        return post<ApiResponse<CombatSimulationData>>("/simulation/fight_simulation") {
            setBody(TextContent(bodyJson, ContentType.Application.Json))
        }.data
    }
}
