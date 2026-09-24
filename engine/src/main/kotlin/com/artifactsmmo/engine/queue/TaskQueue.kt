package com.artifactsmmo.engine.queue

import com.artifactsmmo.domain.queue.GroupSlot
import com.artifactsmmo.domain.queue.GroupState
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.QueuedTask
import com.artifactsmmo.domain.queue.TaskEvent
import com.artifactsmmo.domain.queue.TaskFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Durable task queue. The engine only depends on this interface; the Postgres
 * implementation lives in `server` (JdbcTaskQueue).
 *
 * Claiming model: a worker asks for [candidates], filters them in Kotlin against its live
 * character state (skill levels, inventory, …), then calls [claim] on the best one. [claim]
 * is an atomic conditional UPDATE, so if two workers race for the same row exactly one wins
 * and the other gets null and simply tries its next candidate.
 */
interface TaskQueue {
    // ── Producing ───────────────────────────────────────────────────────────
    /** Returns the new task, or null if a live task with the same dedupe key exists. */
    suspend fun enqueue(task: NewTask): QueuedTask?

    /**
     * Inserts a group (parent + one slot per entry in [slots]; the first slot is the
     * initiator). The parent itself is never claimable. Returns null on dedupe conflict.
     */
    suspend fun enqueueGroup(task: NewTask, slots: List<GroupSlot>): GroupState?

    // ── Consuming ───────────────────────────────────────────────────────────
    /**
     * Claimable rows this character could take, best first (priority desc, oldest first).
     * Normally empty while the character already holds a task; [whileBusy] = true ignores
     * that rule so a running worker can look for higher-priority work (preemption).
     */
    suspend fun candidates(character: String, limit: Int = 20, whileBusy: Boolean = false): List<QueuedTask>

    /** Atomically claims [taskId] for [character]; null if someone else got it first. */
    suspend fun claim(taskId: Long, character: String): QueuedTask?

    suspend fun markRunning(taskId: Long, character: String): Boolean

    /** Extends the lease on everything this character holds. */
    suspend fun heartbeat(character: String)

    suspend fun saveCheckpoint(taskId: Long, checkpoint: JsonObject)

    suspend fun complete(taskId: Long, message: String? = null)

    /** [retry] = true puts the task back to pending (attempts is incremented either way). */
    suspend fun fail(taskId: Long, error: String, retry: Boolean = false)

    /** Preempted: keep the checkpoint, free the character, make it claimable again. */
    suspend fun suspend(taskId: Long, checkpoint: JsonObject?, reason: String)

    suspend fun cancel(taskId: Long, reason: String = "cancelled")

    // ── Groups ──────────────────────────────────────────────────────────────
    suspend fun group(groupId: Long): GroupState?

    /** Un-claims every slot of the group (formation timed out / a member dropped out). */
    suspend fun releaseGroup(groupId: Long, reason: String)

    /** Completes the parent and every non-terminal slot. */
    suspend fun completeGroup(groupId: Long, message: String? = null)

    // ── Maintenance ─────────────────────────────────────────────────────────
    /** Cancels live tasks whose expires_at has passed. Returns the number cancelled. */
    suspend fun cancelExpired(): Int

    /**
     * Returns claimed/running tasks whose heartbeat is older than [staleAfterMillis] to the
     * queue as suspended (checkpoint kept). Pass 0 on boot to recover everything, since
     * only one instance ever runs (see the advisory instance lock).
     */
    suspend fun recoverStale(staleAfterMillis: Long): Int

    // ── Reading ─────────────────────────────────────────────────────────────
    suspend fun get(taskId: Long): QueuedTask?
    suspend fun list(filter: TaskFilter = TaskFilter()): List<QueuedTask>
    suspend fun events(taskId: Long, limit: Int = 200): List<TaskEvent>
    suspend fun appendEvent(taskId: Long, character: String?, kind: String, message: String?, data: JsonObject? = null)

    /** Emits the task id whenever a task row changes (Postgres NOTIFY). */
    val changes: Flow<Long>
}
