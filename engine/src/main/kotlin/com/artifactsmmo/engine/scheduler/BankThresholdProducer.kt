package com.artifactsmmo.engine.scheduler

import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Resource
import com.artifactsmmo.core.task.BankThreshold
import com.artifactsmmo.core.task.ThresholdAction
import com.artifactsmmo.core.task.ThresholdPlanner
import com.artifactsmmo.core.task.ThresholdState
import com.artifactsmmo.core.task.ThresholdStatus
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.StopCondition
import com.artifactsmmo.domain.queue.TaskFilter
import com.artifactsmmo.domain.queue.TaskRequirements
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.queue.TaskStatus
import com.artifactsmmo.domain.task.CraftMode
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.config.TypedConfigs
import com.artifactsmmo.engine.queue.TaskQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Bank restocking: for every enabled [BankThreshold] whose item is below its minimum in the
 * bank, queue one unassigned SYSTEM task (craft from bank ingredients, or gather N times)
 * that any character with the skill level can claim. One live task per rule (dedupe key),
 * and a back-off after a failed one. Reads the cached bank snapshot only (no API calls
 * besides item/resource lookups, which are cached).
 */
class BankThresholdProducer(
    private val queue: TaskQueue,
    private val configs: TypedConfigs,
    private val bank: () -> Map<String, Int>,
    private val itemOf: suspend (String) -> Item?,
    private val resourcesDropping: suspend (String) -> List<Resource>,
    /** Publishes item -> reserve quantity for consumers (Task Master). */
    private val onReserves: (Map<String, Int>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = 180_000,
    private val log: (String) -> Unit = {},
) {
    @Volatile var statuses: Map<String, ThresholdStatus> = emptyMap()
        private set

    fun start(scope: CoroutineScope, initialDelayMillis: Long = 60_000): Job = scope.launch {
        // Let the bank snapshot load first: an empty bank would trigger every rule.
        delay(initialDelayMillis)
        while (isActive) {
            try { tick() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("BankThresholdProducer: ${e.message}") }
            delay(pollMillis)
        }
    }

    suspend fun tick(): Map<String, ThresholdStatus> {
        val settings = configs.thresholdSettings()
        val rules = configs.thresholds()
        onReserves(ThresholdPlanner.reserves(rules, settings))
        val now = clock()
        val bankNow = bank()
        if (rules.isEmpty()) return emptyMap<String, ThresholdStatus>().also { statuses = it }
        if (bankNow.isEmpty()) return statuses  // snapshot not loaded yet: don't treat everything as missing

        val live = queue.list(TaskFilter(statuses = LIVE, source = TaskSource.SYSTEM, limit = 500))
            .filter { it.dedupeKey?.startsWith(KEY_PREFIX) == true }.associateBy { it.dedupeKey }
        val backoffMillis = settings.failureBackoffMinutes * 60_000L
        val recentFailures = queue.list(TaskFilter(statuses = setOf(TaskStatus.FAILED), source = TaskSource.SYSTEM, limit = 200))
            .filter { it.dedupeKey?.startsWith(KEY_PREFIX) == true && now - it.updatedAtMillis < backoffMillis }
            .associateBy { it.dedupeKey }

        val out = LinkedHashMap<String, ThresholdStatus>()
        for (rule in rules) {
            val qty = bankNow[rule.itemCode] ?: 0
            fun status(state: ThresholdState, detail: String? = null, taskId: Long? = null) =
                ThresholdStatus(rule.itemCode, qty, state, detail, taskId, now)
            val key = key(rule.itemCode)
            val liveTask = live[key]
            out[rule.itemCode] = when {
                !settings.enabled || !rule.enabled -> status(ThresholdState.DISABLED, taskId = liveTask?.id)
                liveTask != null -> status(ThresholdState.QUEUED, "task ${liveTask.status.name.lowercase()}", liveTask.id)
                qty >= rule.minQuantity -> status(ThresholdState.OK)
                recentFailures[key] != null ->
                    status(ThresholdState.BACKOFF, "last task failed: ${recentFailures[key]?.lastError ?: "unknown"}")
                else -> when (val plan = plan(rule)) {
                    is Plan.Blocked -> status(ThresholdState.BLOCKED, plan.reason)
                    is Plan.Ready -> {
                        val task = queue.enqueue(
                            NewTask(
                                type = plan.spec.typeName, spec = plan.spec.toJson(), source = TaskSource.SYSTEM,
                                priority = rule.priority ?: settings.defaultPriority,
                                requirements = TaskRequirements(
                                    minSkillLevels = plan.skillLevels,
                                    allowedCharacters = rule.allowedCharacters.toSet(),
                                ),
                                stopCondition = plan.stop,
                                dedupeKey = key,
                            )
                        )
                        if (task != null) log("Bank threshold: ${rule.itemCode} at $qty < ${rule.minQuantity} -> queued ${plan.summary} (task ${task.id})")
                        status(ThresholdState.QUEUED, plan.summary, task?.id)
                    }
                }
            }
        }
        statuses = out
        return out
    }

    private sealed class Plan {
        data class Ready(val spec: TaskSpec, val skillLevels: Map<String, Int>, val stop: StopCondition?, val summary: String) : Plan()
        data class Blocked(val reason: String) : Plan()
    }

    private suspend fun plan(rule: BankThreshold): Plan = when (rule.action) {
        ThresholdAction.CRAFT -> {
            val item = itemOf(rule.itemCode)
            val craft = item?.craft
            val skill = craft?.skill
            if (item == null || craft == null || skill == null) Plan.Blocked("${rule.itemCode} is not craftable")
            else {
                val crafts = minOf(rule.batch, ThresholdPlanner.craftableFromBank(item, bank()))
                if (crafts <= 0) Plan.Blocked("not enough ingredients in the bank")
                else Plan.Ready(
                    TaskSpec.Craft(skill, item.code, item.name, CraftMode.BANK, crafts),
                    mapOf(skill to (craft.level ?: 1)), stop = null, summary = "craft ${crafts}x ${item.code}",
                )
            }
        }
        ThresholdAction.GATHER -> {
            val resource = ThresholdPlanner.pickResource(resourcesDropping(rule.itemCode), rule.resourceCode)
            if (resource == null) Plan.Blocked("no resource drops ${rule.itemCode}")
            else Plan.Ready(
                TaskSpec.Gather(resource.skill, resource.code, resource.name),
                mapOf(resource.skill to resource.level), StopCondition.Count(rule.batch.coerceAtLeast(1)),
                summary = "gather ${resource.code} x${rule.batch}",
            )
        }
    }

    companion object {
        const val KEY_PREFIX = "threshold:"
        fun key(itemCode: String) = "$KEY_PREFIX$itemCode"
        private val LIVE = setOf(TaskStatus.PENDING, TaskStatus.CLAIMED, TaskStatus.RUNNING, TaskStatus.SUSPENDED)
    }
}
