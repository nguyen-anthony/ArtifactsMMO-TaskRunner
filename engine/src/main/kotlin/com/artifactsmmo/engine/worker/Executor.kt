package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.domain.task.TaskSpec
import kotlinx.serialization.Serializable

/**
 * Progress for one task run. Persisted as the task's `checkpoint` so a suspended or
 * crash-recovered task resumes with its counters (and doesn't re-run one-time setup).
 */
@Serializable
data class RunState(
    /** One-time setup (equip plan, bank prep) has run. */
    var prepared: Boolean = false,
    var gathers: Int = 0,
    var fightsWon: Int = 0,
    var fightsLost: Int = 0,
    var crafted: Int = 0,
    var recycled: Int = 0,
    var bankTrips: Int = 0,
    var tasksCompleted: Int = 0,
    var consecutiveDeaths: Int = 0,
)

/** Everything an executor needs for one step. */
class ExecContext(
    val character: String,
    val taskId: Long,
    val spec: TaskSpec,
    val state: RunState,
    /** Report a human-readable status line (shown in the UI and logged). */
    val status: (String) -> Unit,
) {
    /**
     * Character returned by the previous action, reused to skip a GET /characters call.
     * Executors set it; the worker clears it after errors.
     */
    var previousChar: Character? = null
}

/** What the worker should do after a step. */
sealed class StepOutcome {
    /** Keep going; optionally pause first. */
    data class Continue(val delayMillis: Long = 0) : StepOutcome()
    /** The task reached its natural end. */
    data class Complete(val message: String) : StepOutcome()
    /** The task cannot continue. */
    data class Fail(val message: String) : StepOutcome()
}

/**
 * Runs one kind of task, one step at a time. A step should be short (one or a few API
 * actions) so the worker can check for cancellation/preemption between steps.
 */
interface TaskExecutor {
    /** Called before the first step of every (re)start. Use [RunState.prepared] for one-time work. */
    suspend fun start(ctx: ExecContext) {}
    suspend fun step(ctx: ExecContext): StepOutcome
    /** Called when the task ends for good (complete/cancel/fail), not on suspend. */
    suspend fun cleanup(ctx: ExecContext) {}
}

/** Picks the executor for a spec; null = unsupported (the task fails). */
fun interface ExecutorRegistry {
    fun forSpec(spec: TaskSpec): TaskExecutor?
}
