package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.SimpleItem
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
    /** Items to sell to the NPC when this is an NPC event. */
    val itemsToSell: List<SimpleItem> = emptyList(),
    /** Items to buy from the NPC when this is an NPC event. */
    val itemsToBuy: List<SimpleItem> = emptyList(),
    /** For NPC events: the specific character that should handle trading. Null = skip NPC dispatch. */
    val designatedTrader: String? = null,
    /** Minimum simulation win rate (0.0–1.0) required before dispatching to a monster event. */
    val minWinRate: Double = 0.90,
    /**
     * Team for events whose monster is a boss (e.g. out_of_the_ground → duskworm). Boss events
     * are fought as one co-op group; without this block they are skipped.
     */
    val group: EventGroupConfig? = null
)

/** A co-op boss team: the initiator fires the fight with up to two participants. */
@Serializable
data class EventGroupConfig(
    val initiatorName: String,
    val participantNames: List<String> = emptyList(),
    /** Null lets [CoopOptimizer] choose the tank. */
    val tankOverride: String? = null
) {
    /** Initiator first, de-duplicated, capped at the fight API's 3 characters. */
    val members: List<String> get() = (listOf(initiatorName) + participantNames.filter { it != initiatorName }).distinct().take(3)
}
