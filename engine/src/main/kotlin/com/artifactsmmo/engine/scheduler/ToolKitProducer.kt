package com.artifactsmmo.engine.scheduler

import com.artifactsmmo.client.models.Item
import com.artifactsmmo.core.task.ToolAllocator
import com.artifactsmmo.core.task.ToolHolder
import com.artifactsmmo.core.task.ToolKitPlan
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.TaskRequirements
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
 * Keeps every character's tool kit in line with the `toolkit` config: recomputes the
 * [ToolAllocator] plan (publishing it via [onPlan] for enforcement), queues a `toolkit_sync`
 * for each character whose kit can change right now, and an unassigned `bank_recycle` for
 * surplus tools sitting in the bank.
 */
class ToolKitProducer(
    private val queue: TaskQueue,
    private val configs: TypedConfigs,
    /** Live characters in roster order (one API call). */
    private val holders: suspend () -> List<ToolHolder>,
    /** Every tool item in the game. */
    private val tools: suspend () -> List<Item>,
    private val bank: () -> Map<String, Int>,
    private val onPlan: (ToolKitPlan?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMillis: Long = 120_000,
    private val log: (String) -> Unit = {},
) {
    fun start(scope: CoroutineScope, initialDelayMillis: Long = 60_000): Job = scope.launch {
        // Let the bank snapshot load first: an empty pool would hand out the wrong tools.
        delay(initialDelayMillis)
        while (isActive) {
            try { tick() } catch (e: CancellationException) { throw e } catch (e: Exception) { log("ToolKitProducer: ${e.message}") }
            delay(pollMillis)
        }
    }

    suspend fun tick(): ToolKitPlan? {
        val config = configs.toolKit()
        if (!config.enabled) {
            onPlan(null)
            return null
        }
        val now = clock()
        val chars = holders()
        val items = tools()
        val bankNow = bank()
        val plan = ToolAllocator.allocate(items, config, chars, bankNow, nowMillis = now)
        onPlan(plan)

        for (h in chars) {
            val actions = plan.actions[h.name] ?: continue
            if (!actions.actionable(bankNow)) continue
            val spec = TaskSpec.ToolKitSync
            val task = queue.enqueue(
                NewTask(
                    type = spec.typeName, spec = spec.toJson(), source = TaskSource.SYSTEM,
                    priority = config.syncPriority, assignedCharacter = h.name,
                    dedupeKey = syncKey(h.name), expiresAtMillis = now + SYNC_TTL_MILLIS,
                )
            )
            if (task != null) log("Tool kit sync queued for ${h.name} (task ${task.id}): $actions")
        }

        if (!config.recycleSurplus) return plan
        val byCode = items.associateBy { it.code }
        for ((code, surplus) in plan.surplus) {
            val qty = minOf(surplus, bankNow[code] ?: 0)
            val item = byCode[code] ?: continue
            val craftSkill = item.craft?.skill ?: continue
            if (qty <= 0) continue
            // Keep the recycle away from characters who carry this tool as their own kit tool
            // (the recycle step would consume the inventory copy first).
            val skill = plan.assignments.values.firstNotNullOfOrNull { a -> a.entries.firstOrNull { it.value == code }?.key }
            val allowed = chars.map { it.name }.filter { n -> skill == null || plan.assignedTool(n, skill) != code }.toSet()
            val spec = TaskSpec.BankRecycle(code, qty, craftSkill, item.name)
            val task = queue.enqueue(
                NewTask(
                    type = spec.typeName, spec = spec.toJson(), source = TaskSource.SYSTEM,
                    priority = config.recyclePriority,
                    requirements = TaskRequirements(allowedCharacters = if (allowed.size == chars.size) emptySet() else allowed),
                    dedupeKey = recycleKey(code), expiresAtMillis = now + RECYCLE_TTL_MILLIS,
                )
            )
            if (task != null) log("Surplus tool recycle queued: ${qty}x $code (task ${task.id})")
        }
        return plan
    }

    companion object {
        const val SYNC_TTL_MILLIS = 30 * 60_000L
        const val RECYCLE_TTL_MILLIS = 6 * 60 * 60_000L
        fun syncKey(character: String) = "toolkit:$character"
        fun recycleKey(code: String) = "toolkit-recycle:$code"
    }
}
