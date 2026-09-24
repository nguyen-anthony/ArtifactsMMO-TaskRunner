package com.artifactsmmo.server

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.engine.Engine
import com.artifactsmmo.server.db.InstanceLock
import com.artifactsmmo.server.db.JdbcCharacterSettingsStore
import com.artifactsmmo.server.db.JdbcConfigStore
import com.artifactsmmo.server.db.JdbcScheduleStore
import com.artifactsmmo.server.db.JdbcTaskQueue
import com.artifactsmmo.server.db.PgNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.io.File
import javax.sql.DataSource

private val log = KotlinLogging.logger {}

/**
 * Starts the game engine once this process holds the Postgres instance lock. If another
 * instance holds it (e.g. during a rolling redeploy) we keep retrying, so the HTTP API stays
 * up and takes over as soon as the old process exits.
 */
class Backend(private val config: ServerConfig, private val dataSource: DataSource) : AutoCloseable, com.artifactsmmo.server.api.ApiBackend {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notifier = PgNotifier { Database.sessionConnection(config) }
    override val queue = JdbcTaskQueue(dataSource, notifier.changes)
    override val settings = JdbcCharacterSettingsStore(dataSource)
    override val configStore = JdbcConfigStore(dataSource)
    @Volatile override var engine: Engine? = null
        private set
    private var lock: InstanceLock? = null

    fun start() {
        notifier.start(scope)
        val token = config.artifactsToken ?: run { log.warn { "ARTIFACTS_TOKEN not set; engine disabled" }; return }
        scope.launch {
            LegacyConfigImporter.import(configStore, File(".")) { log.info { it } }
            while (lock == null) {
                lock = runCatching { InstanceLock.tryAcquire { Database.sessionConnection(config) } }.getOrNull()
                if (lock == null) { log.info { "Another instance holds the engine lock; retrying in 15s" }; delay(15_000) }
            }
            val e = Engine(ArtifactsMMOClient(token = token), token, queue, settings, configStore,
                JdbcScheduleStore(dataSource), scope)
            e.start()
            engine = e
            log.info { "Engine started for ${e.characters.joinToString()}" }
        }
    }

    override fun close() {
        engine?.stop()
        scope.cancel()
        lock?.close()
    }
}
