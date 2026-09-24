package com.artifactsmmo.server

import com.artifactsmmo.server.api.ApiBackend
import com.artifactsmmo.server.api.ApiError
import com.artifactsmmo.server.api.ApiJson
import com.artifactsmmo.server.api.BadRequest
import com.artifactsmmo.server.api.LoginThrottle
import com.artifactsmmo.server.api.RequireSession
import com.artifactsmmo.server.api.apiRoutes
import com.artifactsmmo.server.api.authRoutes
import com.artifactsmmo.server.api.installSessions
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import mu.KotlinLogging
import javax.sql.DataSource

private val log = KotlinLogging.logger {}

fun main() {
    val config = ServerConfig.fromEnv()
    val dataSource = config.databaseUrl?.let {
        Database.connect(config).also { ds -> Database.migrate(ds, config.databaseSchema) }
    }
    val backend = dataSource?.let { Backend(config, it).also(Backend::start) }
    Runtime.getRuntime().addShutdownHook(Thread { backend?.close() })
    if (config.adminApiKey == null) log.warn { "ADMIN_API_KEY not set: login is disabled" }
    embeddedServer(Netty, port = config.port) { module(dataSource, backend, config) }.start(wait = true)
}

@Serializable
data class HealthResponse(val status: String, val database: String, val engine: Boolean = false)

fun Application.module(
    dataSource: DataSource?,
    backend: ApiBackend? = null,
    config: ServerConfig = ServerConfig.fromEnv(emptyMap()),
) {
    install(ContentNegotiation) { json(ApiJson) }
    install(CallLogging) { filter { !it.request.path().startsWith("/api/stream") } }
    install(SSE)
    install(StatusPages) {
        exception<BadRequest> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "bad request")) }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError(e.cause?.message ?: e.message ?: "bad request"))
        }
        exception<SerializationException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "invalid JSON")) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "bad request")) }
        exception<Throwable> { call, e ->
            log.error(e) { "Unhandled error on ${call.request.path()}" }
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal error: ${e.message}"))
        }
    }
    // Sessions need a signing key; without ADMIN_API_KEY nobody can log in anyway.
    val adminKey = config.adminApiKey
    installSessions(adminKey ?: "disabled-${System.nanoTime()}", config.cookieSecure)

    routing {
        // Unauthenticated liveness probe used by Docker/Caddy.
        get("/api/health") {
            val db = when {
                dataSource == null -> "not_configured"
                runCatching { dataSource.connection.use { it.isValid(2) } }.getOrDefault(false) -> "ok"
                else -> "down"
            }
            val code = if (db == "down") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
            call.respond(code, HealthResponse(status = "ok", database = db, engine = backend?.engine != null))
        }
        authRoutes(adminKey, LoginThrottle(), backend)
        if (backend != null) {
            route("/api") {
                install(RequireSession)
                apiRoutes(backend)
            }
        }
    }
}
