package com.artifactsmmo.domain.queue

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Lifecycle of a row in the `tasks` table.
 *
 * pending ──claim──▶ claimed ──start──▶ running ──▶ completed | failed
 *    ▲                                      │
 *    └──────────── resume ◀── suspended ◀───┘ (preempted by higher priority)
 *
 * cancelled can be reached from any non-terminal state.
 */
@Serializable
enum class TaskStatus {
    @SerialName("pending") PENDING,
    @SerialName("claimed") CLAIMED,
    @SerialName("running") RUNNING,
    @SerialName("suspended") SUSPENDED,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("cancelled") CANCELLED;

    val dbValue: String get() = name.lowercase()
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED

    companion object {
        fun fromDb(value: String): TaskStatus = valueOf(value.uppercase())
    }
}

/** Who created the task. Also drives the default priority. */
@Serializable
enum class TaskSource(val defaultPriority: Int) {
    @SerialName("raid") RAID(90),
    @SerialName("event") EVENT(80),
    @SerialName("manual") MANUAL(50),
    @SerialName("schedule") SCHEDULE(30),
    /** Per-character default/filler task; runs only when nothing else matches. */
    @SerialName("filler") FILLER(10),
    @SerialName("system") SYSTEM(50);

    val dbValue: String get() = name.lowercase()

    companion object {
        fun fromDb(value: String): TaskSource = valueOf(value.uppercase())
    }
}

/**
 * Role of a task inside a multi-character group (boss fight / raid).
 *
 * The ArtifactsMMO fight endpoint is called by exactly one character (the initiator), with the
 * other characters named in the request body as `participants`. So a group is modelled as:
 *   - one parent row (role = GROUP) that owns the shared spec and the formed member list,
 *   - one INITIATOR slot row, and
 *   - N-1 PARTICIPANT slot rows.
 * Slots are claimed like normal tasks; once every slot is claimed the group is "formed" and the
 * initiator learns the participant names from the parent. See `GroupCoordinator` in engine.
 */
@Serializable
enum class GroupRole {
    @SerialName("solo") SOLO,
    @SerialName("group") GROUP,
    @SerialName("initiator") INITIATOR,
    @SerialName("participant") PARTICIPANT;

    val dbValue: String get() = name.lowercase()

    companion object {
        fun fromDb(value: String): GroupRole = valueOf(value.uppercase())
    }
}

/**
 * Eligibility rules for unassigned tasks. Evaluated in Kotlin against the worker's cached
 * character state (SQL only does the coarse filtering: status, not_before, assignment).
 */
@Serializable
data class TaskRequirements(
    /** Skill name -> minimum level, e.g. {"mining": 20}. "combat" means character level. */
    val minSkillLevels: Map<String, Int> = emptyMap(),
    val minFreeInventorySlots: Int = 0,
    /** If non-empty, only these characters may claim the task. */
    val allowedCharacters: Set<String> = emptySet(),
)

/** When a repeating task should be considered done. Null on the task = run until cancelled. */
@Serializable
sealed class StopCondition {
    @Serializable @SerialName("count")
    data class Count(val target: Int) : StopCondition()

    @Serializable @SerialName("skill_level")
    data class SkillLevel(val skill: String, val level: Int) : StopCondition()

    @Serializable @SerialName("until")
    data class Until(val epochMillis: Long) : StopCondition()
}

/** A row of the `tasks` table as seen by the engine/server. */
@Serializable
data class QueuedTask(
    val id: Long,
    val type: String,
    /** Serialized TaskSpec (shape depends on [type]). */
    val spec: JsonObject,
    val priority: Int,
    val source: TaskSource,
    val status: TaskStatus,
    val assignedCharacter: String? = null,
    val claimedBy: String? = null,
    val requirements: TaskRequirements = TaskRequirements(),
    val stopCondition: StopCondition? = null,
    val groupId: Long? = null,
    val groupRole: GroupRole = GroupRole.SOLO,
    val checkpoint: JsonObject? = null,
    val dedupeKey: String? = null,
    val notBeforeMillis: Long? = null,
    val expiresAtMillis: Long? = null,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
