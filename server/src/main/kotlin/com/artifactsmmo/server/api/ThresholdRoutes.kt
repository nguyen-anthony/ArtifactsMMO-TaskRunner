package com.artifactsmmo.server.api

import com.artifactsmmo.core.task.BankThreshold
import com.artifactsmmo.core.task.ThresholdSettings
import com.artifactsmmo.core.task.ThresholdStatus
import com.artifactsmmo.engine.config.TypedConfigs
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** Restock rules, global settings and the latest evaluation per rule (empty while the engine is down). */
@Serializable
data class ThresholdsDto(
    val settings: ThresholdSettings,
    val rules: List<BankThreshold>,
    val statuses: Map<String, ThresholdStatus>,
)

/** Bank thresholds. Every change re-evaluates the rules right away when the engine runs. */
fun Route.thresholdRoutes(b: ApiBackend) {
    val configs = TypedConfigs(b.configStore)
    suspend fun snapshot(evaluate: Boolean): ThresholdsDto {
        val producer = b.engine?.thresholds
        val statuses = when {
            producer == null -> emptyMap()
            evaluate -> producer.tick()
            else -> producer.statuses
        }
        return ThresholdsDto(configs.thresholdSettings(), configs.thresholds(), statuses)
    }
    route("/thresholds") {
        get { call.respond(snapshot(evaluate = false)) }
        post("/refresh") { call.respond(snapshot(evaluate = true)) }
        put("/settings") {
            configs.putThresholdSettings(call.receive<ThresholdSettings>())
            call.respond(snapshot(evaluate = true))
        }
        put("/{item}") {
            val rule = call.receive<BankThreshold>()
            val item = call.parameters["item"]!!
            if (rule.itemCode != item || rule.minQuantity < 0 || rule.batch <= 0) {
                return@put call.respond(HttpStatusCode.BadRequest, ApiError("itemCode must match the path; min >= 0, batch > 0"))
            }
            configs.putThreshold(rule)
            call.respond(snapshot(evaluate = true))
        }
        delete("/{item}") {
            configs.deleteThreshold(call.parameters["item"]!!)
            call.respond(snapshot(evaluate = true))
        }
    }
}
