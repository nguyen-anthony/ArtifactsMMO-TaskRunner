package com.artifactsmmo.server.api

import com.artifactsmmo.engine.Engine
import com.artifactsmmo.engine.config.ConfigStore
import com.artifactsmmo.engine.queue.TaskQueue
import com.artifactsmmo.engine.worker.CharacterSettingsStore
import com.artifactsmmo.server.Database
import com.artifactsmmo.server.ServerConfig
import com.artifactsmmo.server.db.JdbcCharacterSettingsStore
import com.artifactsmmo.server.db.JdbcConfigStore
import com.artifactsmmo.server.db.JdbcTaskQueue
import com.artifactsmmo.server.db.JdbcTaskQueueTest
import com.artifactsmmo.server.module
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {
    private val cfg = ServerConfig.fromEnv(emptyMap()).copy(adminApiKey = "s3cret", cookieSecure = false)

    /** Backend whose services blow up if touched: proves auth rejects before any work. */
    private val untouchable = object : ApiBackend {
        override val queue: TaskQueue = stub()
        override val settings: CharacterSettingsStore = stub()
        override val configStore: ConfigStore = stub()
        override val engine: Engine? = null
    }

    private inline fun <reified T> stub(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
        error("${m.name} must not be called")
    } as T

    private fun ApplicationTestBuilder.cookieClient() = createClient { install(HttpCookies) }

    private suspend fun io.ktor.client.HttpClient.login(key: String) = post("/api/login") {
        contentType(ContentType.Application.Json); setBody("""{"key":"$key"}""")
    }

    @Test
    fun `protected routes need a session, public ones do not`() = testApplication {
        application { module(dataSource = null, backend = untouchable, config = cfg) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/tasks").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/stream").status)
        assertEquals(HttpStatusCode.OK, client.get("/api/health").status)
        assertTrue(client.get("/api/me").bodyAsText().contains("\"loggedIn\":false"))
    }

    @Test
    fun `login sets an HttpOnly cookie and wrong keys are throttled`() = testApplication {
        application { module(dataSource = null, backend = untouchable, config = cfg) }
        val c = cookieClient()
        val ok = c.login("s3cret")
        assertEquals(HttpStatusCode.OK, ok.status)
        val setCookie = ok.headers["Set-Cookie"]!!
        assertTrue("HttpOnly" in setCookie && "SameSite=Strict" in setCookie)
        assertTrue(c.get("/api/me").bodyAsText().contains("\"loggedIn\":true"))

        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, client.login("nope").status) }
        assertEquals(HttpStatusCode.TooManyRequests, client.login("s3cret").status)
    }

    @Test
    fun `tampered cookie is rejected`() = testApplication {
        application { module(dataSource = null, backend = untouchable, config = cfg) }
        val r = client.get("/api/tasks") { header("Cookie", "ammo_session=issuedAtMillis%3D%23l9999999999999/deadbeef") }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `create, list and cancel a task over HTTP`() {
        val base = JdbcTaskQueueTest.baseConfig()
        assumeTrue(base != null, "no database available")
        val dbCfg = base!!.copy(databaseSchema = "api_" + System.nanoTime())
        val ds = Database.connect(dbCfg).also { Database.migrate(it, dbCfg.databaseSchema) }
        val backend = object : ApiBackend {
            override val queue = JdbcTaskQueue(ds)
            override val settings = JdbcCharacterSettingsStore(ds)
            override val configStore = JdbcConfigStore(ds)
            override val engine: Engine? = null
        }
        try {
            testApplication {
                application { module(ds, backend, cfg) }
                val c = cookieClient()
                c.login("s3cret")
                val created = c.post("/api/tasks") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"spec":{"kind":"gather","skill":"mining","resourceCode":"copper_rocks"},
                               "assignedCharacter":"alice","stopCondition":{"kind":"count","target":10}}""")
                }
                assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
                val id = Regex("\"id\":(\\d+)").find(created.bodyAsText())!!.groupValues[1]

                val bad = c.post("/api/tasks") { contentType(ContentType.Application.Json); setBody("""{"spec":{"kind":"nope"}}""") }
                assertEquals(HttpStatusCode.BadRequest, bad.status)

                assertTrue(c.get("/api/tasks?status=pending").bodyAsText().contains("\"id\":$id"))
                assertEquals(HttpStatusCode.NoContent, c.post("/api/tasks/$id/cancel").status)
                assertTrue(c.get("/api/tasks/$id").bodyAsText().contains("\"status\":\"cancelled\""))

                val group = c.post("/api/tasks/group") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"spec":{"kind":"boss_fight","monsterCode":"lich"},
                               "slots":[{"assignedCharacter":"alice"},{},{}]}""")
                }
                assertEquals(HttpStatusCode.Created, group.status, group.bodyAsText())
            }
        } finally {
            ds.connection.use { it.createStatement().execute("drop schema ${dbCfg.databaseSchema} cascade") }
            ds.close()
        }
    }
}
