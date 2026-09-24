package com.artifactsmmo.engine.realtime

import com.artifactsmmo.client.RealtimeMessage
import com.artifactsmmo.client.models.ActiveEvent
import com.artifactsmmo.core.task.EventConfig
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.TaskFilter
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.queue.TaskStatus
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.config.TypedConfigs
import com.artifactsmmo.engine.queue.TaskQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** Decides which characters respond to an event and with what spec (game logic lives here). */
fun interface EventPlanner {
    suspend fun plan(event: ActiveEvent, config: EventConfig): List<Pair<String, TaskSpec>>
}

/** Live-event statuses shared with the executors (`eventActive`) and the UI. */
val LIVE_STATUSES = setOf(TaskStatus.PENDING, TaskStatus.CLAIMED, TaskStatus.RUNNING, TaskStatus.SUSPENDED)

/**
 * Turns realtime event messages into queue tasks.
 *
 *  - spawn:   if an enabled config exists, enqueue one EVENT-priority task per chosen
 *             character (assigned to it, expiring with the event). The dedupe key makes
 *             WebSocket reconnect replays harmless.
 *  - removed: cancel every live task for that event. (expires_at also covers a missed message.)
 *
 * Preemption does the rest: a busy character suspends its current task, runs the event
 * task, and the suspended task resumes afterwards.
 */
class EventTaskProducer(
    private val messages: Flow<RealtimeMessage>,
    private val queue: TaskQueue,
    private val configs: TypedConfigs,
    private val planner: EventPlanner,
    private val log: (String) -> Unit = {},
) {
    private val _active = MutableStateFlow<Map<String, ActiveEvent>>(emptyMap())
    val activeEvents: StateFlow<Map<String, ActiveEvent>> = _active.asStateFlow()

    fun isActive(eventCode: String): Boolean = eventCode in _active.value

    fun start(scope: CoroutineScope): Job = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
        messages.collect { m ->
            try {
                when (m) {
                    is RealtimeMessage.EventSpawn -> onSpawn(m.event)
                    is RealtimeMessage.EventRemoved -> onRemoved(m.event.code)
                    else -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("EventTaskProducer: error handling $m: ${e.message}")
            }
        }
    }

    suspend fun onSpawn(event: ActiveEvent) {
        _active.update { it + (event.code to event) }
        val config = configs.events().firstOrNull { it.eventCode == event.code && it.enabled } ?: return
        val expires = event.map.expiration?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        for ((character, spec) in planner.plan(event, config)) {
            val t = queue.enqueue(
                NewTask(
                    type = spec.typeName, spec = spec.toJson(), source = TaskSource.EVENT,
                    assignedCharacter = character, expiresAtMillis = expires,
                    dedupeKey = "event:${event.code}:$character:${event.map.expiration ?: "open"}",
                )
            )
            if (t != null) log("Event ${event.code}: queued ${spec.typeName} for $character (task ${t.id})")
        }
    }

    suspend fun onRemoved(eventCode: String) {
        _active.update { it - eventCode }
        val live = queue.list(TaskFilter(statuses = LIVE_STATUSES, source = TaskSource.EVENT, limit = 500))
        for (t in live) {
            val code = runCatching { TaskSpec.fromJson(t.spec).eventCodeOrNull() }.getOrNull()
            if (code == eventCode) queue.cancel(t.id, "event $eventCode ended")
        }
    }
}
