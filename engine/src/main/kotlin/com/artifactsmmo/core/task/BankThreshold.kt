package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Resource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a [BankThreshold] queues when its item runs low. */
@Serializable
enum class ThresholdAction {
    /** Craft the item from bank ingredients (as many as the bank allows, up to the batch). */
    @SerialName("craft") CRAFT,
    /** Gather the item's resource for `batch` gathers. */
    @SerialName("gather") GATHER,
}

/**
 * A restock rule (config kind `threshold`, keyed by [itemCode]): when the bank holds fewer
 * than [minQuantity], queue an unassigned task that produces more.
 */
@Serializable
data class BankThreshold(
    val itemCode: String,
    val minQuantity: Int,
    val action: ThresholdAction,
    /** Crafts (CRAFT) or gathers (GATHER) per queued task. */
    val batch: Int,
    /** Null = [ThresholdSettings.defaultPriority]. */
    val priority: Int? = null,
    val enabled: Boolean = true,
    /** GATHER only: resource to gather; null = the lowest-level resource that drops the item. */
    val resourceCode: String? = null,
    /** Empty = any character with the skill level. */
    val allowedCharacters: List<String> = emptyList(),
    /** Task Master won't take this item from the bank below [minQuantity]. */
    val reserve: Boolean = true,
)

/** Global switch and defaults for bank thresholds (config kind `threshold_settings`). */
@Serializable
data class ThresholdSettings(
    val enabled: Boolean = true,
    val defaultPriority: Int = 40,
    /** After a threshold task fails, wait this long before queueing it again. */
    val failureBackoffMinutes: Int = 30,
) {
    companion object { const val KEY = "default" }
}

@Serializable
enum class ThresholdState {
    @SerialName("ok") OK,
    @SerialName("queued") QUEUED,
    @SerialName("blocked") BLOCKED,
    @SerialName("backoff") BACKOFF,
    @SerialName("disabled") DISABLED,
}

/** Latest evaluation of one rule (shown on the Bank page). */
@Serializable
data class ThresholdStatus(
    val itemCode: String,
    val bankQuantity: Int,
    val state: ThresholdState,
    val detail: String? = null,
    /** Live (or just queued) task for this rule. */
    val taskId: Long? = null,
    val checkedAtMillis: Long,
)

object ThresholdPlanner {
    /** Crafts the bank's ingredients allow (0 when the item has no recipe). */
    fun craftableFromBank(item: Item, bank: Map<String, Int>): Int {
        val recipe = item.craft?.items.orEmpty()
        if (recipe.isEmpty()) return 0
        return recipe.minOf { (bank[it.code] ?: 0) / it.quantity.coerceAtLeast(1) }
    }

    /** The resource to gather for [itemCode]: [preferred] if it drops the item, else the lowest level. */
    fun pickResource(candidates: List<Resource>, preferred: String?): Resource? =
        candidates.firstOrNull { it.code == preferred } ?: candidates.minByOrNull { it.level }

    /** Item code -> quantity Task Master must leave in the bank. */
    fun reserves(rules: List<BankThreshold>, settings: ThresholdSettings): Map<String, Int> =
        if (!settings.enabled) emptyMap()
        else rules.filter { it.enabled && it.reserve && it.minQuantity > 0 }.associate { it.itemCode to it.minQuantity }
}
