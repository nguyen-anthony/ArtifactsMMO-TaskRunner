package com.artifactsmmo.engine.scheduler

import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.TaskSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A row of `schedules`: "enqueue this task every N seconds". */
data class Schedule(
    val id: Long,
    val name: String,
    val intervalSeconds: Int,
    /** Template for the task to enqueue (source/priority/dedupe are set by the runner). */
    val task: NewTask,
    val nextRunAtMillis: Long?,
)

interface ScheduleStore {
    /** Enabled schedules with next_run_at null or <= now. */
    suspend fun due(nowMillis: Long): List<Schedule>
    suspend fun markRun(id: Long, ranAtMillis: Long, nextRunAtMillis: Long)
}

/**
 * Enqueues recurring tasks. Each run uses dedupe key `schedule:<id>`, so if the previous
 * run's task is still live the new one is skipped instead of piling up.
 * (Only interval schedules are supported for now; cron rows are ignored by the store.)
 */
class ScheduleRunner(
    private val store: ScheduleStore,
    private val queue: com.artifactsmmo.engine.queue.TaskQueue,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = 30_000,
    private val log: (String) -> Unit = {},
) {
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try { tick() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("ScheduleRunner: ${e.message}") }
            delay(pollMillis)
        }
    }

    suspend fun tick() {
        val now = clock()
        for (s in store.due(now)) {
            val t = queue.enqueue(s.task.copy(source = TaskSource.SCHEDULE, dedupeKey = "schedule:${s.id}"))
            store.markRun(s.id, now, now + s.intervalSeconds * 1000L)
            log(if (t != null) "Schedule '${s.name}': queued task ${t.id}" else "Schedule '${s.name}': previous run still live, skipped")
        }
    }
}
