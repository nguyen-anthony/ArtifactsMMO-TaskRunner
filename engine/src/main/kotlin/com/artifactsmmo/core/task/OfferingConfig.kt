package com.artifactsmmo.core.task

import kotlinx.serialization.Serializable

/**
 * Per-character Seasonal Altar daily offerings (config kind `offering`, keyed by character).
 * A character without a document uses the defaults: enabled, all of today's categories.
 */
@Serializable
data class OfferingConfig(
    val character: String,
    val enabled: Boolean = true,
    /** Categories to do ("combat", "mining", ...); empty = all of today's. */
    val categories: List<String> = emptyList(),
    val priority: Int = DEFAULT_PRIORITY,
) {
    companion object {
        /** Below events (80) and raids (90), above manual work (50). */
        const val DEFAULT_PRIORITY = 70
    }
}
