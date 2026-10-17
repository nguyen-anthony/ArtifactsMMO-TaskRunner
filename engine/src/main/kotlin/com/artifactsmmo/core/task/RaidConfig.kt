package com.artifactsmmo.core.task

import com.artifactsmmo.domain.queue.TaskSource
import kotlinx.serialization.Serializable

/**
 * Per-raid schedule configuration. Raids are explicitly configured because they
 * reserve fixed parties (initiator + up to two participants each) ahead of their UTC start time.
 */
@Serializable
data class RaidConfig(
    val raidCode: String,
    val enabled: Boolean = false,
    /** Parties in priority order; each fights as its own group. Earlier parties get first pick of bank stock. */
    val parties: List<EventGroupConfig> = emptyList(),
    /** Legacy single-party fields; still read when [parties] is empty. */
    val initiatorName: String? = null,
    val participantNames: List<String> = emptyList(),
    /** Null lets [CoopOptimizer] choose the tank from post-gear threat. */
    val tankOverride: String? = null,
    /** Dispatch this many minutes before the scheduled raid start. */
    val leadTimeMinutes: Int = 5,
    /** Skip a party whose simulated win rate is below this (0.0 = never skip). */
    val minWinRate: Double = 0.0,
) {
    /** Configured parties: [parties], or the legacy single-party fields. */
    val allParties: List<EventGroupConfig>
        get() = parties.ifEmpty { listOfNotNull(initiatorName?.let { EventGroupConfig(it, participantNames, tankOverride) }) }

    /** Priority for a raid party: its own, else the RAID default. */
    fun priorityFor(party: EventGroupConfig): Int = party.priority ?: TaskSource.RAID.defaultPriority
}
