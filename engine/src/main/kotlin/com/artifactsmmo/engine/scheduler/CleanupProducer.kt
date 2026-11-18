package com.artifactsmmo.engine.scheduler

import com.artifactsmmo.client.models.Item
import com.artifactsmmo.core.task.CleanupHolder
import com.artifactsmmo.core.task.CleanupReport
import com.artifactsmmo.core.task.GearCleanup
import com.artifactsmmo.core.task.KnownLoadout
import com.artifactsmmo.core.task.ResourceCleanup
import com.artifactsmmo.core.task.ResourceContent
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

/**
 * Bank cleanup: recomputes the [GearCleanup] report (kept in [last] for the API) and, when
 * `autoRecycle` is on, queues an unassigned `bank_recycle` for each recyclable surplus item.
 */
class CleanupProducer(
    private val queue: TaskQueue,
    private val configs: TypedConfigs,
    /** Live characters (one API call). */
    private val holders: suspend () -> List<CleanupHolder>,
    /** Every item of the given types. */
    private val items: suspend (List<String>) -> List<Item>,
    private val bank: () -> Map<String, Int>,
    /** Bank minus other tasks' reservations. */
    private val bankAvailable: () -> Map<String, Int> = bank,
    private val loadouts: () -> List<KnownLoadout> = { emptyList() },
    /** All items, Task Master targets and NPC trades for the resource report (null = skip it). */
    private val resourceContent: suspend () -> ResourceContent? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = 10 * 60_000,
    private val log: (String) -> Unit = {},
) {
    @Volatile var last: CleanupReport? = null
        private set

    fun start(scope: CoroutineScope, initialDelayMillis: Long = 90_000): Job = scope.launch {
        delay(initialDelayMillis)
        while (isActive) {
            try {
                if (configs.cleanup().autoRecycle) tick()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { log("CleanupProducer: ${e.message}") }
            delay(pollMillis)
        }
    }

    suspend fun tick(): CleanupReport {
        val config = configs.cleanup().sanitized()
        val now = clock()
        val chars = holders()
        val gear = items(config.types)
        val protected = mutableMapOf<String, String>()
        if (config.protectLoadouts) {
            val levels = chars.associate { it.name to it.level }
            loadouts().filter { levels[it.characterName] == it.exactLevel }.forEach { l ->
                l.gear.values.forEach { protected.putIfAbsent(it, "loadout (${l.characterName} vs ${l.monsterCode})") }
            }
        }
        val thresholdReasons = runCatching { configs.thresholds() }.getOrDefault(emptyList())
            .associate { it.itemCode to "restock threshold" }
        thresholdReasons.forEach { (code, why) -> protected.putIfAbsent(code, why) }
        val report = GearCleanup.analyze(gear, chars, bank(), config, protected, bankAvailable(), now).let { r ->
            val content = runCatching { resourceContent() }
                .onFailure { if (it is CancellationException) throw it; log("Cleanup: resource content unavailable: ${it.message}") }
                .getOrNull()
            if (content == null) r else r.copy(resources = ResourceCleanup.analyze(content, chars, bank(), config, thresholdReasons))
        }
        last = report
        if (!config.autoRecycle) return report

        for (row in report.rows) {
            val skill = row.craftSkill ?: continue
            if (row.recyclable <= 0) continue
            val spec = TaskSpec.BankRecycle(row.code, row.recyclable, skill, row.name)
            val task = queue.enqueue(
                NewTask(
                    type = spec.typeName, spec = spec.toJson(), source = TaskSource.SYSTEM,
                    priority = config.recyclePriority, dedupeKey = recycleKey(row.code),
                    expiresAtMillis = now + RECYCLE_TTL_MILLIS,
                )
            )
            if (task != null) log("Cleanup recycle queued: ${row.recyclable}x ${row.code} (task ${task.id})")
        }
        return report
    }

    companion object {
        const val RECYCLE_TTL_MILLIS = 6 * 60 * 60_000L
        fun recycleKey(code: String) = "cleanup-recycle:$code"
    }
}
