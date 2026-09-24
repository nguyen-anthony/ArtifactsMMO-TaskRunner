package com.artifactsmmo.server.api

import com.artifactsmmo.domain.queue.GroupSlot
import com.artifactsmmo.domain.queue.StopCondition
import com.artifactsmmo.domain.queue.TaskRequirements
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.Engine
import com.artifactsmmo.engine.config.ConfigStore
import com.artifactsmmo.engine.queue.TaskQueue
import com.artifactsmmo.engine.worker.CharacterSettingsStore
import com.artifactsmmo.engine.worker.WorkerStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the HTTP layer needs from the backend (an interface so tests can stub it). */
interface ApiBackend {
    val queue: TaskQueue
    val settings: CharacterSettingsStore
    val configStore: ConfigStore
    /** Null until this process holds the instance lock and the engine has booted. */
    val engine: Engine?
}

/**
 * JSON used by the API. Sealed types (TaskSpec, StopCondition) carry their subtype in a
 * "kind" field, e.g. {"kind":"gather","skill":"mining",...}.
 */
val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "kind"
}

@Serializable data class LoginRequest(val key: String)
@Serializable data class MeResponse(val loggedIn: Boolean, val engineRunning: Boolean, val paused: String? = null)

@Serializable
data class CreateTaskRequest(
    val spec: TaskSpec,
    /** Defaults to the MANUAL priority (50). Higher runs first and can preempt. */
    val priority: Int? = null,
    val assignedCharacter: String? = null,
    val requirements: TaskRequirements = TaskRequirements(),
    val stopCondition: StopCondition? = null,
    val notBeforeMillis: Long? = null,
    val expiresAtMillis: Long? = null,
    val dedupeKey: String? = null,
)

/** Boss/raid group: the first slot is the initiator. */
@Serializable
data class CreateGroupRequest(
    val spec: TaskSpec.BossFight,
    val slots: List<GroupSlot>,
    val priority: Int? = null,
    val stopCondition: StopCondition? = null,
    val expiresAtMillis: Long? = null,
)

@Serializable data class CancelRequest(val reason: String = "cancelled by user")

@Serializable
data class CharacterSettingsDto(
    val enabled: Boolean = true,
    val allowedTypes: Set<String>? = null,
    /** Default task when nothing else matches; null clears it. */
    val filler: TaskSpec? = null,
)

@Serializable
data class CharacterDto(val name: String, val status: WorkerStatus?, val settings: CharacterSettingsDto)

@Serializable data class RateWindowDto(val limit: Int, val windowMillis: Long, val used: Int)
@Serializable data class RatesDto(val buckets: Map<String, List<RateWindowDto>>)

@Serializable data class BankItemDto(val code: String, val quantity: Int)

@Serializable
data class LogDto(val timestampMillis: Long, val character: String?, val message: String)

/** Payloads pushed over /api/stream (the SSE `event:` name is the class's role). */
@Serializable data class TaskChangedDto(val id: Long)
@Serializable data class ControlDto(val paused: String?)
