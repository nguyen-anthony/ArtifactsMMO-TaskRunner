package com.artifactsmmo.client.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class ApiResponse<T>(
    val data: T
)

@Serializable
data class DataPage<T>(
    val data: List<T>,
    val total: Int? = null,
    val page: Int? = null,
    val size: Int? = null,
    val pages: Int? = null
)

@Serializable
data class ErrorResponse(
    val error: ErrorDetail
)

@Serializable
data class ErrorDetail(
    val code: Int,
    val message: String,
    val data: Map<String, List<String>>? = null
)

@OptIn(kotlin.time.ExperimentalTime::class)
@Serializable
data class Cooldown(
    @SerialName("total_seconds") val totalSeconds: Int,
    @SerialName("remaining_seconds") val remainingSeconds: Int,
    @SerialName("started_at") val startedAt: Instant,
    val expiration: Instant,
    val reason: String
)

@Serializable
data class SimpleItem(
    val code: String,
    val quantity: Int
)

@Serializable
data class Drop(
    val code: String,
    val quantity: Int
)

@Serializable
data class DropRate(
    val code: String,
    val rate: Int,
    @SerialName("min_quantity") val minQuantity: Int,
    @SerialName("max_quantity") val maxQuantity: Int
)

@Serializable
data class Condition(
    val code: String,
    val operator: String,
    val value: Int
)

@Serializable
data class SimpleEffect(
    val code: String,
    val value: Int,
    val description: String
)

@Serializable
data class StorageEffect(
    val code: String,
    val value: Int
)

