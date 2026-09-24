package com.artifactsmmo.client.gateway

import com.artifactsmmo.client.ApiTransport
import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.RetryConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ApiTransportTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    /** Builds a transport on a MockEngine whose responses come from [handler] (called per request). */
    private fun TestScope.transport(
        limits: Map<RateCategory, List<RateWindow>> = DefaultRateLimits.all(),
        handler: (path: String, call: Int) -> Pair<HttpStatusCode, String>,
    ): Pair<ApiTransport, MutableList<String>> {
        val clock = Clock { testScheduler.currentTime }
        val seen = mutableListOf<String>()
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            seen += path
            val (status, body) = handler(path, seen.size)
            respond(body, status, jsonHeaders)
        }
        val http = HttpClient(engine) { defaultRequest { url("http://test") } }
        val t = ApiTransport(
            http,
            limiter = RateLimiter(limits, clock),
            cooldowns = CooldownTracker(clock),
            retry = RetryConfig(rateLimitBackoffMillis = 100, transientBaseBackoffMillis = 10),
            clock = clock,
        )
        return t to seen
    }

    private fun err(code: Int, msg: String = "boom", data: String? = null) =
        """{"error":{"code":$code,"message":"$msg"${data?.let { ",\"data\":$it" } ?: ""}}}"""

    @Test
    fun `parses JSON error with nested data`() = runTest {
        val (t, _) = transport { _, _ ->
            HttpStatusCode.UnprocessableEntity to err(422, "invalid", """{"body":{"code":["required"]}}""")
        }
        val e = assertFailsWith<ArtifactsApiException> { t.execute(HttpMethod.Post, "/my/a/action/crafting") {} }
        assertEquals(422, e.errorCode)
        assertTrue(e.message.contains("invalid"))
        assertTrue(e.data!!.containsKey("body"))
        assertIs<ErrorAction.FailTask>(e.action)
    }

    @Test
    fun `retries 429 then succeeds`() = runTest {
        val (t, seen) = transport { _, n ->
            if (n < 3) HttpStatusCode.TooManyRequests to err(429) else HttpStatusCode.OK to """{"data":{}}"""
        }
        t.execute(HttpMethod.Post, "/my/a/action/rest") {}
        assertEquals(3, seen.size)
    }

    @Test
    fun `does not retry 5xx for actions but does for GET`() = runTest {
        var itemCalls = 0
        val (t, seen) = transport { path, _ ->
            if (path.startsWith("/my/")) HttpStatusCode.InternalServerError to err(500)
            else if (++itemCalls < 2) HttpStatusCode.BadGateway to "oops"
            else HttpStatusCode.OK to """{"data":[]}"""
        }
        assertFailsWith<ArtifactsApiException> { t.execute(HttpMethod.Post, "/my/a/action/fight") {} }
        assertEquals(1, seen.count { it.startsWith("/my/") })
        t.execute(HttpMethod.Get, "/items") {}
        assertEquals(2, seen.count { it == "/items" })
    }

    @Test
    fun `learns cooldown from action response and waits before next action`() = runTest {
        val expiry = Instant.ofEpochMilli(testScheduler.currentTime + 30_000).toString()
        val (t, _) = transport { _, _ ->
            HttpStatusCode.OK to """{"data":{"cooldown":{},"character":{"name":"a","cooldown_expiration":"$expiry"}}}"""
        }
        t.execute(HttpMethod.Post, "/my/a/action/gathering") {}
        val before = testScheduler.currentTime
        t.execute(HttpMethod.Post, "/my/a/action/gathering") {}
        assertTrue(testScheduler.currentTime - before >= 29_000, "should wait out the 30s cooldown")
        // Other characters are not blocked.
        val b0 = testScheduler.currentTime
        t.execute(HttpMethod.Post, "/my/b/action/gathering") {}
        assertTrue(testScheduler.currentTime - b0 < 1_000)
    }

    @Test
    fun `group fight records cooldown for every participant`() = runTest {
        val expiry = Instant.ofEpochMilli(testScheduler.currentTime + 10_000).toString()
        val (t, _) = transport { _, _ ->
            HttpStatusCode.OK to """{"data":{"characters":[
                {"name":"a","cooldown_expiration":"$expiry"},
                {"name":"b","cooldown_expiration":"$expiry"}]}}"""
        }
        t.execute(HttpMethod.Post, "/my/a/action/fight") {}
        assertTrue(t.cooldowns.remainingMillis("b") > 9_000)
    }

    @Test
    fun `499 waits the reported seconds and resends`() = runTest {
        val (t, seen) = transport { _, n ->
            if (n == 1) HttpStatusCode(499, "cooldown") to err(499, "Character in cooldown: 5.5 seconds remaining.")
            else HttpStatusCode.OK to """{"data":{}}"""
        }
        val start = testScheduler.currentTime
        t.execute(HttpMethod.Post, "/my/a/action/move") {}
        assertEquals(2, seen.size)
        assertTrue(testScheduler.currentTime - start >= 5_500)
    }

    @Test
    fun `rate bucket spaces bursts across the window`() = runTest {
        val (t, seen) = transport(limits = mapOf(
            RateCategory.ACTION to DefaultRateLimits.ACTION,
            RateCategory.DATA to listOf(RateWindow(2, 1_000)),
            RateCategory.SIMULATION to DefaultRateLimits.SIMULATION,
            RateCategory.ACCOUNT to DefaultRateLimits.ACCOUNT,
        )) { _, _ -> HttpStatusCode.OK to """{"data":[]}""" }
        val start = testScheduler.currentTime
        (1..5).map { async { t.execute(HttpMethod.Get, "/items") {} } }.awaitAll()
        assertEquals(5, seen.size)
        // 2 per second → requests 3-4 in the 2nd second, 5 in the 3rd.
        assertTrue(testScheduler.currentTime - start >= 2_000)
    }

    @Test
    fun `categorizes paths`() {
        assertEquals(RateCategory.ACTION, RateCategory.of("POST", "/my/bob/action/move"))
        assertEquals(RateCategory.DATA, RateCategory.of("GET", "/my/bank/items?page=1"))
        assertEquals(RateCategory.SIMULATION, RateCategory.of("POST", "/simulation/fight"))
        assertEquals(RateCategory.ACCOUNT, RateCategory.of("GET", "/my/rates"))
    }
}
