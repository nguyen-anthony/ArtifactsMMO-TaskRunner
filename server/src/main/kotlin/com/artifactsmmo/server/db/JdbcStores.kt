package com.artifactsmmo.server.db

import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.TaskRequirements
import com.artifactsmmo.engine.config.ConfigStore
import com.artifactsmmo.engine.scheduler.Schedule
import com.artifactsmmo.engine.scheduler.ScheduleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.sql.Timestamp
import javax.sql.DataSource

private val json = Json { ignoreUnknownKeys = true }

/** `config_entries` table. */
class JdbcConfigStore(private val ds: DataSource) : ConfigStore {
    override suspend fun list(kind: String): Map<String, JsonElement> = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.prepareStatement("select key, value from config_entries where kind = ? order by key").use { ps ->
                ps.setString(1, kind)
                ps.executeQuery().use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), json.parseToJsonElement(rs.getString(2))) }
                }
            }
        }
    }

    override suspend fun put(kind: String, key: String, value: JsonElement) = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.prepareStatement(
                """insert into config_entries (kind, key, value) values (?, ?, ?::jsonb)
                   on conflict (kind, key) do update set value = excluded.value, updated_at = now()"""
            ).use { ps -> ps.setString(1, kind); ps.setString(2, key); ps.setString(3, value.toString()); ps.executeUpdate() }
        }
        Unit
    }

    override suspend fun delete(kind: String, key: String) = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.prepareStatement("delete from config_entries where kind = ? and key = ?").use {
                it.setString(1, kind); it.setString(2, key); it.executeUpdate()
            }
        }
        Unit
    }
}

/** `schedules` table (interval schedules only; cron rows are skipped). */
class JdbcScheduleStore(private val ds: DataSource) : ScheduleStore {
    override suspend fun due(nowMillis: Long): List<Schedule> = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.prepareStatement(
                """select id, name, interval_seconds, task_type, task_spec, priority, assigned_character, requirements, next_run_at
                   from schedules where enabled and interval_seconds is not null
                     and (next_run_at is null or next_run_at <= ?) order by id"""
            ).use { ps ->
                ps.setTimestamp(1, Timestamp(nowMillis))
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(
                            Schedule(
                                id = rs.getLong("id"), name = rs.getString("name"),
                                intervalSeconds = rs.getInt("interval_seconds"),
                                task = NewTask(
                                    type = rs.getString("task_type"),
                                    spec = json.parseToJsonElement(rs.getString("task_spec")) as JsonObject,
                                    priority = rs.getInt("priority"),
                                    assignedCharacter = rs.getString("assigned_character"),
                                    requirements = json.decodeFromString(TaskRequirements.serializer(), rs.getString("requirements")),
                                ),
                                nextRunAtMillis = rs.getTimestamp("next_run_at")?.time,
                            )
                        )
                    }
                }
            }
        }
    }

    override suspend fun markRun(id: Long, ranAtMillis: Long, nextRunAtMillis: Long) = withContext(Dispatchers.IO) {
        ds.connection.use { c ->
            c.prepareStatement("update schedules set last_run_at = ?, next_run_at = ? where id = ?").use {
                it.setTimestamp(1, Timestamp(ranAtMillis)); it.setTimestamp(2, Timestamp(nextRunAtMillis)); it.setLong(3, id)
                it.executeUpdate()
            }
        }
        Unit
    }
}
