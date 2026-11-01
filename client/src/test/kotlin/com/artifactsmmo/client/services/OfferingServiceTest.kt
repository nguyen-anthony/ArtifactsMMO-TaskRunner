package com.artifactsmmo.client.services

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.gateway.ErrorAction
import com.artifactsmmo.client.gateway.ErrorPolicy
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfferingServiceTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun `character offerings parse selections and completed categories`() = runTest {
        var path = ""
        val engine = MockEngine { req ->
            path = req.url.encodedPath
            respond(
                """{"data":{"date":"2026-10-25","expires_at":"2026-10-26T00:00:00Z","skills":["cooking","mining"],
                  "xp_reward_percent":25,
                  "offerings":[{"id":"20261025_combat_10_20","category":"combat","type":"monster","level_min":10,
                    "level_max":20,"requirement":{"code":"wolf_bone","quantity":5},
                    "favor":{"target_type":"monster","target_code":"wolf","xp_multiplier":1.5,"drop_multiplier":1.5}}],
                  "selected_offerings":[{"offering_id":"20261025_combat_10_20","category":"combat","status":"accepted",
                    "accepted_at":"2026-10-25T10:00:00Z","completed_at":null,"reward_xp":0,
                    "requirement":{"code":"wolf_bone","quantity":5},
                    "favor":{"target_type":"monster","target_code":"wolf"},"expires_at":"2026-10-26T00:00:00Z"}],
                  "completed_categories":["mining"],"active_favors":[]}}""",
                HttpStatusCode.OK, jsonHeaders,
            )
        }
        val result = ArtifactsMMOClient(token = "t", engine = engine).offerings.getForCharacter("bob")
        assertEquals("/my/bob/offerings", path)
        assertEquals(listOf("combat"), result.categories)
        assertEquals("wolf_bone", result.offerings.single().requirement.code)
        assertEquals(1.5, result.offerings.single().favor.dropMultiplier)
        assertEquals(false, result.selectedOfferings.single().isCompleted)
        assertEquals(listOf("mining"), result.completedCategories)
    }

    @Test
    fun `complete posts the offering id to the action endpoint`() = runTest {
        var path = ""
        var body = ""
        val engine = MockEngine { req ->
            path = req.url.encodedPath
            body = String(req.body.toByteArray())
            respond("""{"error":{"code":478,"message":"Missing required item(s)."}}""", HttpStatusCode.fromValue(478), jsonHeaders)
        }
        val client = ArtifactsMMOClient(token = "t", engine = engine)
        val error = runCatching { client.offerings.complete("bob", "20261025_combat_10_20") }.exceptionOrNull()
        assertEquals("/my/bob/action/offering", path)
        assertTrue(body.contains("\"offering_id\":\"20261025_combat_10_20\""), body)
        assertTrue(error is com.artifactsmmo.client.ArtifactsApiException && error.errorCode == 478)
    }

    @Test
    fun `offering error codes are classified`() {
        assertEquals(ErrorAction.Benign, ErrorPolicy.classify(574))
        assertEquals(ErrorAction.Benign, ErrorPolicy.classify(576))
        assertTrue(ErrorPolicy.classify(577) is ErrorAction.Replan)
        assertTrue(ErrorPolicy.classify(575) is ErrorAction.Replan)
    }
}
