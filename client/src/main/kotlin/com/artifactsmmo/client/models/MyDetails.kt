package com.artifactsmmo.client.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MyDetails(
    val username: String,
    val email: String? = null,
    val status: String? = null,
    @SerialName("subscription_end") val subscriptionEnd: String? = null,
    val gems: Int = 0,
    val uid: String? = null,
    @SerialName("achievements_points") val achievementsPoints: Int = 0,
    @SerialName("created_at") val createdAt: String? = null
)

/**
 * A single achievement entry returned by GET /accounts/{username}/achievements.
 * Only [code] and [completedAt] are needed for access-condition checks.
 */
@Serializable
data class Achievement(
    val code: String,
    /** Non-null when the achievement has been completed. */
    @SerialName("completed_at") val completedAt: String? = null
) {
    val isCompleted: Boolean get() = completedAt != null
}
