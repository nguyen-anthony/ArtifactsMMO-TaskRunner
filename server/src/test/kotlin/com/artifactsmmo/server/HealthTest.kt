package com.artifactsmmo.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthTest {
    @Test
    fun `health reports ok without a database`() = testApplication {
        application { module(dataSource = null) }
        val response = client.get("/api/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("not_configured"))
    }
}
