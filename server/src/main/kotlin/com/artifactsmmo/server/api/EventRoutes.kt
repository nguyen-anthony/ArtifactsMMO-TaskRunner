package com.artifactsmmo.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.time.Instant

/** A live game event: what it spawned ([type] npc/monster/resource, [contentCode]) and where. */
@Serializable
data class ActiveEventDto(
    val code: String,
    val name: String,
    val type: String,
    val contentCode: String,
    val x: Int,
    val y: Int,
    val layer: String,
    val expiresAtMillis: Long? = null,
)

/**
 * GET /api/events/active — events the engine currently knows are live (WebSocket spawn/removed
 * plus the startup replay). Lets the UI queue one-off tasks against an event's tile.
 */
fun Route.eventRoutes(b: ApiBackend) {
    get("/events/active") {
        val e = b.engine ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ApiError("engine not running"))
        val events = runCatching { e.events.activeEvents.value.values }.getOrDefault(emptyList())
        call.respond(events.map { ev ->
            ActiveEventDto(
                code = ev.code, name = ev.name, type = ev.content.type, contentCode = ev.content.code,
                x = ev.map.x, y = ev.map.y, layer = ev.map.layer,
                expiresAtMillis = ev.expiresAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }.sortedBy { it.name })
    }
}
