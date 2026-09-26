package com.artifactsmmo.server.api

import com.artifactsmmo.domain.queue.TaskRequirements
import com.artifactsmmo.domain.task.CraftMode
import com.artifactsmmo.domain.task.TaskSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SpecRequirementsTest {
    private val resources = mapOf("iron_rocks" to 10, "copper_rocks" to 1)
    private val items = mapOf("steel_pickaxe" to 20)

    private suspend fun derive(spec: TaskSpec, base: TaskRequirements = TaskRequirements()) =
        SpecRequirements.withSkillMinimums(spec, base, { resources[it] }, { items[it] })

    @Test fun gatherRequiresResourceLevel() = runTest {
        assertEquals(mapOf("mining" to 10), derive(TaskSpec.Gather("mining", "iron_rocks")).minSkillLevels)
    }

    @Test fun craftRequiresRecipeLevel() = runTest {
        assertEquals(mapOf("weaponcrafting" to 20),
            derive(TaskSpec.Craft("weaponcrafting", "steel_pickaxe", mode = CraftMode.BANK)).minSkillLevels)
    }

    @Test fun levelOneAndUnknownAddNothing() = runTest {
        assertEquals(emptyMap(), derive(TaskSpec.Gather("mining", "copper_rocks")).minSkillLevels)
        assertEquals(emptyMap(), derive(TaskSpec.Gather("mining", "nope")).minSkillLevels)
        assertEquals(emptyMap(), derive(TaskSpec.Fight("chicken")).minSkillLevels)
    }

    @Test fun keepsHigherExistingMinimum() = runTest {
        val base = TaskRequirements(minSkillLevels = mapOf("mining" to 25, "combat" to 5))
        assertEquals(mapOf("mining" to 25, "combat" to 5), derive(TaskSpec.Gather("mining", "iron_rocks"), base).minSkillLevels)
    }
}
