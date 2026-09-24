package com.artifactsmmo.engine.worker

import com.artifactsmmo.domain.queue.GroupRole
import com.artifactsmmo.domain.queue.GroupSlot
import com.artifactsmmo.domain.queue.GroupState
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.QueuedTask
import com.artifactsmmo.domain.queue.TaskEvent
import com.artifactsmmo.domain.queue.TaskFilter
import com.artifactsmmo.domain.queue.TaskStatus
import com.artifactsmmo.engine.queue.TaskQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * In-memory [TaskQueue] mirroring JdbcTaskQueue's rules (claimability, one task per
 * character, dedupe while live). Time comes from [clock] so tests can use virtual time.
 */
class FakeTaskQueue(private val clock: () -> Long) : TaskQueue {
    private val mutex = Mutex()
    private val rows = linkedMapOf<Long, QueuedTask>()
    val eventLog = mutableListOf<TaskEvent>()
    private var nextId = 1L
    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 1024)
    override val changes: Flow<Long> = _changes

    private fun put(t: QueuedTask) { rows[t.id] = t.copy(updatedAtMillis = clock()); _changes.tryEmit(t.id) }
    private fun ev(id: Long, who: String?, kind: String, msg: String?) {
        eventLog += TaskEvent(eventLog.size + 1L, id, who, kind, msg, null, clock())
    }
    private fun live(t: QueuedTask) = !t.status.isTerminal

    override suspend fun enqueue(task: NewTask): QueuedTask? = mutex.withLock {
        if (task.dedupeKey != null && rows.values.any { live(it) && it.dedupeKey == task.dedupeKey }) return null
        val t = QueuedTask(
            id = nextId++, type = task.type, spec = task.spec, priority = task.priority, source = task.source,
            status = TaskStatus.PENDING, assignedCharacter = task.assignedCharacter, requirements = task.requirements,
            stopCondition = task.stopCondition, dedupeKey = task.dedupeKey, notBeforeMillis = task.notBeforeMillis,
            expiresAtMillis = task.expiresAtMillis, createdAtMillis = clock(), updatedAtMillis = clock(),
        )
        put(t); ev(t.id, null, "created", null); t
    }

    override suspend fun enqueueGroup(task: NewTask, slots: List<GroupSlot>): GroupState? {
        val parentId = mutex.withLock {
            val base = QueuedTask(
                id = 0, type = task.type, spec = task.spec, priority = task.priority, source = task.source,
                status = TaskStatus.PENDING, stopCondition = task.stopCondition, createdAtMillis = clock(), updatedAtMillis = clock(),
            )
            val pid = nextId++
            put(base.copy(id = pid, groupId = pid, groupRole = GroupRole.GROUP))
            slots.forEachIndexed { i, slot ->
                put(base.copy(id = nextId++, groupId = pid, assignedCharacter = slot.assignedCharacter,
                    requirements = slot.requirements, stopCondition = if (i == 0) task.stopCondition else null,
                    groupRole = if (i == 0) GroupRole.INITIATOR else GroupRole.PARTICIPANT))
            }
            pid
        }
        return group(parentId)
    }

    private fun claimable(t: QueuedTask, c: String, whileBusy: Boolean): Boolean {
        val now = clock()
        return (t.status == TaskStatus.PENDING || t.status == TaskStatus.SUSPENDED) &&
            t.groupRole != GroupRole.GROUP &&
            (t.assignedCharacter == null || t.assignedCharacter == c) &&
            (t.notBeforeMillis?.let { it <= now } ?: true) &&
            (t.expiresAtMillis?.let { it > now } ?: true) &&
            !(t.groupId != null && t.assignedCharacter == null && rows.values.any {
                it.groupId == t.groupId && it.id != t.id && it.assignedCharacter == c &&
                    (it.status == TaskStatus.PENDING || it.status == TaskStatus.SUSPENDED) }) &&
            (whileBusy || rows.values.none { it.id != t.id && it.claimedBy == c &&
                (it.status == TaskStatus.CLAIMED || it.status == TaskStatus.RUNNING) })
    }

    override suspend fun candidates(character: String, limit: Int, whileBusy: Boolean) = mutex.withLock {
        rows.values.filter { claimable(it, character, whileBusy) }
            .sortedWith(compareByDescending<QueuedTask> { it.priority }.thenBy { it.createdAtMillis }.thenBy { it.id })
            .take(limit)
    }

    override suspend fun claim(taskId: Long, character: String): QueuedTask? = mutex.withLock {
        val t = rows[taskId]?.takeIf { claimable(it, character, false) } ?: return null
        val n = t.copy(status = TaskStatus.CLAIMED, claimedBy = character)
        put(n); ev(taskId, character, "claimed", null); n
    }

    private suspend fun mutate(id: Long, kind: String, msg: String?, f: (QueuedTask) -> QueuedTask?) = mutex.withLock {
        val t = rows[id]?.takeIf(::live) ?: return@withLock false
        val n = f(t) ?: return@withLock false
        put(n); ev(id, t.claimedBy, kind, msg); true
    }

    override suspend fun markRunning(taskId: Long, character: String) = mutate(taskId, "started", null) {
        if (it.claimedBy == character && it.status == TaskStatus.CLAIMED) it.copy(status = TaskStatus.RUNNING) else null
    }

    override suspend fun heartbeat(character: String) {}

    override suspend fun saveCheckpoint(taskId: Long, checkpoint: JsonObject) = mutex.withLock {
        rows[taskId]?.let { rows[taskId] = it.copy(checkpoint = checkpoint) }; Unit
    }

    override suspend fun complete(taskId: Long, message: String?) {
        mutate(taskId, "completed", message) { it.copy(status = TaskStatus.COMPLETED) }
    }

    override suspend fun fail(taskId: Long, error: String, retry: Boolean) {
        mutate(taskId, if (retry) "error" else "failed", error) {
            if (retry) it.copy(status = TaskStatus.PENDING, claimedBy = null, attempts = it.attempts + 1, lastError = error)
            else it.copy(status = TaskStatus.FAILED, attempts = it.attempts + 1, lastError = error)
        }
    }

    override suspend fun suspend(taskId: Long, checkpoint: JsonObject?, reason: String) {
        mutate(taskId, "suspended", reason) {
            it.copy(status = TaskStatus.SUSPENDED, claimedBy = null, checkpoint = checkpoint ?: it.checkpoint)
        }
    }

    override suspend fun cancel(taskId: Long, reason: String) {
        val ids = mutex.withLock { rows.values.filter { (it.id == taskId || it.groupId == taskId) && live(it) }.map { it.id } }
        ids.forEach { mutate(it, "cancelled", reason) { t -> t.copy(status = TaskStatus.CANCELLED, lastError = reason) } }
    }

    override suspend fun group(groupId: Long): GroupState? = mutex.withLock {
        val all = rows.values.filter { it.groupId == groupId }
        val parent = all.firstOrNull { it.groupRole == GroupRole.GROUP } ?: return@withLock null
        GroupState(parent, all.filter { it.groupRole != GroupRole.GROUP })
    }
    override suspend fun releaseGroup(groupId: Long, reason: String) {}
    override suspend fun completeGroup(groupId: Long, message: String?) {
        val ids = mutex.withLock { rows.values.filter { it.groupId == groupId && live(it) }.map { it.id } }
        ids.forEach { mutate(it, "completed", message) { t -> t.copy(status = TaskStatus.COMPLETED) } }
    }
    override suspend fun cancelExpired(): Int = 0
    override suspend fun recoverStale(staleAfterMillis: Long): Int = 0
    override suspend fun get(taskId: Long): QueuedTask? = mutex.withLock { rows[taskId] }
    override suspend fun list(filter: TaskFilter): List<QueuedTask> = mutex.withLock { rows.values.toList() }
    override suspend fun events(taskId: Long, limit: Int) = eventLog.filter { it.taskId == taskId }
    override suspend fun appendEvent(taskId: Long, character: String?, kind: String, message: String?, data: JsonObject?) {
        ev(taskId, character, kind, message)
    }
}
