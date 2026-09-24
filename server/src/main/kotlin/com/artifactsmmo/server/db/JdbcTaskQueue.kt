package com.artifactsmmo.server.db

import com.artifactsmmo.domain.queue.GroupRole
import com.artifactsmmo.domain.queue.GroupSlot
import com.artifactsmmo.domain.queue.GroupState
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.QueuedTask
import com.artifactsmmo.domain.queue.StopCondition
import com.artifactsmmo.domain.queue.TaskEvent
import com.artifactsmmo.domain.queue.TaskFilter
import com.artifactsmmo.domain.queue.TaskRequirements
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.queue.TaskStatus
import com.artifactsmmo.engine.queue.TaskQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import javax.sql.DataSource

/**
 * Postgres implementation of [TaskQueue] using plain JDBC.
 *
 * Every public method runs in its own transaction on Dispatchers.IO. The schema is chosen by
 * the Hikari pool (`schema=`), so SQL uses unqualified table names.
 *
 * Concurrency invariants enforced in SQL (not just in Kotlin):
 *  - a row is claimed by at most one character (conditional UPDATE on status),
 *  - a character holds at most one claimed/running task at a time,
 *  - a character never takes an open group slot while it is assigned to a different
 *    slot of the same group (which would deadlock the group).
 */
class JdbcTaskQueue(
    private val dataSource: DataSource,
    override val changes: Flow<Long> = emptyFlow(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) : TaskQueue {

    // ── Producing ───────────────────────────────────────────────────────────

    override suspend fun enqueue(task: NewTask): QueuedTask? = tx { c ->
        insertTask(c, task, GroupRole.SOLO, groupId = null, assigned = task.assignedCharacter,
            requirements = task.requirements, dedupeKey = task.dedupeKey)
            ?.also { event(c, it.id, null, "created", "source=${it.source.dbValue}") }
    }

    override suspend fun enqueueGroup(task: NewTask, slots: List<GroupSlot>): GroupState? = tx { c ->
        require(slots.isNotEmpty()) { "a group needs at least one slot" }
        val parent = insertTask(c, task, GroupRole.GROUP, groupId = null, assigned = null,
            requirements = TaskRequirements(), dedupeKey = task.dedupeKey) ?: return@tx null
        // The parent points at itself so `where group_id = ?` selects the whole group.
        c.prepareStatement("update tasks set group_id = id where id = ?").use {
            it.setLong(1, parent.id); it.executeUpdate()
        }
        slots.forEachIndexed { i, slot ->
            insertTask(c, task, if (i == 0) GroupRole.INITIATOR else GroupRole.PARTICIPANT,
                groupId = parent.id, assigned = slot.assignedCharacter,
                requirements = slot.requirements, dedupeKey = null)
        }
        event(c, parent.id, null, "created", "group of ${slots.size}")
        loadGroup(c, parent.id)
    }

    private fun insertTask(
        c: Connection, t: NewTask, role: GroupRole, groupId: Long?, assigned: String?,
        requirements: TaskRequirements, dedupeKey: String?,
    ): QueuedTask? = c.prepareStatement(
        """
        insert into tasks as t (type, spec, priority, source, assigned_character, requirements,
                           stop_condition, group_id, group_role, dedupe_key, not_before, expires_at)
        values (?, ?::jsonb, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
        on conflict do nothing
        returning $COLUMNS
        """.trimIndent()
    ).use { ps ->
        ps.setString(1, t.type)
        ps.setString(2, t.spec.toString())
        ps.setInt(3, t.priority)
        ps.setString(4, t.source.dbValue)
        ps.setString(5, assigned)
        ps.setString(6, json.encodeToString(TaskRequirements.serializer(), requirements))
        ps.setString(7, t.stopCondition?.let { json.encodeToString(StopCondition.serializer(), it) })
        if (groupId != null) ps.setLong(8, groupId) else ps.setNull(8, java.sql.Types.BIGINT)
        ps.setString(9, role.dbValue)
        ps.setString(10, dedupeKey)
        ps.setTimestamp(11, t.notBeforeMillis?.let(::Timestamp))
        ps.setTimestamp(12, t.expiresAtMillis?.let(::Timestamp))
        ps.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
    }

    // ── Consuming ───────────────────────────────────────────────────────────

    override suspend fun candidates(character: String, limit: Int, whileBusy: Boolean): List<QueuedTask> = tx { c ->
        val where = if (whileBusy) CLAIMABLE_BASE else CLAIMABLE
        val params = if (whileBusy) BASE_PARAMS else CLAIMABLE_PARAMS
        c.prepareStatement(
            "select $COLUMNS from tasks t where $where order by t.priority desc, t.created_at, t.id limit ?"
        ).use { ps ->
            repeat(params) { ps.setString(1 + it, character) }
            ps.setInt(params + 1, limit)
            ps.executeQuery().use { rs -> rs.list() }
        }
    }

    override suspend fun claim(taskId: Long, character: String): QueuedTask? = tx { c ->
        c.prepareStatement(
            """
            update tasks t set status = 'claimed', claimed_by = ?, claimed_at = now(), heartbeat_at = now()
            where t.id = ? and $CLAIMABLE
            returning $COLUMNS
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, character)
            ps.setLong(2, taskId)
            bindClaimable(ps, 3, character)
            ps.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
        }?.also { event(c, it.id, character, "claimed", null) }
    }

    override suspend fun markRunning(taskId: Long, character: String): Boolean = tx { c ->
        val n = c.prepareStatement(
            "update tasks set status = 'running', heartbeat_at = now() where id = ? and claimed_by = ? and status = 'claimed'"
        ).use { it.setLong(1, taskId); it.setString(2, character); it.executeUpdate() }
        if (n > 0) event(c, taskId, character, "started", null)
        n > 0
    }

    override suspend fun heartbeat(character: String) = tx { c ->
        c.prepareStatement(
            "update tasks set heartbeat_at = now() where claimed_by = ? and status in ('claimed','running')"
        ).use { it.setString(1, character); it.executeUpdate() }
        Unit
    }

    override suspend fun saveCheckpoint(taskId: Long, checkpoint: JsonObject) = tx { c ->
        c.prepareStatement("update tasks set checkpoint = ?::jsonb, heartbeat_at = now() where id = ?").use {
            it.setString(1, checkpoint.toString()); it.setLong(2, taskId); it.executeUpdate()
        }
        Unit
    }

    override suspend fun complete(taskId: Long, message: String?) = tx { c ->
        val who = transition(c, taskId, "status = 'completed', finished_at = now()")
        if (who != null) event(c, taskId, who.ifEmpty { null }, "completed", message)
    }

    override suspend fun fail(taskId: Long, error: String, retry: Boolean) = tx { c ->
        val set = if (retry)
            "status = 'pending', claimed_by = null, claimed_at = null, heartbeat_at = null, attempts = attempts + 1, last_error = ?"
        else
            "status = 'failed', finished_at = now(), attempts = attempts + 1, last_error = ?"
        val who = transition(c, taskId, set, error)
        if (who != null) event(c, taskId, who.ifEmpty { null }, if (retry) "error" else "failed", error)
    }

    override suspend fun suspend(taskId: Long, checkpoint: JsonObject?, reason: String) = tx { c ->
        val who = transition(
            c, taskId,
            "status = 'suspended', claimed_by = null, claimed_at = null, heartbeat_at = null, checkpoint = coalesce(?::jsonb, checkpoint)",
            checkpoint?.toString(),
        )
        if (who != null) event(c, taskId, who.ifEmpty { null }, "suspended", reason)
    }

    override suspend fun cancel(taskId: Long, reason: String) = tx { c ->
        // For a group parent (group_id = id) this also cancels every slot.
        val ids = c.prepareStatement(
            """
            update tasks set status = 'cancelled', finished_at = now(), last_error = ?
            where (id = ? or group_id = ?) and status not in ('completed','failed','cancelled')
            returning id
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, reason); ps.setLong(2, taskId); ps.setLong(3, taskId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
        ids.forEach { event(c, it, null, "cancelled", reason) }
    }

    /**
     * Applies [setClause] to a non-terminal task. Returns the previous `claimed_by`
     * ("" if none) when a row changed, or null when the task was already terminal/missing.
     */
    private fun transition(c: Connection, taskId: Long, setClause: String, param: String? = null): String? {
        val hasParam = setClause.contains('?')
        return c.prepareStatement(
            """
            update tasks t set $setClause
            from (select id, claimed_by from tasks where id = ? for update) old
            where t.id = old.id and t.status not in ('completed','failed','cancelled')
            returning coalesce(old.claimed_by, '')
            """.trimIndent()
        ).use { ps ->
            var i = 1
            if (hasParam) ps.setString(i++, param)
            ps.setLong(i, taskId)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    // ── Groups ──────────────────────────────────────────────────────────────

    override suspend fun group(groupId: Long): GroupState? = tx { c -> loadGroup(c, groupId) }

    private fun loadGroup(c: Connection, groupId: Long): GroupState? {
        val rows = c.prepareStatement("select $COLUMNS from tasks t where t.group_id = ? order by t.id").use {
            it.setLong(1, groupId); it.executeQuery().use { rs -> rs.list() }
        }
        val parent = rows.firstOrNull { it.groupRole == GroupRole.GROUP } ?: return null
        return GroupState(parent, rows.filter { it.groupRole != GroupRole.GROUP })
    }

    override suspend fun releaseGroup(groupId: Long, reason: String) = tx { c ->
        c.prepareStatement(
            """
            update tasks set status = 'pending', claimed_by = null, claimed_at = null, heartbeat_at = null
            where group_id = ? and group_role <> 'group' and status in ('claimed','running','suspended')
            """.trimIndent()
        ).use { it.setLong(1, groupId); it.executeUpdate() }
        event(c, groupId, null, "released", reason)
    }

    override suspend fun completeGroup(groupId: Long, message: String?) = tx { c ->
        c.prepareStatement(
            """
            update tasks set status = 'completed', finished_at = now()
            where group_id = ? and status not in ('completed','failed','cancelled')
            """.trimIndent()
        ).use { it.setLong(1, groupId); it.executeUpdate() }
        event(c, groupId, null, "completed", message)
    }

    // ── Maintenance ─────────────────────────────────────────────────────────

    override suspend fun cancelExpired(): Int = tx { c ->
        val ids = c.prepareStatement(
            """
            update tasks set status = 'cancelled', finished_at = now(), last_error = 'expired'
            where expires_at <= now() and status not in ('completed','failed','cancelled')
            returning id
            """.trimIndent()
        ).use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } } }
        ids.forEach { event(c, it, null, "cancelled", "expired") }
        ids.size
    }

    override suspend fun recoverStale(staleAfterMillis: Long): Int = tx { c ->
        val ids = c.prepareStatement(
            """
            update tasks set status = 'suspended', claimed_by = null, claimed_at = null, heartbeat_at = null
            where status in ('claimed','running')
              and (heartbeat_at is null or heartbeat_at <= now() - (? * interval '1 millisecond'))
            returning id
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, staleAfterMillis)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
        }
        ids.forEach { event(c, it, null, "suspended", "lease expired (recovered)") }
        ids.size
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    override suspend fun get(taskId: Long): QueuedTask? = tx { c ->
        c.prepareStatement("select $COLUMNS from tasks t where t.id = ?").use {
            it.setLong(1, taskId); it.executeQuery().use { rs -> if (rs.next()) map(rs) else null }
        }
    }

    override suspend fun list(filter: TaskFilter): List<QueuedTask> = tx { c ->
        val where = mutableListOf("true")
        val params = mutableListOf<Any>()
        if (filter.statuses.isNotEmpty()) {
            where += "t.status = any(?)"; params.add(filter.statuses.map { it.dbValue }.toTypedArray())
        }
        filter.character?.let {
            where += "(t.claimed_by = ? or t.assigned_character = ?)"; params += it; params += it
        }
        filter.source?.let { where += "t.source = ?"; params += it.dbValue }
        c.prepareStatement(
            "select $COLUMNS from tasks t where ${where.joinToString(" and ")} order by t.id desc limit ?"
        ).use { ps ->
            params.forEachIndexed { i, p ->
                if (p is Array<*>) ps.setArray(i + 1, c.createArrayOf("text", p)) else ps.setString(i + 1, p as String)
            }
            ps.setInt(params.size + 1, filter.limit)
            ps.executeQuery().use { rs -> rs.list() }
        }
    }

    override suspend fun events(taskId: Long, limit: Int): List<TaskEvent> = tx { c ->
        c.prepareStatement(
            "select id, task_id, character, kind, message, data, created_at from task_events where task_id = ? order by id limit ?"
        ).use { ps ->
            ps.setLong(1, taskId); ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add(
                        TaskEvent(
                            id = rs.getLong(1), taskId = rs.getLong(2), character = rs.getString(3),
                            kind = rs.getString(4), message = rs.getString(5), data = rs.jsonObj(6),
                            createdAtMillis = rs.getTimestamp(7).time,
                        )
                    )
                }
            }
        }
    }

    override suspend fun appendEvent(taskId: Long, character: String?, kind: String, message: String?, data: JsonObject?) =
        tx { c -> event(c, taskId, character, kind, message, data) }

    private fun event(c: Connection, taskId: Long, character: String?, kind: String, message: String?, data: JsonObject? = null) {
        c.prepareStatement("insert into task_events (task_id, character, kind, message, data) values (?, ?, ?, ?, ?::jsonb)").use {
            it.setLong(1, taskId); it.setString(2, character); it.setString(3, kind)
            it.setString(4, message); it.setString(5, data?.toString()); it.executeUpdate()
        }
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { c ->
            c.autoCommit = false
            try {
                block(c).also { c.commit() }
            } catch (e: Throwable) {
                c.rollback(); throw e
            }
        }
    }

    private fun ResultSet.list(): List<QueuedTask> = buildList { while (next()) add(map(this@list)) }

    private fun ResultSet.jsonObj(col: Int): JsonObject? =
        getString(col)?.let { json.parseToJsonElement(it) as? JsonObject }

    private fun ResultSet.millis(col: String): Long? = getTimestamp(col)?.time

    private fun map(rs: ResultSet) = QueuedTask(
        id = rs.getLong("id"),
        type = rs.getString("type"),
        spec = json.parseToJsonElement(rs.getString("spec")) as JsonObject,
        priority = rs.getInt("priority"),
        source = TaskSource.fromDb(rs.getString("source")),
        status = TaskStatus.fromDb(rs.getString("status")),
        assignedCharacter = rs.getString("assigned_character"),
        claimedBy = rs.getString("claimed_by"),
        requirements = json.decodeFromString(TaskRequirements.serializer(), rs.getString("requirements")),
        stopCondition = rs.getString("stop_condition")?.let { json.decodeFromString(StopCondition.serializer(), it) },
        groupId = rs.getLong("group_id").takeUnless { rs.wasNull() },
        groupRole = GroupRole.fromDb(rs.getString("group_role")),
        checkpoint = rs.getString("checkpoint")?.let { json.parseToJsonElement(it) as? JsonObject },
        dedupeKey = rs.getString("dedupe_key"),
        notBeforeMillis = rs.millis("not_before"),
        expiresAtMillis = rs.millis("expires_at"),
        attempts = rs.getInt("attempts"),
        lastError = rs.getString("last_error"),
        createdAtMillis = rs.getTimestamp("created_at").time,
        updatedAtMillis = rs.getTimestamp("updated_at").time,
    )

    private companion object {
        const val COLUMNS = """t.id, t.type, t.spec, t.priority, t.source, t.status, t.assigned_character,
            t.claimed_by, t.requirements, t.stop_condition, t.group_id, t.group_role, t.checkpoint,
            t.dedupe_key, t.not_before, t.expires_at, t.attempts, t.last_error, t.created_at, t.updated_at"""

        /**
         * Rows [character] could take, ignoring what it currently holds. Parameters (all =
         * the character name): 1. assignment check, 2. group deadlock check.
         */
        const val CLAIMABLE_BASE = """
            t.status in ('pending','suspended')
            and t.group_role <> 'group'
            and (t.assigned_character is null or t.assigned_character = ?)
            and (t.not_before is null or t.not_before <= now())
            and (t.expires_at is null or t.expires_at > now())
            and not (t.group_id is not null and t.assigned_character is null and exists (
                     select 1 from tasks s where s.group_id = t.group_id and s.id <> t.id
                       and s.assigned_character = ? and s.status in ('pending','suspended')))
        """
        const val BASE_PARAMS = 2

        /** [CLAIMABLE_BASE] plus the one-task-at-a-time rule (3rd parameter). */
        const val CLAIMABLE = CLAIMABLE_BASE + """
            and not exists (select 1 from tasks o
                            where o.claimed_by = ? and o.status in ('claimed','running') and o.id <> t.id)
        """
        const val CLAIMABLE_PARAMS = 3

        fun bindClaimable(ps: PreparedStatement, start: Int, character: String) {
            repeat(CLAIMABLE_PARAMS) { ps.setString(start + it, character) }
        }
    }
}
