package com.artifactsmmo.client.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@OptIn(kotlin.time.ExperimentalTime::class)
@Serializable
data class Raid(
    val code: String,
    val name: String,
    val description: String,
    val monster: String,
    val schedule: RaidSchedule,
    val status: String,
    @SerialName("next_start_at") val nextStartAt: Instant? = null,
    @SerialName("participant_count") val participantCount: Int = 0,
    @SerialName("active_instance") val activeInstance: RaidInstance? = null,
    @SerialName("latest_instance") val latestInstance: RaidInstance? = null
)

@Serializable
data class RaidSchedule(
    val weekdays: List<String> = emptyList(),
    @SerialName("start_hour_utc") val startHourUtc: Int,
    @SerialName("start_minute_utc") val startMinuteUtc: Int,
    @SerialName("duration_hours") val durationHours: Int
)

@OptIn(kotlin.time.ExperimentalTime::class)
@Serializable
data class RaidInstance(
    @SerialName("starts_at") val startsAt: Instant,
    @SerialName("ends_at") val endsAt: Instant,
    val status: String,
    @SerialName("total_hp") val totalHp: Int,
    @SerialName("remaining_hp") val remainingHp: Int,
    @SerialName("participant_count") val participantCount: Int,
    @SerialName("ended_at") val endedAt: Instant? = null,
    val result: String? = null
)
