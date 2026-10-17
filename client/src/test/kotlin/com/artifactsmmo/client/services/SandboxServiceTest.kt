package com.artifactsmmo.client.services

import com.artifactsmmo.client.ArtifactsMMOClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SandboxServiceTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `sandbox url detection`() {
        assertTrue(ArtifactsMMOClient.isSandboxUrl(ArtifactsMMOClient.SANDBOX_URL))
        assertFalse(ArtifactsMMOClient.isSandboxUrl(ArtifactsMMOClient.PRODUCTION_URL))
        assertFalse(ArtifactsMMOClient.isSandboxUrl("https://api.artifactsmmo.com/sandbox"))
        assertFalse(ArtifactsMMOClient.isSandboxUrl("https://evil.com/?api.sandbox.artifactsmmo.com"))
    }

    @Test
    fun `sandbox calls never leave the process against production`() = runTest {
        var requests = 0
        val engine = MockEngine { requests++; respond("{}", HttpStatusCode.OK, jsonHeaders) }
        val client = ArtifactsMMOClient(token = "t", engine = engine)
        assertFailsWith<IllegalStateException> { client.sandbox.giveItem("a", "copper_ore", 1) }
        assertFailsWith<IllegalStateException> { client.sandbox.clearCooldown("a") }
        assertFailsWith<IllegalStateException> { client.sandbox.spawnEvent("x") }
        assertEquals(0, requests)
    }

    @Test
    fun `give item posts body and records cooldown for the body character`() = runTest {
        var body = ""
        val engine = MockEngine { req ->
            body = String(req.body.toByteArray())
            respond(
                """{"data":{"cooldown":{},"item":{"code":"copper_ore","quantity":2},
                   "character":{"name":"bob","cooldown_expiration":"2999-01-01T00:00:00Z"}}}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val client = ArtifactsMMOClient(token = "t", baseUrl = ArtifactsMMOClient.SANDBOX_URL, engine = engine)
        // The partial character payload fails to decode; only the request and cooldown matter here.
        runCatching { client.sandbox.giveItem("bob", "copper_ore", 2) }
        assertTrue(body.contains("\"code\":\"copper_ore\"") && body.contains("\"quantity\":2"), body)
        assertTrue(client.cooldowns.remainingMillis("bob") > 0)
    }
}
