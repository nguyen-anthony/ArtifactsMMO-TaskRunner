package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.gateway.ErrorAction
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.utils.CharacterUtils
import com.artifactsmmo.core.task.TransitionConditionUnsatisfiableException
import com.artifactsmmo.domain.queue.QueuedTask
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.queue.TaskQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class WorkerConfig(
    /** Max wait between queue checks when idle (a NOTIFY usually wakes us sooner). */
    val idlePollMillis: Long = 30_000,
    val heartbeatMillis: Long = 30_000,
    val checkpointMillis: Long = 30_000,
    /** Re-check cancel/preemption at least this often even without a notification. */
    val controlCheckMillis: Long = 60_000,
    /** Consecutive failing steps before the task is failed. */
    val maxConsecutiveErrors: Int = 5,
)

@Serializable
enum class WorkerState { IDLE, RUNNING, DISABLED, PAUSED }

/** Live view of a worker, pushed to the UI (not persisted). */
@Serializable
data class WorkerStatus(
    val character: String,
    val state: WorkerState = WorkerState.IDLE,
    val taskId: Long? = null,
    val taskType: String? = null,
    val message: String = "Idle",
    val progress: RunState = RunState(),
    val lastError: String? = null,
    val updatedAtMillis: Long = 0,
)

/**
 * Shared kill switch. Set when the gateway reports an account-level (Fatal) error such as an
 * invalid token; every worker pauses until [resume] is called (e.g. from the UI).
 */
class WorkerControl {
    private val _fatal = MutableStateFlow<String?>(null)
    val fatalReason: StateFlow<String?> = _fatal.asStateFlow()
    fun pause(reason: String) { _fatal.value = reason }
    fun resume() { _fatal.value = null }
}

/**
 * One coroutine per character. Loop:
 *   claim the best eligible task (or enqueue this character's filler) → run it step by step →
 *   complete / fail / suspend → repeat.
 *
 * Between steps it checks whether the task was cancelled or a higher-priority task is
 * waiting (preemption). Preempted tasks are suspended with their checkpoint and go back into
 * the queue, so they resume automatically later — this replaces the old `previousTask` logic.
 */
class CharacterWorker(
    val character: String,
    private val queue: TaskQueue,
    private val executors: ExecutorRegistry,
    private val view: CharacterView,
    private val settings: CharacterSettingsStore,
    /** Suspends until the character's known cooldown ends (gateway CooldownTracker). */
    private val awaitCooldown: suspend (String) -> Unit,
    private val control: WorkerControl = WorkerControl(),
    private val config: WorkerConfig = WorkerConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    /** Log sink (task-scoped lines also go to task_events via the queue). */
    private val log: (String, String) -> Unit = { _, _ -> },
) {
    private val _status = MutableStateFlow(WorkerStatus(character))
    val status: StateFlow<WorkerStatus> = _status.asStateFlow()

    /** Conflated wake-up signal fed by queue change notifications. */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var controlDirty = true

    fun start(scope: CoroutineScope): Job = scope.launch {
        // Subscribe BEFORE the first queue check so no notification is missed (no replay).
        launch(start = CoroutineStart.UNDISPATCHED) {
            queue.changes.collect { controlDirty = true; wake.trySend(Unit) }
        }
        runLoop()
    }

    private suspend fun runLoop() = coroutineScope {
        while (isActive) {
            control.fatalReason.value?.let { reason ->
                setStatus(WorkerState.PAUSED, message = "Paused: $reason", taskId = null, taskType = null)
                control.fatalReason.first { it == null }
            }
            val cfg = runCatching { settings.get(character) }.getOrElse { rethrowCancellation(it); CharacterSettings(character) }
            if (!cfg.enabled) {
                setStatus(WorkerState.DISABLED, message = "Disabled", taskId = null, taskType = null)
                waitForWake()
                continue
            }
            val task = try {
                claimNext(cfg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setStatus(WorkerState.IDLE, message = "Queue error: ${e.message}", lastError = e.message)
                delay(5_000)
                continue
            }
            if (task == null) {
                setStatus(WorkerState.IDLE, message = "Idle", taskId = null, taskType = null)
                waitForWake()
                continue
            }
            runTask(task)
        }
    }

    private suspend fun waitForWake() {
        withTimeoutOrNull(config.idlePollMillis) { wake.receive() }
    }

    // ── Claiming ────────────────────────────────────────────────────────────

    /** Claims the best eligible candidate; falls back to enqueueing the filler task once. */
    private suspend fun claimNext(cfg: CharacterSettings): QueuedTask? {
        tryClaim(cfg)?.let { return it }
        val filler = cfg.filler ?: return null
        queue.enqueue(
            filler.copy(
                source = TaskSource.FILLER,
                priority = TaskSource.FILLER.defaultPriority,
                assignedCharacter = character,
                dedupeKey = "filler:$character",
            )
        )
        return tryClaim(cfg)
    }

    private suspend fun tryClaim(cfg: CharacterSettings): QueuedTask? {
        val candidates = queue.candidates(character)
        if (candidates.isEmpty()) return null
        val snap = view.snapshot(character)
        for (c in candidates) {
            if (!TaskRules.eligible(c, snap, cfg)) continue
            queue.claim(c.id, character)?.let { return it }
        }
        return null
    }

    // ── Running ─────────────────────────────────────────────────────────────

    private suspend fun runTask(task: QueuedTask) = coroutineScope {
        val spec = runCatching { TaskSpec.fromJson(task.spec) }.getOrNull()
        val executor = spec?.let { executors.forSpec(it) }
        if (spec == null || executor == null) {
            queue.fail(task.id, "Unsupported or invalid task spec (type=${task.type})")
            return@coroutineScope
        }
        queue.markRunning(task.id, character)
        val state = task.checkpoint?.let { runCatching { json.decodeFromJsonElement(RunState.serializer(), it) }.getOrNull() }
            ?: RunState()
        val ctx = ExecContext(character, task, spec, state) { msg ->
            log(character, msg)
            setStatus(WorkerState.RUNNING, message = msg)
        }
        setStatus(WorkerState.RUNNING, taskId = task.id, taskType = task.type,
            message = if (task.checkpoint != null) "Resuming ${task.type}" else "Starting ${task.type}", lastError = null)

        val heartbeat = launch {
            while (isActive) { delay(config.heartbeatMillis); runCatching { queue.heartbeat(character) } }
        }
        try {
            val end = execute(task, executor, ctx)
            executor.onStop(ctx)
            finish(task, executor, ctx, end)
        } catch (e: Throwable) {
            executor.onStop(ctx) // e.g. shutdown: still unblock group members
            throw e
        } finally {
            heartbeat.cancel()
        }
    }

    /** How a task run ended. */
    private sealed class End {
        data class Complete(val message: String) : End()
        data class Fail(val message: String) : End()
        data object Cancelled : End()
        data class Suspend(val reason: String) : End()
    }

    private suspend fun execute(task: QueuedTask, executor: TaskExecutor, ctx: ExecContext): End {
        var errors = 0
        var lastSave = clock()
        var lastControl = clock()
        controlDirty = false

        awaitCooldown(character)
        executor.start(ctx)

        while (true) {
            // Control checks: cancelled? higher-priority work waiting?
            if (controlDirty || clock() - lastControl >= config.controlCheckMillis) {
                controlDirty = false
                lastControl = clock()
                controlDecision(task)?.let { return it }
            }
            control.fatalReason.value?.let { return End.Suspend("paused: $it") }

            val snap = ctx.previousChar?.let(::snapshotOf)
            if (TaskRules.stopReached(task.stopCondition, task.type, ctx.state, snap, clock())) {
                return End.Complete("Stop condition reached")
            }

            val outcome: StepOutcome = try {
                executor.step(ctx).also { errors = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransitionConditionUnsatisfiableException) {
                return End.Fail("Cannot reach destination: ${e.message}")
            } catch (e: ArtifactsApiException) {
                ctx.previousChar = null
                record(ctx, "API ${e.errorCode}: ${e.message}")
                when (val action = e.action) {
                    ErrorAction.Fatal -> {
                        control.pause("API ${e.errorCode}: ${e.message}")
                        return End.Suspend("fatal API error ${e.errorCode}")
                    }
                    ErrorAction.WaitCooldown -> { awaitCooldown(character); StepOutcome.Continue() }
                    ErrorAction.Benign -> StepOutcome.Continue()
                    ErrorAction.Retry -> { errors++; StepOutcome.Continue(2_000) }
                    is ErrorAction.Replan -> { errors++; StepOutcome.Continue(3_000) }
                    ErrorAction.FailTask -> { errors++; StepOutcome.Continue(10_000) }
                }
            } catch (e: Exception) {
                ctx.previousChar = null
                record(ctx, "Error: ${e.message}")
                errors++
                StepOutcome.Continue(10_000)
            }

            if (errors >= config.maxConsecutiveErrors) {
                return End.Fail("Giving up after $errors consecutive errors: ${_status.value.lastError}")
            }
            when (outcome) {
                is StepOutcome.Complete -> return End.Complete(outcome.message)
                is StepOutcome.Fail -> return End.Fail(outcome.message)
                is StepOutcome.Continue -> {
                    _status.update { it.copy(progress = ctx.state.copy(), updatedAtMillis = clock()) }
                    if (clock() - lastSave >= config.checkpointMillis) {
                        runCatching { queue.saveCheckpoint(task.id, checkpointOf(ctx.state)) }
                        lastSave = clock()
                    }
                    if (outcome.delayMillis > 0) delay(outcome.delayMillis)
                }
            }
        }
    }

    /** Returns an End if the task must stop now (cancelled / preempted), else null. */
    private suspend fun controlDecision(task: QueuedTask): End? {
        val current = queue.get(task.id) ?: return End.Cancelled
        if (current.status.isTerminal) return End.Cancelled
        val cfg = runCatching { settings.get(character) }.getOrElse { rethrowCancellation(it); CharacterSettings(character) }
        if (!cfg.enabled) return End.Suspend("character disabled")

        // Preempt only for strictly higher-priority, eligible work. `whileBusy` skips the
        // one-task-per-character rule, which would otherwise hide everything while we run.
        val better = queue.candidates(character, limit = 5, whileBusy = true).filter { it.priority > task.priority && it.id != task.id }
        if (better.isEmpty()) return null
        val snap = view.snapshot(character)
        val winner = better.firstOrNull { TaskRules.eligible(it, snap, cfg) } ?: return null
        return End.Suspend("preempted by task ${winner.id} (${winner.type}, priority ${winner.priority})")
    }

    private suspend fun finish(task: QueuedTask, executor: TaskExecutor, ctx: ExecContext, end: End) {
        val checkpoint = checkpointOf(ctx.state)
        when (end) {
            is End.Suspend -> {
                queue.suspend(task.id, checkpoint, end.reason)
                setStatus(WorkerState.IDLE, message = "Suspended: ${end.reason}", taskId = null, taskType = null)
            }
            // Record the terminal status first so cleanup (e.g. group teardown) can see it.
            is End.Complete -> {
                queue.saveCheckpoint(task.id, checkpoint)
                queue.complete(task.id, end.message)
                executor.cleanup(ctx)
                setStatus(WorkerState.IDLE, message = "Completed: ${end.message}", taskId = null, taskType = null)
            }
            is End.Fail -> {
                queue.saveCheckpoint(task.id, checkpoint)
                queue.fail(task.id, end.message)
                executor.cleanup(ctx)
                setStatus(WorkerState.IDLE, message = "Failed: ${end.message}", taskId = null, taskType = null, lastError = end.message)
            }
            End.Cancelled -> {
                executor.cleanup(ctx)
                setStatus(WorkerState.IDLE, message = "Cancelled", taskId = null, taskType = null)
            }
        }
    }

    private fun record(ctx: ExecContext, msg: String) {
        log(character, msg)
        _status.update { it.copy(message = msg, lastError = msg, updatedAtMillis = clock()) }
    }

    private fun setStatus(
        state: WorkerState, message: String,
        taskId: Long? = _status.value.taskId, taskType: String? = _status.value.taskType,
        lastError: String? = _status.value.lastError,
    ) {
        _status.update {
            it.copy(state = state, message = message, taskId = taskId, taskType = taskType,
                lastError = lastError, updatedAtMillis = clock())
        }
    }

    private fun checkpointOf(s: RunState): JsonObject = json.encodeToJsonElement(RunState.serializer(), s).jsonObject

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun snapshotOf(c: Character): CharacterSnapshot = CharacterSnapshot(
            name = c.name,
            level = c.level,
            skills = SKILLS.associateWith { CharacterUtils.getSkillLevel(c, it) ?: 0 },
            freeInventorySlots = c.inventory.count { it.code.isEmpty() || it.quantity == 0 },
        )

        private val SKILLS = listOf(
            "mining", "woodcutting", "fishing", "weaponcrafting", "gearcrafting", "jewelrycrafting", "cooking", "alchemy",
        )
    }
}
