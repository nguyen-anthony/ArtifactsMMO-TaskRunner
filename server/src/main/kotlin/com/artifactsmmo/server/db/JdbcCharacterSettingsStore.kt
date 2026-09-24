package com.artifactsmmo.server.db

import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.worker.CharacterSettings
import com.artifactsmmo.engine.worker.CharacterSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import javax.sql.DataSource

/**
 * `characters` table. A missing row means defaults (enabled, any type, no filler).
 * The filler is stored as its type + spec; everything else about the filler task is
 * decided by the worker (source FILLER, lowest priority, assigned to the character).
 */
class JdbcCharacterSettingsStore(private val dataSource: DataSource) : CharacterSettingsStore {

    override suspend fun get(name: String): CharacterSettings = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.prepareStatement("select enabled, allowed_types, filler_type, filler_spec from characters where name = ?").use { ps ->
                ps.setString(1, name)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return@withContext CharacterSettings(name)
                    val allowed = rs.getArray("allowed_types")?.let { (it.array as Array<*>).map { v -> v.toString() }.toSet() }
                    val fillerType = rs.getString("filler_type")
                    val fillerSpec = rs.getString("filler_spec")?.let { Json.parseToJsonElement(it) as JsonObject }
                    CharacterSettings(
                        name = name,
                        enabled = rs.getBoolean("enabled"),
                        allowedTypes = allowed,
                        filler = if (fillerType != null && fillerSpec != null) NewTask(type = fillerType, spec = fillerSpec) else null,
                    )
                }
            }
        }
    }

    override suspend fun put(settings: CharacterSettings) = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.prepareStatement(
                """
                insert into characters (name, enabled, allowed_types, filler_type, filler_spec, updated_at)
                values (?, ?, ?, ?, ?::jsonb, now())
                on conflict (name) do update set enabled = excluded.enabled, allowed_types = excluded.allowed_types,
                    filler_type = excluded.filler_type, filler_spec = excluded.filler_spec, updated_at = now()
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, settings.name)
                ps.setBoolean(2, settings.enabled)
                ps.setArray(3, settings.allowedTypes?.let { c.createArrayOf("text", it.toTypedArray()) })
                ps.setString(4, settings.filler?.type)
                ps.setString(5, settings.filler?.spec?.toString())
                ps.executeUpdate()
            }
        }
        Unit
    }

    /** Convenience for setting a filler from a typed spec. */
    suspend fun setFiller(name: String, spec: TaskSpec?) {
        val current = get(name)
        put(current.copy(filler = spec?.let { NewTask(type = it.typeName, spec = it.toJson()) }))
    }
}
