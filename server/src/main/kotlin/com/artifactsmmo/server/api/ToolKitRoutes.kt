package com.artifactsmmo.server.api

import com.artifactsmmo.core.task.CleanupConfig
import com.artifactsmmo.core.task.CleanupReport
import com.artifactsmmo.core.task.FoodConfig
import com.artifactsmmo.core.task.GearFocusConfig
import com.artifactsmmo.core.task.ToolKitConfig
import com.artifactsmmo.core.task.ToolKitPlan
import com.artifactsmmo.engine.config.TypedConfigs
import io.ktor.http.HttpStatusCode
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

@Serializable data class CleanupDto(val config: CleanupConfig, val report: CleanupReport?)

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
    /** Gathering gear focus: items worth prospecting for (see StatGearOptimizer.resolveFocus). */
    route("/gear-focus") {
        get { call.respond(configs.gearFocus()) }
        put {
            val c = call.receive<GearFocusConfig>()
            configs.putGearFocus(c.copy(prospectFor = c.prospectFor.map(String::trim).filter(String::isNotEmpty).distinct()))
            b.engine?.reloadGearFocus()
            call.respond(configs.gearFocus())
        }
    }
    /** Fight food sizing / heal timing (see FoodPlanner). */
    route("/food-config") {
        get { call.respond(configs.food()) }
        put {
            configs.putFood(call.receive<FoodConfig>())
            b.engine?.reloadFood()
            call.respond(configs.food())
        }
    }
    /** Bank cleanup: obsolete gear report (see GearCleanup). Report is null while the engine is down. */
    route("/cleanup") {
        get {
            val producer = b.engine?.cleanup
            val report = producer?.let { it.last ?: it.tick() }
            call.respond(CleanupDto(configs.cleanup(), report))
        }
        put {
            configs.putCleanup(call.receive<CleanupConfig>())
            call.respond(CleanupDto(configs.cleanup(), b.engine?.cleanup?.tick()))
        }
        post("/refresh") {
            call.respond(CleanupDto(configs.cleanup(), b.engine?.cleanup?.tick()))
        }
        // On-demand Grand Exchange prices for one item (3 GETs; not part of the report to save API calls).
        get("/ge/{code}") {
            val e = b.engine ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ApiError("engine not running"))
            val code = call.parameters["code"]!!
            val ge = e.client.grandExchange
            val result = runCatching {
                val sales = ge.getHistory(code, size = 20).data
                val sells = ge.getOrders(itemCode = code, type = "sell", size = 100).data
                val buys = ge.getOrders(itemCode = code, type = "buy", size = 100).data
                GePriceDto(
                    code = code,
                    recentSales = sales.map { GeSaleDto(it.price, it.quantity, it.soldAt) },
                    lowestSell = sells.minOfOrNull { it.price },
                    sellQuantity = sells.sumOf { it.quantity },
                    highestBuy = buys.maxOfOrNull { it.price },
                    buyQuantity = buys.sumOf { it.quantity },
                )
            }.getOrElse { return@get call.respond(HttpStatusCode.BadGateway, ApiError("GE lookup failed: ${it.message}")) }
            call.respond(result)
        }
    }
}

@Serializable data class GeSaleDto(val price: Int, val quantity: Int, val soldAt: String)
@Serializable data class GePriceDto(
    val code: String,
    val recentSales: List<GeSaleDto>,
    val lowestSell: Int?,
    val sellQuantity: Int,
    val highestBuy: Int?,
    val buyQuantity: Int,
)
