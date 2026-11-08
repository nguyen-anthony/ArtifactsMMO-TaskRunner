package com.artifactsmmo.server.api

import com.artifactsmmo.core.task.ToolKitConfig
import com.artifactsmmo.core.task.ToolKitPlan
import com.artifactsmmo.engine.config.TypedConfigs
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

/** Tool kit config plus the allocation in force (null plan = disabled, engine down, or not computed yet). */
@Serializable data class ToolKitDto(val config: ToolKitConfig, val plan: ToolKitPlan?)

/** Tool kit settings and allocation. Saving recomputes the plan right away when the engine runs. */
fun Route.toolKitRoutes(b: ApiBackend) {
    val configs = TypedConfigs(b.configStore)
    route("/toolkit") {
        get { call.respond(ToolKitDto(configs.toolKit(), b.engine?.toolKitPlan)) }
        put {
            configs.putToolKit(call.receive<ToolKitConfig>())
            val plan = b.engine?.toolKit?.tick()
            call.respond(ToolKitDto(configs.toolKit(), plan))
        }
        post("/refresh") {
            val plan = b.engine?.toolKit?.tick()
            call.respond(ToolKitDto(configs.toolKit(), plan))
        }
    }
}
