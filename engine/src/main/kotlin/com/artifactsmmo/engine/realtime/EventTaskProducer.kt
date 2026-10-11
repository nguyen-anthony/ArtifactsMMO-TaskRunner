package com.artifactsmmo.engine.realtime

import com.artifactsmmo.client.RealtimeMessage
import com.artifactsmmo.client.models.ActiveEvent
import com.artifactsmmo.core.task.EventConfig
import com.artifactsmmo.core.task.disjointParties
import com.artifactsmmo.domain.queue.GroupRole
import com.artifactsmmo.domain.queue.GroupSlot
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

/** What an event spawn turns into: per-character solo tasks, or one co-op boss group. */
sealed interface EventPlan {
    data class Solo(val character: String, val spec: TaskSpec) : EventPlan
    /** [members] are initiator first; every slot row carries [spec]. */
    data class Group(val members: List<String>, val spec: TaskSpec.BossFight) : EventPlan
}

/** Decides which characters respond to an event and with what spec (game logic lives here). */
fun interface EventPlanner {
    suspend fun plan(event: ActiveEvent, config: EventConfig): List<EventPlan>
}

/** Live-event statuses shared with the executors (`eventActive`) and the UI. */
val LIVE_STATUSES = setOf(TaskStatus.PENDING, TaskStatus.CLAIMED, TaskStatus.RUNNING, TaskStatus.SUSPENDED)

/**
 * Turns realtime event messages into queue tasks.
 *
 *  - spawn:   if an enabled config exists, enqueue one EVENT-priority task per chosen
 *             character (assigned to it, expiring with the event), or — for a boss monster
 *             with configured parties — one co-op boss group per party. The dedupe keys make
 *             WebSocket reconnect replays harmless.
 *  - removed: cancel every live task/group for that event. (expires_at also covers a missed message.)
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
        var config = configs.events().firstOrNull { it.eventCode == event.code && it.enabled } ?: return
        val expiration = event.expiresAt
        val expires = expiration?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        val instance = expiration ?: "open"
        // Planning a party runs co-op simulations, so only plan parties not yet queued for this
        // instance (WebSocket spawn + reconnect/startup replay both deliver the same event).
        val parties = disjointParties(config.parties)
        if (parties.isNotEmpty()) {
            val queued = queuedKeys()
            val remaining = parties.filterIndexed { i, p ->
                groupKey(event.code, p.initiatorName, instance) !in queued &&
                    !(i == 0 && legacyGroupKey(event.code, instance) in queued)
            }
            if (remaining.isEmpty()) return
            config = config.copy(groups = remaining, group = null)
        }
        for (plan in planner.plan(event, config)) {
            when (plan) {
                is EventPlan.Solo -> {
                    val t = queue.enqueue(
                        NewTask(
                            type = plan.spec.typeName, spec = plan.spec.toJson(), source = TaskSource.EVENT,
                            assignedCharacter = plan.character, expiresAtMillis = expires,
                            dedupeKey = "event:${event.code}:${plan.character}:$instance",
                        )
                    )
                    if (t != null) log("Event ${event.code}: queued ${plan.spec.typeName} for ${plan.character} (task ${t.id})")
                }
                is EventPlan.Group -> {
                    val group = queue.enqueueGroup(
                        NewTask(
                            type = plan.spec.typeName, spec = plan.spec.toJson(), source = TaskSource.EVENT,
                            dedupeKey = groupKey(event.code, plan.members.first(), instance), expiresAtMillis = expires,
                        ),
                        plan.members.map { GroupSlot(assignedCharacter = it) },
                    )
                    if (group != null) log("Event ${event.code}: queued boss group ${group.parent.id} for ${plan.members.joinToString()}")
                }
            }
        }
    }

    private fun groupKey(eventCode: String, initiator: String, instance: String) = "event:$eventCode:group:$initiator:$instance"
    /** Single-party key used before multi-party configs; treated as the first party's. */
    private fun legacyGroupKey(eventCode: String, instance: String) = "event:$eventCode:group:$instance"

    private suspend fun queuedKeys(): Set<String> =
        queue.list(TaskFilter(statuses = LIVE_STATUSES, source = TaskSource.EVENT, limit = 500)).mapNotNull { it.dedupeKey }.toSet()

    suspend fun onRemoved(eventCode: String) {
        _active.update { it - eventCode }
        val live = queue.list(TaskFilter(statuses = LIVE_STATUSES, source = TaskSource.EVENT, limit = 500))
        // Group slots are cancelled with their parent.
        for (t in live.filter { it.groupRole == GroupRole.SOLO || it.groupRole == GroupRole.GROUP }) {
            val code = runCatching { TaskSpec.fromJson(t.spec).eventCodeOrNull() }.getOrNull()
            if (code == eventCode) queue.cancel(t.id, "event $eventCode ended")
        }
    }
}
