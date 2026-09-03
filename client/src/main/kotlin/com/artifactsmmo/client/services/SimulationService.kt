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
        val result = post<ApiResponse<CombatSimulationData>>("/simulation/fight") {
            setBody(TextContent(bodyJson, ContentType.Application.Json))
        }.data
        // The simulation endpoint returns percentage points (for example 100.0 for 100%),
        // while the application uses a 0.0..1.0 fraction for thresholds and display math.
        return result.copy(
            winrate = normalizeSimulationWinRate(result.winrate, result.wins, result.losses)
        )
    }
}

internal fun normalizeSimulationWinRate(value: Double, wins: Int, losses: Int): Double {
    val total = wins + losses
    if (total > 0) return wins.toDouble() / total
    return (if (value > 1.0) value / 100.0 else value).coerceIn(0.0, 1.0)
}
