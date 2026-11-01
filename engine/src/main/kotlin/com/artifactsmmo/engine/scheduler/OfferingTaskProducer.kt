package com.artifactsmmo.engine.scheduler

import com.artifactsmmo.core.task.OfferingConfig
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.config.TypedConfigs
import com.artifactsmmo.engine.queue.TaskQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Enqueues one `daily_offering` task per enabled character per UTC day (the altar rotates at
 * 00:00 UTC). The task expires at the next rotation. Each character/day is enqueued once per
 * process — after a restart the task is enqueued again and finishes immediately if everything
 * is already completed; a task cancelled by hand is not re-created the same day.
 */
class OfferingTaskProducer(
    private val queue: TaskQueue,
    private val configs: TypedConfigs,
    private val characters: () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = 60_000,
    private val log: (String) -> Unit = {},
) {
    private val enqueued = mutableSetOf<String>()

    fun start(scope: CoroutineScope): Job = scope.launch {
        while (isActive) {
            try { tick() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("OfferingTaskProducer: ${e.message}") }
            delay(pollMillis)
        }
    }

    suspend fun tick() {
        val now = clock()
        val day = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate()
        val rotation = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        enqueued.removeAll { !it.endsWith(":$day") }

        val configured = configs.offerings().associateBy { it.character }
        for (name in characters()) {
            val config = configured[name] ?: OfferingConfig(name)
            val key = "$name:$day"
            if (!config.enabled || key in enqueued) continue
            val spec = TaskSpec.DailyOffering(config.categories)
            val task = queue.enqueue(
                NewTask(
                    type = spec.typeName, spec = spec.toJson(), source = TaskSource.SCHEDULE,
                    priority = config.priority, assignedCharacter = name,
                    dedupeKey = dedupeKey(name, day), expiresAtMillis = rotation,
                )
            )
            enqueued += key
            if (task != null) log("Daily offerings queued for $name ($day, task ${task.id})")
        }
    }

    companion object {
        fun dedupeKey(character: String, day: LocalDate) = "offering:$character:$day"
    }
}
