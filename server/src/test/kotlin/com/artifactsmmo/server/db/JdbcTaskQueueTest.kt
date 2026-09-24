package com.artifactsmmo.server.db

import com.artifactsmmo.domain.queue.GroupSlot
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.queue.TaskStatus
import com.artifactsmmo.server.Database
import com.artifactsmmo.server.ServerConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests against a real Postgres. Uses TEST_DATABASE_URL/_USER/_PASSWORD if set
 * (e.g. a Supabase dev project), otherwise a Testcontainers Postgres; skipped when neither
 * is available. Every test gets its own fresh schema.
 */
class JdbcTaskQueueTest {

    private lateinit var config: ServerConfig
    private lateinit var ds: HikariDataSource
    private lateinit var queue: JdbcTaskQueue

    private fun task(type: String = "gather", priority: Int? = null, assigned: String? = null, dedupe: String? = null,
                     notBefore: Long? = null, expires: Long? = null) = NewTask(
        type = type,
        spec = buildJsonObject { put("resource", "copper_rocks") },
        source = TaskSource.MANUAL,
        priority = priority ?: TaskSource.MANUAL.defaultPriority,
        assignedCharacter = assigned,
        dedupeKey = dedupe,
        notBeforeMillis = notBefore,
        expiresAtMillis = expires,
    )

    @BeforeTest
    fun setUp() {
        val base = baseConfig()
        assumeTrue(base != null, "No TEST_DATABASE_URL and Docker unavailable; skipping DB tests")
        config = base!!.copy(databaseSchema = "t_" + System.nanoTime())
        ds = Database.connect(config)
        Database.migrate(ds, config.databaseSchema)
        queue = JdbcTaskQueue(ds)
    }

    @AfterTest
    fun tearDown() {
        if (!::ds.isInitialized) return
        ds.connection.use { it.createStatement().execute("drop schema ${config.databaseSchema} cascade") }
        ds.close()
    }

    @Test
    fun `enqueue dedupes only while the task is live`() = runBlocking {
        val a = assertNotNull(queue.enqueue(task(dedupe = "raid:x:1")))
        assertNull(queue.enqueue(task(dedupe = "raid:x:1")))
        queue.cancel(a.id)
        assertNotNull(queue.enqueue(task(dedupe = "raid:x:1")))
        Unit
    }

    @Test
    fun `candidates ordered by priority and respect assignment and not_before`() = runBlocking {
        val low = queue.enqueue(task(priority = 10))!!
        val high = queue.enqueue(task(priority = 90))!!
        queue.enqueue(task(assigned = "bob"))
        queue.enqueue(task(notBefore = System.currentTimeMillis() + 60_000))
        val ids = queue.candidates("alice").map { it.id }
        assertEquals(listOf(high.id, low.id), ids)
        assertEquals(3, queue.candidates("bob").size)
    }

    @Test
    fun `exactly one of many racing workers wins a claim`() = runBlocking {
        val t = queue.enqueue(task())!!
        val winners = (1..8).map { i -> async(Dispatchers.IO) { queue.claim(t.id, "c$i") } }.awaitAll().filterNotNull()
        assertEquals(1, winners.size)
        assertEquals(TaskStatus.CLAIMED, queue.get(t.id)!!.status)
    }

    @Test
    fun `a character holds at most one task`() = runBlocking {
        val a = queue.enqueue(task())!!
        val b = queue.enqueue(task())!!
        assertNotNull(queue.claim(a.id, "alice"))
        assertNull(queue.claim(b.id, "alice"))
        assertTrue(queue.candidates("alice").isEmpty())
        queue.complete(a.id)
        assertNotNull(queue.claim(b.id, "alice"))
        Unit
    }

    @Test
    fun `suspend keeps checkpoint and makes the task claimable again`() = runBlocking {
        val t = queue.enqueue(task())!!
        queue.claim(t.id, "alice"); queue.markRunning(t.id, "alice")
        queue.suspend(t.id, buildJsonObject { put("gathered", 12) }, "preempted by event")
        val s = queue.get(t.id)!!
        assertEquals(TaskStatus.SUSPENDED, s.status)
        assertNull(s.claimedBy)
        assertEquals(JsonPrimitive(12), s.checkpoint!!["gathered"])
        assertNotNull(queue.claim(t.id, "bob"))
        assertEquals(listOf("created", "claimed", "started", "suspended", "claimed"), queue.events(t.id).map { it.kind })
    }

    @Test
    fun `fail with retry returns to pending and counts attempts`() = runBlocking {
        val t = queue.enqueue(task())!!
        queue.claim(t.id, "alice")
        queue.fail(t.id, "boom", retry = true)
        queue.get(t.id)!!.let { assertEquals(TaskStatus.PENDING, it.status); assertEquals(1, it.attempts) }
        queue.claim(t.id, "alice")
        queue.fail(t.id, "boom again")
        assertEquals(TaskStatus.FAILED, queue.get(t.id)!!.status)
    }

    @Test
    fun `group forms when every slot is claimed and names participants`() = runBlocking {
        val g = queue.enqueueGroup(
            task(type = "boss_fight"),
            listOf(GroupSlot("alice"), GroupSlot(), GroupSlot()),
        )!!
        assertEquals(3, g.slots.size)
        // The parent row is never claimable.
        assertTrue(queue.candidates("zed").none { it.id == g.parent.id })

        // alice is assigned the initiator slot, so she must not take an open slot.
        val aliceCandidates = queue.candidates("alice").map { it.id }
        assertEquals(listOf(g.initiator!!.id), aliceCandidates)

        queue.claim(g.initiator!!.id, "alice")
        queue.claim(g.participants[0].id, "bob")
        assertTrue(!queue.group(g.parent.id)!!.isFormed)
        queue.claim(g.participants[1].id, "carol")
        val formed = queue.group(g.parent.id)!!
        assertTrue(formed.isFormed)
        assertEquals(listOf("bob", "carol"), formed.participantNames)

        queue.releaseGroup(g.parent.id, "timeout")
        assertTrue(queue.group(g.parent.id)!!.slots.all { it.status == TaskStatus.PENDING && it.claimedBy == null })

        queue.cancel(g.parent.id)
        assertTrue(queue.group(g.parent.id)!!.slots.all { it.status == TaskStatus.CANCELLED })
    }

    @Test
    fun `recoverStale and cancelExpired`() = runBlocking {
        val t = queue.enqueue(task())!!
        queue.claim(t.id, "alice")
        assertEquals(0, queue.recoverStale(60_000))
        assertEquals(1, queue.recoverStale(0))
        assertEquals(TaskStatus.SUSPENDED, queue.get(t.id)!!.status)

        val e = queue.enqueue(task(expires = System.currentTimeMillis() + 500))!!
        delay(1_000)
        assertEquals(1, queue.cancelExpired())
        assertEquals(TaskStatus.CANCELLED, queue.get(e.id)!!.status)
    }

    @Test
    fun `notifier emits on insert and instance lock is exclusive`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val notifier = PgNotifier { Database.sessionConnection(config) }
            notifier.start(scope)
            delay(1_000) // let LISTEN register
            val t = queue.enqueue(task())!!
            val id = withTimeout(10_000) { notifier.changes.first { it == t.id } }
            assertEquals(t.id, id)

            val lock = assertNotNull(InstanceLock.tryAcquire { Database.sessionConnection(config) })
            assertNull(InstanceLock.tryAcquire { Database.sessionConnection(config) })
            lock.close()
            InstanceLock.tryAcquire { Database.sessionConnection(config) }!!.close()
        } finally {
            scope.cancel()
        }
    }

    companion object {
        private val container: PostgreSQLContainer<*>? by lazy {
            if (!runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)) null
            else PostgreSQLContainer("postgres:16-alpine").also { it.start() }
        }

        fun baseConfig(): ServerConfig? {
            val env = System.getenv()
            env["TEST_DATABASE_URL"]?.takeIf { it.isNotBlank() }?.let { url ->
                return ServerConfig.fromEnv(emptyMap()).copy(
                    databaseUrl = url, databaseUser = env["TEST_DATABASE_USER"], databasePassword = env["TEST_DATABASE_PASSWORD"],
                )
            }
            val c = container ?: return null
            return ServerConfig.fromEnv(emptyMap()).copy(
                databaseUrl = c.jdbcUrl, databaseUser = c.username, databasePassword = c.password,
            )
        }
    }
}
