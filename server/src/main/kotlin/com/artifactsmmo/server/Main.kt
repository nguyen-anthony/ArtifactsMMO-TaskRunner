package com.artifactsmmo.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import javax.sql.DataSource

fun main() {
    val config = ServerConfig.fromEnv()
    val dataSource = config.databaseUrl?.let {
        Database.connect(config).also { ds -> Database.migrate(ds, config.databaseSchema) }
    }
    embeddedServer(Netty, port = config.port) { module(dataSource) }.start(wait = true)
}

@Serializable
data class HealthResponse(val status: String, val database: String)

fun Application.module(dataSource: DataSource?) {
    install(ContentNegotiation) { json() }
    install(CallLogging)

    routing {
        // Unauthenticated liveness probe used by Docker/Caddy.
        get("/api/health") {
            val db = when {
                dataSource == null -> "not_configured"
                runCatching { dataSource.connection.use { it.isValid(2) } }.getOrDefault(false) -> "ok"
                else -> "down"
            }
            val code = if (db == "down") HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
            call.respond(code, HealthResponse(status = "ok", database = db))
        }
    }
}
