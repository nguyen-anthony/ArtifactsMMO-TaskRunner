package com.artifactsmmo.core.task

import com.artifactsmmo.client.RealtimeMessage
import com.artifactsmmo.client.models.ActiveEvent
import com.artifactsmmo.client.utils.CharacterUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Listens to [WebSocketManager.messages] and dispatches event tasks to characters.
 *
 * Active events are tracked in [activeEvents] (keyed by event code) so that
 * [EventExecutor.executeGatherStep] can check liveness on every tick.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class EventDispatcher(
    private val webSocketManager: WebSocketManager,
    private val eventConfigStore: EventConfigStore,
    private val taskManager: TaskManager,
    private val contentCache: ContentCache,
    private val scope: CoroutineScope
) {
    private val _activeEvents = MutableStateFlow<Map<String, ActiveEvent>>(emptyMap())
    val activeEvents: StateFlow<Map<String, ActiveEvent>> = _activeEvents

    private var collectJob: Job? = null

    /** Loaded config — refreshed by [reloadConfig]. */
    private var configs: List<EventConfig> = emptyList()

    fun start() {
        configs = eventConfigStore.load()
        collectJob?.cancel()
        collectJob = scope.launch {
            webSocketManager.messages.collect { message ->
                when (message) {
                    is RealtimeMessage.EventSpawn   -> handleSpawn(message.event)
                    is RealtimeMessage.EventRemoved -> handleRemoved(message.event)
                    is RealtimeMessage.AccountLog   -> {} // handled in WebSocketManager → BankState
                    is RealtimeMessage.Unknown      -> {}
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    fun reloadConfig() {
        configs = eventConfigStore.load()
    }

    private suspend fun handleSpawn(event: ActiveEvent) {
        // Track active event
        _activeEvents.value = _activeEvents.value + (event.code to event)

        taskManager.logger.log("EventDispatcher: spawn [${event.name}] code=${event.code} type=${event.content.type} at (${event.map.x},${event.map.y})")

        val config = configs.find { it.eventCode == event.code && it.enabled }
        if (config == null) {
            taskManager.logger.log("EventDispatcher: no enabled config for event code=${event.code} — skipping (configs=${configs.map { it.eventCode }})")
            return
        }

        when (event.content.type) {
            "resource" -> dispatchResourceEvent(event, config)
            "npc"      -> dispatchNpcEvent(event, config)
            "monster"  -> taskManager.logger.log("EventDispatcher: monster event [${event.name}] skipped — deferred")
            else       -> taskManager.logger.log("EventDispatcher: unknown content type '${event.content.type}' for [${event.name}]")
        }
    }

    private fun handleRemoved(event: ActiveEvent) {
        _activeEvents.value = _activeEvents.value - event.code
        taskManager.revertEventTasks(event.code)
    }

    private suspend fun dispatchResourceEvent(event: ActiveEvent, config: EventConfig) {
        val resource = try {
            contentCache.getResource(event.content.code)
        } catch (e: Exception) {
            taskManager.logger.log("EventDispatcher: failed to load resource ${event.content.code}: ${e.message}")
            return
        }

        taskManager.logger.log("EventDispatcher: resource event [${event.name}] — resource=${resource.code} skill=${resource.skill} level=${resource.level}")

        val candidateNames = if (config.eligibleCharacters.isNotEmpty())
            config.eligibleCharacters
        else
            taskManager.getCharacterNames()

        taskManager.logger.log("EventDispatcher: checking ${candidateNames.size} candidate(s): $candidateNames")

        for (name in candidateNames) {
            val char = try {
                taskManager.getCharacterDetails(name)
            } catch (e: Exception) {
                taskManager.logger.log("EventDispatcher: could not fetch character $name — ${e.message}")
                continue
            }

            val skillLevel = CharacterUtils.getSkillLevel(char, resource.skill) ?: 0
            if (skillLevel < resource.level) {
                taskManager.logger.log("EventDispatcher: $name skipped — ${resource.skill} level $skillLevel < required ${resource.level}")
                continue
            }

            taskManager.logger.log("EventDispatcher: assigning event gather [${event.name}] to $name (${resource.skill} Lv.$skillLevel)")
            taskManager.assignEventTask(
                name,
                TaskType.EventGather(
                    eventCode = event.code,
                    resourceCode = resource.code,
                    resourceName = resource.name,
                    skill = resource.skill,
                    eventMapX = event.map.x,
                    eventMapY = event.map.y,
                    eventMapLayer = event.map.layer
                )
            )
        }
    }

    private fun dispatchNpcEvent(event: ActiveEvent, config: EventConfig) {
        val trader = config.designatedTrader
        if (trader == null) {
            taskManager.logger.log("EventDispatcher: NPC event [${event.name}] skipped — no designatedTrader configured")
            return
        }
        if (config.itemsToSell.isEmpty() && config.itemsToBuy.isEmpty()) {
            taskManager.logger.log("EventDispatcher: NPC event [${event.name}] skipped — no items configured")
            return
        }
        taskManager.logger.log("EventDispatcher: dispatching NPC event [${event.name}] to $trader — sell=${config.itemsToSell.map { it.code }} buy=${config.itemsToBuy.map { "${it.code}x${it.quantity}" }}")
        taskManager.assignEventTask(
            trader,
            TaskType.EventNpc(
                eventCode = event.code,
                npcCode = event.content.code,
                npcName = event.name,
                eventMapX = event.map.x,
                eventMapY = event.map.y,
                eventMapLayer = event.map.layer,
                itemsToSell = config.itemsToSell,
                itemsToBuy = config.itemsToBuy
            )
        )
    }
}
