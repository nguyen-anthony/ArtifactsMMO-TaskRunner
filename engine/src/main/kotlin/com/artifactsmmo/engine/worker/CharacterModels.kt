package com.artifactsmmo.engine.worker

import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.QueuedTask
import com.artifactsmmo.domain.queue.StopCondition
import com.artifactsmmo.domain.queue.TaskRequirements

/** The few character facts the worker needs to pick and stop tasks. */
data class CharacterSnapshot(
    val name: String,
    val level: Int,
    /** skill name -> level, e.g. "mining" -> 20. */
    val skills: Map<String, Int>,
    val freeInventorySlots: Int,
) {
    fun skillLevel(skill: String): Int? = if (skill == "combat") level else skills[skill]
}

/** Source of [CharacterSnapshot]s (live API in production, fakes in tests). */
fun interface CharacterView {
    suspend fun snapshot(name: String): CharacterSnapshot
}

/** Per-character settings (the `characters` table). */
data class CharacterSettings(
    val name: String,
    /** false = the worker idles and claims nothing. */
    val enabled: Boolean = true,
    /** null = any task type. */
    val allowedTypes: Set<String>? = null,
    /** Default task enqueued when nothing else matches (source = FILLER). */
    val filler: NewTask? = null,
)

interface CharacterSettingsStore {
    suspend fun get(name: String): CharacterSettings
    suspend fun put(settings: CharacterSettings)
}

/** Pure eligibility / stop-condition rules, kept separate so they're easy to test. */
object TaskRules {
    fun eligible(task: QueuedTask, char: CharacterSnapshot, settings: CharacterSettings): Boolean {
        if (settings.allowedTypes != null && task.type !in settings.allowedTypes) return false
        return meets(task.requirements, char)
    }

    fun meets(req: TaskRequirements, char: CharacterSnapshot): Boolean {
        if (req.allowedCharacters.isNotEmpty() && char.name !in req.allowedCharacters) return false
        if (char.freeInventorySlots < req.minFreeInventorySlots) return false
        return req.minSkillLevels.all { (skill, min) -> (char.skillLevel(skill) ?: 0) >= min }
    }

    /**
     * True when the task's stop condition is satisfied. "Count" counts the task's primary
     * unit of work: gathers, fight wins, items crafted, or task-master tasks completed.
     */
    fun stopReached(stop: StopCondition?, type: String, state: RunState, char: CharacterSnapshot?, nowMillis: Long): Boolean =
        when (stop) {
            null -> false
            is StopCondition.Until -> nowMillis >= stop.epochMillis
            is StopCondition.SkillLevel -> char != null && (char.skillLevel(stop.skill) ?: 0) >= stop.level
            is StopCondition.Count -> primaryCount(type, state) >= stop.target
        }

    fun primaryCount(type: String, s: RunState): Int = when (type) {
        "gather" -> s.gathers
        "fight" -> s.fightsWon
        "craft" -> s.crafted
        "task_master" -> s.tasksCompleted
        else -> 0
    }
}
