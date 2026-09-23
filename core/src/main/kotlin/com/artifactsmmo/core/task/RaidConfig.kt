package com.artifactsmmo.core.task

import kotlinx.serialization.Serializable

/**
 * Per-raid schedule configuration. Raids are explicitly configured because they
 * reserve a fixed initiator and up to two participants ahead of their UTC start time.
 */
@Serializable
data class RaidConfig(
    val raidCode: String,
    val enabled: Boolean = false,
    val initiatorName: String? = null,
    val participantNames: List<String> = emptyList(),
    /** Null lets [CoopOptimizer] choose the tank from post-gear threat. */
    val tankOverride: String? = null,
    /** Dispatch this many minutes before the scheduled raid start. */
    val leadTimeMinutes: Int = 5
)
