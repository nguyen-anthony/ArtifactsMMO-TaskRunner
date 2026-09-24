package com.artifactsmmo.engine.worker

import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.engine.queue.TaskQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** [CharacterView] backed by the live API (one GET /characters/{name}). */
class ApiCharacterView(private val helper: ActionHelper) : CharacterView {
    override suspend fun snapshot(name: String): CharacterSnapshot =
        CharacterWorker.snapshotOf(helper.refreshCharacter(name))
}

/**
 * Starts one [CharacterWorker] per character plus queue housekeeping.
 * Must only run in the process holding the instance lock.
 */
class WorkerPool(
    private val queue: TaskQueue,
    private val workers: List<CharacterWorker>,
    private val maintenanceMillis: Long = 30_000,
    /** Claimed/running tasks with no heartbeat for this long are returned to the queue. */
    private val staleLeaseMillis: Long = 5 * 60_000,
) {
    val statuses: Map<String, StateFlow<WorkerStatus>> = workers.associate { it.character to it.status }

    fun start(scope: CoroutineScope): Job = scope.launch {
        // Single instance => anything still claimed belongs to a dead process.
        runCatching { queue.recoverStale(0) }
        workers.forEach { it.start(this) }
        launch {
            while (isActive) {
                runCatching { queue.cancelExpired() }
                runCatching { queue.recoverStale(staleLeaseMillis) }
                delay(maintenanceMillis)
            }
        }
    }
}
