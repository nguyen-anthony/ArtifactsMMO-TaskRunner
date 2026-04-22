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
