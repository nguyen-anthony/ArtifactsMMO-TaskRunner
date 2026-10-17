package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.domain.queue.TaskSource
import kotlinx.serialization.Serializable

/**
 * Per-event configuration controlling whether and how the event dispatcher
 * assigns tasks to characters when this event spawns.
 */
@Serializable
data class EventConfig(
    val eventCode: String,
    val enabled: Boolean = false,
    /** Characters eligible to respond to this event. Empty = all qualifying characters. */
    val eligibleCharacters: List<String> = emptyList(),
    /** Items to sell to the NPC when this is an NPC event. `quantity` is a "sell down to" floor: keep this many, sell the rest (0 = sell all). */
    val itemsToSell: List<SimpleItem> = emptyList(),
    /** Items to buy from the NPC when this is an NPC event (`quantity` per event, bought in inventory-sized trips). */
    val itemsToBuy: List<SimpleItem> = emptyList(),
    /** For NPC events: the specific character that should handle trading. Null = skip NPC dispatch. */
    val designatedTrader: String? = null,
    /** Minimum simulation win rate (0.0–1.0) required before dispatching to a monster event. Applies to every boss party. */
    val minWinRate: Double = 0.90,
    /**
     * Parties for events whose monster is a boss (e.g. out_of_the_ground → duskworm). Each
     * party is co-op optimized and fights as its own group, in list order (earlier parties
     * get first pick of shared bank stock). Without any party, boss events are skipped.
     */
    val groups: List<EventGroupConfig> = emptyList(),
    /** Legacy single-party form of [groups]; still read when [groups] is empty. */
    val group: EventGroupConfig? = null,
    /** Queue priority for this event's tasks (higher runs first and can preempt). Null = [TaskSource.EVENT] default. */
    val priority: Int? = null,
    /** Per-character overrides of [priority] for solo tasks (gather, fight, NPC trading). */
    val characterPriorities: Map<String, Int> = emptyMap(),
) {
    /** Configured boss parties: [groups], or the legacy single [group]. */
    val parties: List<EventGroupConfig> get() = groups.ifEmpty { listOfNotNull(group) }

    /** Priority for [character]'s solo task. */
    fun priorityFor(character: String): Int = characterPriorities[character] ?: priority ?: TaskSource.EVENT.defaultPriority

    /** Priority for a boss party: its own, else the event's, else the EVENT default. */
    fun priorityFor(party: EventGroupConfig): Int = party.priority ?: priority ?: TaskSource.EVENT.defaultPriority
}

/** A co-op boss party (events and raids): the initiator fires the fight with up to two participants. */
@Serializable
data class EventGroupConfig(
    val initiatorName: String,
    val participantNames: List<String> = emptyList(),
    /** Null lets [CoopOptimizer] choose the tank. */
    val tankOverride: String? = null,
    /** Queue priority for every member's slot. Null = the event's priority, or the EVENT/RAID default. */
    val priority: Int? = null,
) {
    /** Initiator first, de-duplicated, capped at the fight API's 3 characters. */
    val members: List<String> get() = (listOf(initiatorName) + participantNames.filter { it != initiatorName }).distinct().take(3)
}

/**
 * Parties in order, dropping any that reuse a character from an earlier party (a character
 * can only be in one fight) or have a blank initiator. [onDropped] receives a reason.
 */
fun disjointParties(parties: List<EventGroupConfig>, onDropped: (String) -> Unit = {}): List<EventGroupConfig> {
    val used = mutableSetOf<String>()
    return parties.filter { party ->
        val members = party.members
        when {
            party.initiatorName.isBlank() -> { onDropped("party with no initiator"); false }
            members.any { it in used } -> {
                onDropped("party ${members.joinToString()} reuses ${members.filter { it in used }.joinToString()} from an earlier party")
                false
            }
            else -> { used += members; true }
        }
    }
}
