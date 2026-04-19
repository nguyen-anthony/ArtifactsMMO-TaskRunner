package com.artifactsmmo.client.models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/**
 * Handles the ArtifactsMMO API inconsistency where `cooldown` on a Character
 * is an Int (seconds remaining) when the character is idle, but a full Cooldown
 * object when the character is actively in a cooldown. This serializer accepts
 * both forms and returns the remaining seconds as an Int in both cases.
 */
object IntOrCooldownSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("IntOrCooldown", PrimitiveKind.INT)
    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return when (val element = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> element.intOrNull ?: 0
            is JsonObject    -> element["remaining_seconds"]?.jsonPrimitive?.intOrNull ?: 0
            else             -> 0
        }
    }
}

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

