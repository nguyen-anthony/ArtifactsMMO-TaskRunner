package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.AccountLogEntry
import com.artifactsmmo.client.models.SimpleItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.minutes

/**
 * Shared, periodically-refreshed view of the bank contents.
 *
 * Primary update path: WebSocket account_log messages (deposit_item / withdraw_item)
 * are applied as immediate deltas via [applyLogEntry]. This keeps the snapshot
 * current between full syncs with zero extra API calls.
 *
 * The 5-minute full sync via [refresh] serves as reconciliation — it corrects any
 * drift from missed or malformed WebSocket messages and logs discrepancies.
 *
 * ActionHelper no longer calls invalidate() after deposits/withdrawals; the
 * WebSocket delta for that same action arrives within the action cooldown window
 * and updates the snapshot before the next executor step reads it.
 */
class BankState(
    private val client: ArtifactsMMOClient,
    private val scope: CoroutineScope,
    private val logger: TaskLogger? = null,
    val reservations: BankReservations = BankReservations(),
) : BankReserver {
    private val _snapshot = MutableStateFlow<Map<String, Int>>(emptyMap())
    val snapshot: StateFlow<Map<String, Int>> = _snapshot.asStateFlow()

    /**
     * What planning for [characters] may use: the bank minus items other characters' planned
     * work has reserved (see [BankReservations]). Planners should read this, not [snapshot].
     */
    fun availableFor(characters: Set<String>): Map<String, Int> = reservations.available(characters, _snapshot.value)
    fun availableFor(character: String): Map<String, Int> = availableFor(setOf(character))

    override fun reserve(owner: String, characters: Set<String>, items: Map<String, Int>): Map<String, Int> {
        val granted = reservations.reserve(owner, characters, items, _snapshot.value)
        if (granted != items.filterValues { it > 0 }) {
            logger?.log("BankState: $owner reserved $granted of requested $items for ${characters.joinToString()}")
        }
        return granted
    }

    override fun release(owner: String) {
        reservations.release(owner)
    }

    private val REFRESH_INTERVAL = 5.minutes
    private var refreshJob: Job? = null

    /** Start the periodic refresh loop. */
    fun start() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (isActive) {
                refresh()
                delay(REFRESH_INTERVAL)
            }
        }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * Fetch all bank pages and update [snapshot].
     * Logs discrepancies between the current snapshot and fresh data — surfaces any
     * delta-parsing bugs without impacting correctness (the full sync always wins).
     * Never throws — a failed refresh leaves the previous snapshot intact.
     */
    suspend fun refresh() {
        try {
            val fresh = mutableMapOf<String, Int>()
            var page = 1
            while (true) {
                val result = client.bank.getBankItems(page = page, size = 100)
                for (item in result.data) {
                    fresh[item.code] = (fresh[item.code] ?: 0) + item.quantity
                }
                if (page >= (result.pages ?: Int.MAX_VALUE)) break
                if (result.data.size < 100) break
                page++
            }
            val current = _snapshot.value
            val discrepancies = (fresh.keys + current.keys).filter { fresh[it] != current[it] }
            if (discrepancies.isNotEmpty()) {
                logger?.log("BankState: sync corrected ${discrepancies.size} item(s): $discrepancies")
            }
            _snapshot.value = fresh
        } catch (_: Exception) {
            // Leave previous snapshot intact on failure
        }
    }

    /**
     * Apply a bank-relevant delta from an account_log WebSocket entry.
     * deposit_item → increase quantities; withdraw_item → decrease quantities.
     * Atomic via [MutableStateFlow.update]. No-op for irrelevant or unparseable entries.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    fun applyLogEntry(entry: AccountLogEntry) {
        val delta = extractBankDelta(entry) ?: return
        if (entry.type == "withdraw_item") {
            reservations.consume(entry.character, delta.mapValues { -it.value }.filterValues { it > 0 })
        }
        _snapshot.update { current ->
            val mutable = current.toMutableMap()
            for ((code, qty) in delta) {
                val newQty = (mutable[code] ?: 0) + qty
                if (newQty <= 0) mutable.remove(code) else mutable[code] = newQty
            }
            mutable
        }
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun extractBankDelta(entry: AccountLogEntry): Map<String, Int>? {
        return when (entry.type) {
            "deposit_item"  -> parseContent(entry.content, sign = +1)
            "withdraw_item" -> parseContent(entry.content, sign = -1)
            else -> null
        }
    }

    /**
     * Parse the content JsonElement of a deposit_item or withdraw_item log entry.
     * Confirmed shape: {"items":[{"code":"...","quantity":N}], "character":{...}}
     * Returns null on parse failure — next full sync corrects silently.
     */
    private fun parseContent(
        content: kotlinx.serialization.json.JsonElement,
        sign: Int
    ): Map<String, Int>? {
        return try {
            val itemsArray = content.jsonObject["items"]
                ?.let { it as? kotlinx.serialization.json.JsonArray }
                ?: return null
            val result = mutableMapOf<String, Int>()
            for (element in itemsArray) {
                val obj  = element.jsonObject
                val code = obj["code"]?.jsonPrimitive?.content ?: continue
                val qty  = obj["quantity"]?.jsonPrimitive?.intOrNull ?: continue
                result[code] = (result[code] ?: 0) + qty * sign
            }
            result.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** Convenience read — returns 0 if the item is not in the bank. */
    fun getQuantity(itemCode: String): Int = _snapshot.value[itemCode] ?: 0
    /** Return the snapshot as a [List<SimpleItem>] for callers that need that shape. */
    fun getItems(): List<SimpleItem> = _snapshot.value.map { (code, qty) -> SimpleItem(code, qty) }
}
