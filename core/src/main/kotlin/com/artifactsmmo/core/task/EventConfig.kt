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
    val designatedTrader: String? = null
)
