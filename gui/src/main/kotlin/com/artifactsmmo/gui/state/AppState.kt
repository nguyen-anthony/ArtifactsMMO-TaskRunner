package com.artifactsmmo.gui.state

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.ActiveEvent
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.core.task.EventConfig
import com.artifactsmmo.core.task.RunnerStatus
import com.artifactsmmo.core.task.TaskLogger
import com.artifactsmmo.core.task.TaskManager
import com.artifactsmmo.core.task.WebSocketManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Top-level application state for the GUI.
 *
 * Holds the [TaskManager], manages initialization, exposes runner statuses
 * and character details as [StateFlow]s, and provides a log ring-buffer.
 */
class AppState(val scope: CoroutineScope, private val apiToken: String) {

    // ── API client ────────────────────────────────────────────────────────────

    val client: ArtifactsMMOClient by lazy { ArtifactsMMOClient(apiToken) }

    val logger = TaskLogger()

    val taskManager: TaskManager by lazy { TaskManager(client, scope, logger = logger, token = apiToken) }

    // ── Initialization state ──────────────────────────────────────────────────

    sealed class InitState {
        data object Loading : InitState()
        data class Ready(val characterNames: List<String>) : InitState()
        data class Error(val message: String) : InitState()
    }

    private val _initState = MutableStateFlow<InitState>(InitState.Loading)
    val initState: StateFlow<InitState> = _initState.asStateFlow()

    // ── Character statuses (from runners) ─────────────────────────────────────

    private val _statuses = MutableStateFlow<List<RunnerStatus>>(emptyList())
    val statuses: StateFlow<List<RunnerStatus>> = _statuses.asStateFlow()

    // ── Character details (from API) ──────────────────────────────────────────

    private val _characterDetails = MutableStateFlow<Map<String, Character>>(emptyMap())
    val characterDetails: StateFlow<Map<String, Character>> = _characterDetails.asStateFlow()

    // ── Log entries (raw, so the UI can filter by character) ─────────────────

    private val _logEntries = MutableStateFlow<List<TaskLogger.LogEntry>>(emptyList())
    val logEntries: StateFlow<List<TaskLogger.LogEntry>> = _logEntries.asStateFlow()

    // ── Event config ──────────────────────────────────────────────────────────

    private val _eventConfigs = MutableStateFlow<List<EventConfig>>(emptyList())
    val eventConfigs: StateFlow<List<EventConfig>> = _eventConfigs.asStateFlow()

    // ── Active events (from WebSocket dispatcher) ─────────────────────────────

    @OptIn(kotlin.time.ExperimentalTime::class)
    private val _activeEvents = MutableStateFlow<Map<String, ActiveEvent>>(emptyMap())
    @OptIn(kotlin.time.ExperimentalTime::class)
    val activeEvents: StateFlow<Map<String, ActiveEvent>> = _activeEvents.asStateFlow()

    // ── WebSocket log entries ─────────────────────────────────────────────────

    @OptIn(kotlin.time.ExperimentalTime::class)
    private val _wsLogEntries = MutableStateFlow<List<WebSocketManager.WebSocketLogEntry>>(emptyList())
    @OptIn(kotlin.time.ExperimentalTime::class)
    val wsLogEntries: StateFlow<List<WebSocketManager.WebSocketLogEntry>> = _wsLogEntries.asStateFlow()

    private var logPollJob: Job? = null
    private var statusPollJob: Job? = null

    // ── Initialization ────────────────────────────────────────────────────────

    init {
        scope.launch { initialize() }
    }

    private suspend fun initialize() {
        _initState.value = InitState.Loading
        try {
            val names = taskManager.initialize()
            _initState.value = InitState.Ready(names)
            refreshCharacterDetails(names)
            startPolling(names)
            _eventConfigs.value = taskManager.getEventConfigs()
            scope.launch {
                @OptIn(kotlin.time.ExperimentalTime::class)
                taskManager.eventDispatcher.activeEvents.collect { _activeEvents.value = it }
            }
        } catch (e: Exception) {
            _initState.value = InitState.Error(e.message ?: "Unknown error during initialization")
        }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling(names: List<String>) {
        // Poll runner statuses every second (they're already StateFlows but we
        // snapshot them here so the dashboard can use a single list flow).
        statusPollJob?.cancel()
        statusPollJob = scope.launch {
            while (isActive) {
                _statuses.value = taskManager.getAllStatuses()
                delay(1_000)
            }
        }

        // Poll character details every 60 seconds
        scope.launch {
            while (isActive) {
                delay(60_000)
                refreshCharacterDetails(names)
            }
        }

        // Poll log entries every 500 ms
        logPollJob?.cancel()
        logPollJob = scope.launch {
            while (isActive) {
                _logEntries.value = logger.getRecent(300)
                delay(500)
            }
        }

        // Poll WebSocket log entries every 1 s
        scope.launch {
            while (isActive) {
                @OptIn(kotlin.time.ExperimentalTime::class)
                _wsLogEntries.value = taskManager.getRecentWebSocketLogs(500)
                delay(1_000)
            }
        }
    }

    // ── Public actions ────────────────────────────────────────────────────────

    /** Manually refresh character data from the API for all characters. */
    suspend fun refreshCharacterDetails(names: List<String> = taskManager.getCharacterNames()) {
        val details = mutableMapOf<String, Character>()
        for (name in names) {
            runCatching { taskManager.getCharacterDetails(name) }
                .onSuccess { details[name] = it }
        }
        _characterDetails.value = details
    }

    /** Stop all running tasks without clearing the persisted task file. */
    fun stopAll() {
        taskManager.stopAll()
    }

    /** Live bank snapshot from [BankState] — no polling needed, it's already a StateFlow. */
    val bankSnapshot: StateFlow<Map<String, Int>> get() = taskManager.bankState.snapshot

    /** Save event configurations and update the local flow. */
    fun saveEventConfigs(configs: List<EventConfig>) {
        taskManager.saveEventConfigs(configs)
        _eventConfigs.value = configs
    }
}
