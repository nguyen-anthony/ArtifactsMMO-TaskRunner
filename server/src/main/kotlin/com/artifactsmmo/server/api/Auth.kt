package com.artifactsmmo.server.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.get
import io.ktor.server.sessions.sessions
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Single-user auth: the browser POSTs the static ADMIN_API_KEY once and gets an HttpOnly,
 * signed session cookie (a cookie rather than a header because the browser's EventSource
 * for SSE cannot send custom headers).
 */
@Serializable
data class UserSession(val issuedAtMillis: Long)

const val SESSION_COOKIE = "ammo_session"
const val SESSION_MAX_AGE_SECONDS = 30L * 24 * 3600

fun Application.installSessions(adminKey: String, secure: Boolean) {
    // Signing key derived from the admin key: rotating ADMIN_API_KEY logs everyone out.
    val signKey = MessageDigest.getInstance("SHA-256").digest("session:$adminKey".toByteArray())
    install(Sessions) {
        cookie<UserSession>(SESSION_COOKIE) {
            cookie.path = "/"
            cookie.httpOnly = true
            cookie.secure = secure
            cookie.maxAgeInSeconds = SESSION_MAX_AGE_SECONDS
            cookie.extensions["SameSite"] = "Strict"
            transform(SessionTransportTransformerMessageAuthentication(signKey))
        }
    }
}

fun ApplicationCall.isLoggedIn(nowMillis: Long = System.currentTimeMillis()): Boolean {
    val s = sessions.get<UserSession>() ?: return false
    return nowMillis - s.issuedAtMillis < SESSION_MAX_AGE_SECONDS * 1000
}

/** Paths reachable without a session. (Ktor merges `/api` route nodes, so exempt explicitly.) */
val PUBLIC_PATHS = setOf("/api/health", "/api/login", "/api/logout", "/api/me")

/** Rejects unauthenticated calls to every route it is installed on. */
val RequireSession = createRouteScopedPlugin("RequireSession") {
    onCall { call ->
        if (call.request.path() !in PUBLIC_PATHS && !call.isLoggedIn()) call.respond(HttpStatusCode.Unauthorized, ApiError("not logged in"))
    }
}

/** Constant-time key comparison (no early exit that leaks how many characters matched). */
fun keyMatches(given: String, expected: String): Boolean =
    MessageDigest.isEqual(sha256(given), sha256(expected))

private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())

/** Brute-force guard for /api/login: at most [max] failures per IP per [windowMillis]. */
class LoginThrottle(private val max: Int = 5, private val windowMillis: Long = 15 * 60_000) {
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()

    fun blocked(ip: String, now: Long = System.currentTimeMillis()): Boolean =
        recent(ip, now).size >= max

    fun fail(ip: String, now: Long = System.currentTimeMillis()) {
        failures.compute(ip) { _, l -> (l ?: mutableListOf()).apply { add(now) } }
    }

    fun reset(ip: String) { failures.remove(ip) }

    private fun recent(ip: String, now: Long): List<Long> =
        failures.computeIfPresent(ip) { _, l -> l.apply { removeAll { it <= now - windowMillis } } } ?: emptyList()
}

@Serializable
data class ApiError(val error: String)

/** Caddy puts the real client IP in X-Forwarded-For; fall back to the socket address. */
fun ApplicationCall.clientIp(): String =
    request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
        ?: request.local.remoteAddress
