package com.artifactsmmo.server.api

import com.artifactsmmo.domain.queue.TaskRequirements
import com.artifactsmmo.domain.task.TaskSpec

/**
 * Adds the skill levels the game itself requires for a task to its queue requirements, so
 * no character below them claims it (it waits instead). Only hard game rules: a resource's
 * gathering level and a recipe's crafting level. Fights have no game-enforced level.
 * Existing (higher) minimums in [base] are kept.
 */
object SpecRequirements {
    suspend fun withSkillMinimums(
        spec: TaskSpec,
        base: TaskRequirements,
        resourceLevel: suspend (String) -> Int?,
        itemLevel: suspend (String) -> Int?,
    ): TaskRequirements {
        val (skill, level) = when (spec) {
            is TaskSpec.Gather -> spec.skill to resourceLevel(spec.resourceCode)
            is TaskSpec.Craft -> spec.skill to itemLevel(spec.itemCode)
            else -> return base
        }
        if (level == null || level <= 1) return base
        val merged = base.minSkillLevels + (skill to maxOf(level, base.minSkillLevels[skill] ?: 0))
        return base.copy(minSkillLevels = merged)
    }
}
