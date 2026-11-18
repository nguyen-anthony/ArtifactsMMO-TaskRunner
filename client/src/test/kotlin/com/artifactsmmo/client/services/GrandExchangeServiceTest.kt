package com.artifactsmmo.client.services

import com.artifactsmmo.client.ArtifactsMMOClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GrandExchangeServiceTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val page = """"total":1,"page":1,"size":50,"pages":1"""

    @Test
    fun `orders and history parse the current API shapes`() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { req ->
            urls += req.url.encodedPathAndQuery
            val body = if (req.url.encodedPath.startsWith("/grandexchange/history")) {
                """{"data":[{"order_id":"o1","seller":"s","buyer":"b","code":"feather","quantity":1,"price":77,
                  "sold_at":"2026-11-08T06:18:50.709Z"}],$page}"""
            } else {
                """{"data":[{"id":"o2","type":"sell","account":"chneau","code":"feather","quantity":72,"price":2,
                  "created_at":"2026-11-14T16:55:29.443Z"}],$page}"""
            }
            respond(body, HttpStatusCode.OK, jsonHeaders)
        }
        val ge = ArtifactsMMOClient(token = "t", engine = engine).grandExchange
        assertEquals(77, ge.getHistory("feather").data.single().price)
        val order = ge.getOrders(itemCode = "feather", type = "sell").data.single()
        assertEquals("chneau", order.account)
        assertEquals(2, order.price)
        assertEquals("/grandexchange/orders?code=feather&type=sell&page=1&size=50", urls.last())
    }
}
