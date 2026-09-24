package com.artifactsmmo.client.services

import com.artifactsmmo.client.ApiTransport
import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.DataPage
import com.artifactsmmo.client.models.Raid
import io.ktor.client.HttpClient

/** Service for scheduled raid definitions and active raid state. */
class RaidService(transport: ApiTransport) : BaseApiService(transport) {
    suspend fun getRaids(page: Int = 1, size: Int = 50, active: Boolean? = null): DataPage<Raid> {
        val activeParam = active?.let { "&active=$it" } ?: ""
        return get("/raids?page=$page&size=$size$activeParam")
    }

    suspend fun getRaid(code: String): Raid = get("/raids/$code")
}
