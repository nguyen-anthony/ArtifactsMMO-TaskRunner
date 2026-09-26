package com.artifactsmmo.server.db

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mu.KotlinLogging
import org.postgresql.PGConnection
import java.sql.Connection

private val log = KotlinLogging.logger {}

/**
 * Listens on the `task_changed` channel (fired by the `tasks_notify` trigger) and emits the
 * changed task id. Idle workers collect [changes] so new work wakes them immediately
 * instead of polling.
 *
 * Uses its own long-lived connection (not from the pool): LISTEN is per session, which is
 * also why Supabase must be reached via the session pooler / direct connection.
 */
class PgNotifier(private val connect: () -> Connection) {
    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<Long> = _changes

    private companion object {
        /** Worst-case wake-up latency for idle workers. One tiny query per second is negligible. */
        const val POLL_MS = 1_000L
    }

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        while (isActive) {
            try {
                connect().use { conn ->
                    conn.createStatement().use { it.execute("LISTEN task_changed") }
                    val pg = conn.unwrap(PGConnection::class.java)
                    log.info { "Listening for task_changed notifications" }
                    conn.prepareStatement("select 1").use { ping ->
                    while (isActive) {
                        // Don't use the blocking getNotifications(timeout): the Supabase pooler
                        // sends periodic ParameterStatus ('S') messages, which that code path
                        // rejects ("Unknown Response Type S") and the connection drops every ~60 s.
                        // A trivial query goes through the normal protocol handler (which accepts
                        // 'S'), pulls in any pending notifications, and proves the link is alive.
                        ping.executeQuery().close()
                        val notes = pg.notifications
                        if (notes.isNullOrEmpty()) { delay(POLL_MS); continue }
                        for (n in notes) {
                            val id = runCatching {
                                Json.parseToJsonElement(n.parameter).jsonObject["id"]?.jsonPrimitive?.longOrNull
                            }.getOrNull() ?: continue
                            _changes.tryEmit(id)
                        }
                    }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "LISTEN connection lost; reconnecting in 5s" }
                delay(5_000)
            }
        }
    }
}

/**
 * Session-level Postgres advisory lock guaranteeing a single active backend. Only the
 * holder runs workers, the scheduler and the realtime listener; this also makes
 * `recoverStale(0)` on boot safe. The lock lives as long as [connection] stays open.
 */
class InstanceLock private constructor(private val connection: Connection) : AutoCloseable {
    override fun close() = connection.close()

    companion object {
        /** Arbitrary constant identifying this application's lock. */
        const val KEY = 0x4152_5446L // "ARTF"

        /** Returns the lock, or null if another instance holds it. */
        fun tryAcquire(connect: () -> Connection): InstanceLock? {
            val conn = connect()
            val ok = conn.prepareStatement("select pg_try_advisory_lock(?)").use { ps ->
                ps.setLong(1, KEY)
                ps.executeQuery().use { it.next() && it.getBoolean(1) }
            }
            return if (ok) InstanceLock(conn) else { conn.close(); null }
        }
    }
}
