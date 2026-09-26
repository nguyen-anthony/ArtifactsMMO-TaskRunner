package com.artifactsmmo.server.api

import com.artifactsmmo.client.models.DataPage
import com.artifactsmmo.domain.task.EquipPlan
import com.artifactsmmo.domain.task.MemberPlan
import com.artifactsmmo.domain.task.UtilityPlan
import com.artifactsmmo.engine.realtime.LegacyRaidPlanner
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** Slim game-content entries for the web UI's pickers. */
@Serializable data class MonsterDto(val code: String, val name: String, val level: Int, val type: String, val reachable: Boolean = true)
@Serializable data class ResourceDto(val code: String, val name: String, val skill: String, val level: Int, val reachable: Boolean = true)
@Serializable data class ItemDto(val code: String, val name: String, val level: Int, val type: String, val craftSkill: String?)

@Serializable data class Catalog(val monsters: List<MonsterDto>, val resources: List<ResourceDto>, val items: List<ItemDto>)

@Serializable data class LoadoutRequest(val character: String, val monsterCode: String)
@Serializable
data class LoadoutResponse(
    val baselineWinRate: Double, val winRate: Double,
    val equip: List<EquipPlan>, val utilities: List<UtilityPlan>,
)

@Serializable data class CoopRequest(val members: List<String>, val monsterCode: String, val tankOverride: String? = null)

/**
 * Game content changes rarely, so the whole catalog is fetched once (a few dozen GETs from
 * the data bucket) and kept in memory until restart.
 */
private val catalogLock = Mutex()
@Volatile private var catalog: Catalog? = null

private suspend fun <T> allPages(fetch: suspend (Int) -> DataPage<T>): List<T> {
    val out = mutableListOf<T>()
    var page = 1
    while (true) {
        val p = fetch(page)
        out += p.data
        if (p.data.isEmpty() || page >= (p.pages ?: 1)) break
        page++
    }
    return out
}

fun Route.contentAndSimRoutes(b: ApiBackend) {
    get("/content") {
        val e = b.engine ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ApiError("engine not running"))
        val c = catalog ?: catalogLock.withLock {
            catalog ?: run {
                val content = e.client.content
                Catalog(
                    monsters = allPages { content.getMonsters(page = it, size = 100) }
                        .map { MonsterDto(it.code, it.name, it.level, it.type) }.sortedBy { it.level },
                    resources = allPages { content.getResources(page = it, size = 100) }
                        .map { ResourceDto(it.code, it.name, it.skill, it.level) }.sortedBy { it.level },
                    items = allPages { content.getItems(page = it, size = 100) }
                        .map { ItemDto(it.code, it.name, it.level, it.type, it.craft?.skill) }.sortedBy { it.level },
                ).also { catalog = it }
            }
        }
        // Reachability depends on the account's achievements, so it's computed per request
        // (cheap: in-memory route search) rather than cached with the content.
        val monsters = e.contentCache.reachableContentCodes("monster")
        val resources = e.contentCache.reachableContentCodes("resource")
        call.respond(c.copy(
            monsters = c.monsters.map { it.copy(reachable = it.code in monsters) },
            resources = c.resources.map { it.copy(reachable = it.code in resources) },
        ))
    }

    // Best gear + utilities for one character vs a monster (uses the fight simulator, ~seconds).
    post("/sim/loadout") {
        val e = b.engine ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiError("engine not running"))
        val req = call.receive<LoadoutRequest>()
        val char = e.helper.refreshCharacter(req.character)
        val r = e.gearOptimizer.optimizeWithCacheHint(char, req.monsterCode)
        call.respond(LoadoutResponse(
            baselineWinRate = r.baselineScore.winRate, winRate = r.optimizedScore.winRate,
            equip = r.equipActions.map { EquipPlan(it.slot, it.itemCode, it.source) },
            utilities = r.utilityActions.map { UtilityPlan(it.slot, it.itemCode, it.quantity, it.source) },
        ))
    }

    // Per-member provisioning plans for a boss/raid group (feed into BossFight.plans).
    post("/sim/coop") {
        val e = b.engine ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiError("engine not running"))
        val req = call.receive<CoopRequest>()
        val plans: Map<String, MemberPlan> = LegacyRaidPlanner(e.coop).plan(req.members, req.monsterCode, req.tankOverride)
        call.respond(plans)
    }
}
