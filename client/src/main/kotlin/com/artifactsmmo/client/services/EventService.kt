package com.artifactsmmo.client.services

import com.artifactsmmo.client.BaseApiService
import com.artifactsmmo.client.models.ActiveEvent
import com.artifactsmmo.client.models.DataPage
import com.artifactsmmo.client.models.EventDefinition
import io.ktor.client.*

/**
 * Service for game event queries.
 */
class EventService(client: HttpClient) : BaseApiService(client) {

    suspend fun getEvents(page: Int = 1, size: Int = 100): DataPage<EventDefinition> =
        get("/events?page=$page&size=$size")

    suspend fun getActiveEvents(page: Int = 1, size: Int = 100): DataPage<ActiveEvent> =
        get("/events/active?page=$page&size=$size")
}
