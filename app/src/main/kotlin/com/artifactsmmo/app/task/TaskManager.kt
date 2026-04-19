package com.artifactsmmo.app.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Monster
import com.artifactsmmo.client.models.Resource
import com.artifactsmmo.client.utils.CharacterUtils
import kotlinx.coroutines.CoroutineScope

/**
 * Manages all character task runners.
 * Provides methods for the UI to query status and assign tasks.
 */
class TaskManager(
    private val client: ArtifactsMMOClient,
    private val scope: CoroutineScope,
    private val taskStore: TaskStore = TaskStore(),
    val logger: TaskLogger = TaskLogger()
) {
    private val helper = ActionHelper(client)
    private val gatheringExecutor = GatheringExecutor(helper)
    private val fightingExecutor = FightingExecutor(helper)
    private val craftingExecutor = CraftingExecutor(helper)
    private val taskMasterExecutor = TaskMasterExecutor(helper, gatheringExecutor, fightingExecutor)

    private val runners = mutableMapOf<String, CharacterTaskRunner>()

    /**
     * Initialize runners for all characters. Restores saved tasks if available.
     */
    suspend fun initialize(): List<String> {
        val characters = client.characters.getMyCharacters()
        for (char in characters) {
            runners[char.name] = CharacterTaskRunner(
                characterName = char.name,
                client = client,
                helper = helper,
                gatheringExecutor = gatheringExecutor,
                fightingExecutor = fightingExecutor,
                craftingExecutor = craftingExecutor,
                taskMasterExecutor = taskMasterExecutor,
                logger = logger
            )
        }

        // Restore saved tasks
        val savedAssignments = taskStore.load()
        var restored = 0
        for ((name, assignment) in savedAssignments) {
            val runner = runners[name] ?: continue
            if (assignment.task !is TaskType.Idle) {
                // Restore previous task if present (for craft tasks)
                if (assignment.previousTask != null) {
                    // First set the previous task by assigning a temporary idle,
                    // then re-assign the craft task which will set previousTask.
                    // Actually, we need to set previousTask directly.
                    // Assign the previous task first so it becomes current,
                    // then assign the craft task which saves current as previous.
                    runner.assignTask(assignment.previousTask, scope)
                    // Small delay not needed since assignTask is synchronous in launching
                    runner.assignTask(assignment.task, scope)
                } else {
                    runner.assignTask(assignment.task, scope)
                }
                restored++
            }
        }
        if (restored > 0) {
            logger.log("Restored $restored saved task assignment(s).")
        }

        return characters.map { it.name }
    }

    /**
     * Get all runner statuses.
     */
    fun getAllStatuses(): List<RunnerStatus> {
        return runners.values.map { it.status.value }
    }

    /**
     * Get a specific runner's status.
     */
    fun getStatus(characterName: String): RunnerStatus? {
        return runners[characterName]?.status?.value
    }

    /**
     * Refresh character data from the API.
     */
    suspend fun getCharacterDetails(characterName: String): Character {
        return client.characters.getCharacter(characterName)
    }

    /**
     * Get available resources for a gathering skill based on character's skill level.
     */
    suspend fun getAvailableResources(characterName: String, skill: String): List<Resource> {
        val char = client.characters.getCharacter(characterName)
        val skillLevel = CharacterUtils.getSkillLevel(char, skill) ?: 0
        return helper.getAvailableResources(skill, skillLevel)
    }

    /**
     * Get available monsters based on character's combat level.
     */
    suspend fun getAvailableMonsters(characterName: String): List<Monster> {
        val char = client.characters.getCharacter(characterName)
        return helper.getAvailableMonsters(char.level)
    }

    /**
     * Simulate combat between a character and a monster.
     */
    suspend fun simulateFight(
        characterName: String,
        monsterCode: String,
        iterations: Int = 20
    ): com.artifactsmmo.client.models.CombatSimulationData {
        return helper.simulateFight(characterName, monsterCode, iterations)
    }

    /**
     * Get available items that can be crafted with a specific skill from inventory + bank.
     */
    suspend fun getAvailableCraftingItems(characterName: String, skill: String): List<ActionHelper.CraftableItemInfo> {
        val char = client.characters.getCharacter(characterName)
        return helper.getAvailableCraftingItems(char, skill)
    }

    /**
     * Get available misc (non-weapon/gear/jewelry) craftable items from inventory + bank.
     */
    suspend fun getAvailableMiscCraftingItems(characterName: String): List<ActionHelper.CraftableItemInfo> {
        val char = client.characters.getCharacter(characterName)
        return helper.getAvailableMiscCraftingItems(char)
    }

    /**
     * Assign a task to a character.
     */
    fun assignTask(characterName: String, task: TaskType) {
        val runner = runners[characterName] ?: throw IllegalArgumentException("Unknown character: $characterName")
        runner.assignTask(task, scope)
        persistTasks()
    }

    /**
     * Stop a character's current task.
     */
    fun stopTask(characterName: String) {
        runners[characterName]?.stop()
        persistTasks()
    }

    /**
     * Stop all characters immediately (for quit/shutdown).
     */
    fun stopAll() {
        runners.values.forEach { it.stopImmediate() }
        // Don't clear persisted tasks on stopAll — they should resume on restart
    }

    /**
     * Stop all characters and clear persisted tasks.
     */
    fun stopAllAndClear() {
        runners.values.forEach { it.stop() }
        persistTasks()
    }

    private fun persistTasks() {
        val assignments = runners.mapValues { (_, runner) ->
            TaskAssignment(
                task = runner.currentTask,
                previousTask = runner.previousTask
            )
        }
        taskStore.save(assignments)
    }

    /**
     * Get the character names managed by this task manager.
     */
    fun getCharacterNames(): List<String> {
        return runners.keys.toList()
    }
}
