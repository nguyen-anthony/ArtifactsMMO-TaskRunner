package com.artifactsmmo.gui.state

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.core.task.RunnerStatus
import com.artifactsmmo.core.task.TaskLogger
import com.artifactsmmo.core.task.TaskManager
import io.github.cdimascio.dotenv.dotenv
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
class AppState(val scope: CoroutineScope) {

    // ── API client ────────────────────────────────────────────────────────────

    private val apiToken: String by lazy {
        runCatching {
            dotenv { ignoreIfMissing = true }["ARTIFACTS_API_TOKEN"]
        }.getOrElse {
            System.getenv("ARTIFACTS_API_TOKEN")
                ?: error("ARTIFACTS_API_TOKEN not found in .env or environment")
        }
    }

    val client: ArtifactsMMOClient by lazy { ArtifactsMMOClient(apiToken) }

    val logger = TaskLogger()

    val taskManager: TaskManager by lazy { TaskManager(client, scope, logger = logger) }

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

        // Poll character details every 30 seconds
        scope.launch {
            while (isActive) {
                delay(30_000)
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
}
