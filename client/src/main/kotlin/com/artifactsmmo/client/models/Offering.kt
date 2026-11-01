package com.artifactsmmo.client.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Season 9 Seasonal Altar: one combat + two skill categories rotate daily at 00:00 UTC. */
@Serializable
data class SeasonalOffering(
    /** e.g. `20261025_combat_41_50`. */
    val id: String,
    /** `combat` or a skill name. */
    val category: String,
    /** `monster`, `resource` or `craft`. */
    val type: String = "",
    @SerialName("level_min") val levelMin: Int = 0,
    @SerialName("level_max") val levelMax: Int = 0,
    val requirement: OfferingRequirement,
    val favor: OfferingFavor,
)

@Serializable
data class OfferingRequirement(val code: String, val quantity: Int)

@Serializable
data class OfferingFavor(
    @SerialName("target_type") val targetType: String,
    @SerialName("target_code") val targetCode: String,
    @SerialName("xp_multiplier") val xpMultiplier: Double = 1.0,
    @SerialName("drop_multiplier") val dropMultiplier: Double = 1.0,
)

/** `GET /seasonal_offerings`. */
@Serializable
data class DailySeasonalOfferings(
    /** UTC date of the rotation, `yyyy-MM-dd`. */
    val date: String,
    @SerialName("expires_at") val expiresAt: String,
    val skills: List<String> = emptyList(),
    @SerialName("xp_reward_percent") val xpRewardPercent: Int = 25,
    val offerings: List<SeasonalOffering> = emptyList(),
)

/** An offering this character accepted (or completed) today. */
@Serializable
data class SelectedOffering(
    @SerialName("offering_id") val offeringId: String,
    val category: String,
    /** `accepted` or `completed`. */
    val status: String,
    @SerialName("accepted_at") val acceptedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("reward_xp") val rewardXp: Int = 0,
    val requirement: OfferingRequirement,
    val favor: OfferingFavor,
    @SerialName("expires_at") val expiresAt: String? = null,
) {
    val isCompleted: Boolean get() = status == "completed"
}

/** `GET /my/{name}/offerings`. */
@Serializable
data class CharacterSeasonalOfferings(
    val date: String,
    @SerialName("expires_at") val expiresAt: String,
    val skills: List<String> = emptyList(),
    @SerialName("xp_reward_percent") val xpRewardPercent: Int = 25,
    val offerings: List<SeasonalOffering> = emptyList(),
    @SerialName("selected_offerings") val selectedOfferings: List<SelectedOffering> = emptyList(),
    @SerialName("completed_categories") val completedCategories: List<String> = emptyList(),
    @SerialName("active_favors") val activeFavors: List<OfferingFavor> = emptyList(),
) {
    /** Categories offered today: `combat` plus the two rotating skills. */
    val categories: List<String> get() = offerings.map { it.category }.distinct()
}

@Serializable
data class OfferingRequest(@SerialName("offering_id") val offeringId: String)

@Serializable
data class OfferingReward(val category: String, val xp: Int)

/** Response for `POST /my/{name}/action/offering/accept`. */
@Serializable
data class AcceptOfferingData(
    val cooldown: Cooldown,
    val offering: SeasonalOffering,
    @SerialName("favor_expires_at") val favorExpiresAt: String? = null,
    val character: Character,
)

/** Response for `POST /my/{name}/action/offering`. */
@Serializable
data class CompleteOfferingData(
    val cooldown: Cooldown,
    val offering: SeasonalOffering,
    val reward: OfferingReward,
    @SerialName("favor_expires_at") val favorExpiresAt: String? = null,
    val character: Character,
)
