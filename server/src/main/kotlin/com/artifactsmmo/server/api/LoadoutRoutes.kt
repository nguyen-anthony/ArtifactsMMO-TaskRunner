package com.artifactsmmo.server.api

import com.artifactsmmo.core.task.KnownLoadout
import com.artifactsmmo.core.task.KnownLoadoutStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable data class LoadoutsRemovedDto(val removed: Int)

/**
 * Cached optimizer loadouts (known_loadouts.json). File-backed, so these work while the engine
 * is down; with an engine running, clearing a loadout also makes the character's running
 * fight task re-optimize before its next fight.
 */
fun Route.loadoutRoutes(b: ApiBackend, store: KnownLoadoutStore = KnownLoadoutStore()) {
    route("/loadouts") {
        get {
            call.respond(store.all().sortedWith(
                compareBy<KnownLoadout>({ it.characterName }, { it.monsterCode }, { -it.exactLevel })
            ))
        }
        // Edit gear/utilities of one entry (identified by character, level, monster, version).
        put {
            val edited = call.receive<KnownLoadout>()
            val updated = store.edit(edited)
                ?: return@put call.respond(HttpStatusCode.NotFound, ApiError("loadout not found"))
            b.engine?.forgetLoadout(updated.characterName)
            call.respond(updated)
        }
        // Filters: character, monster, level, version. At least one filter, or all=true.
        delete {
            val q = call.request.queryParameters
            val character = q["character"]
            val monster = q["monster"]
            val level = q["level"]?.toIntOrNull()
            val version = q["version"]
            if (character == null && monster == null && level == null && version == null && q["all"] != "true") {
                return@delete call.respond(HttpStatusCode.BadRequest, ApiError("pass a filter or all=true"))
            }
            val affected = mutableSetOf<String>()
            val removed = store.removeWhere {
                val match = (character == null || it.characterName == character) &&
                    (monster == null || it.monsterCode == monster) &&
                    (level == null || it.exactLevel == level) &&
                    (version == null || it.optimizerVersion == version)
                if (match) affected += it.characterName
                match
            }
            b.engine?.let { e -> affected.forEach(e::forgetLoadout) }
            call.respond(LoadoutsRemovedDto(removed))
        }
    }
}
