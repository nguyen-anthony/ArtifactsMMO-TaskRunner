package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Monster
import com.artifactsmmo.client.models.Resource
import com.artifactsmmo.client.models.SimpleItem
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
    private val contentCache = ContentCache(client.content)
    private val helper = ActionHelper(client, contentCache)
    private val gatheringExecutor = GatheringExecutor(helper)
    private val fightingExecutor = FightingExecutor(helper)
    private val craftingExecutor = CraftingExecutor(helper)
    private val taskMasterExecutor = TaskMasterExecutor(helper, gatheringExecutor, fightingExecutor)
    private val bankExecutor = BankExecutor(helper)

    private val runners = mutableMapOf<String, CharacterTaskRunner>()

    /**
     * Initialize runners for all characters. Restores saved tasks if available.
     */
    suspend fun initialize(): List<String> {
        // Pre-warm the map cache before restoring tasks so findNearest* calls are ready
        contentCache.preWarmMaps()

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
                bankExecutor = bankExecutor,
                logger = logger,
                onTaskChanged = { persistTasks() }
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
     * Get items that can be crafted with a given skill, filtered by character's skill level.
     * Used by the gathering wizard to show "Specific Crafted Item" options.
     */
    suspend fun getAvailableCraftedItems(characterName: String, skill: String): List<Item> {
        val char = client.characters.getCharacter(characterName)
        val skillLevel = CharacterUtils.getSkillLevel(char, skill) ?: 0
        return helper.getAvailableCraftedItems(skill, skillLevel)
    }

    /**
     * Resolve a crafted item to its gatherable resource source(s).
     * Returns null if the item cannot be obtained through gathering.
     */
    suspend fun findTaskItemSource(itemCode: String): ActionHelper.TaskItemSource? {
        return helper.findTaskItemSource(itemCode)
    }

    /**
     * Get cookable drop info for a monster, filtered by the character's cooking level.
     * Used by the fight wizard to configure drop strategies.
     */
    suspend fun getCookableDrops(characterName: String, monsterCode: String): List<ActionHelper.CookableDropInfo> {
        val char = client.characters.getCharacter(characterName)
        val cookingLevel = com.artifactsmmo.client.utils.CharacterUtils.getSkillLevel(char, "cooking") ?: 0
        val allDrops = helper.findCookableDrops(monsterCode)
        return allDrops.filter { it.cookingLevelRequired <= cookingLevel && it.useLevelRequired <= char.level }
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
     * Get available equipment options for a combat slot.
     */
    suspend fun getAvailableEquipmentForSlot(
        characterName: String,
        slotInfo: ActionHelper.SlotInfo
    ): List<ActionHelper.EquipmentOption> {
        val char = client.characters.getCharacter(characterName)
        return helper.getAvailableEquipmentForSlot(char, slotInfo)
    }

    /**
     * Simulate combat using the character's current gear with optional slot overrides.
     * [slotOverrides] maps slot name (e.g. "weapon") to an item code.
     */
    suspend fun simulateFightWithOverrides(
        characterName: String,
        monsterCode: String,
        slotOverrides: Map<String, String>,
        iterations: Int = 20
    ): com.artifactsmmo.client.models.CombatSimulationData {
        val char = client.characters.getCharacter(characterName)
        return helper.simulateFightWithSlotOverrides(char, monsterCode, slotOverrides, iterations)
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

    /**
     * Detail record combining a [SimpleItem] bank entry with the full [Item] metadata.
     */
    data class BankItemDetail(
        val bankItem: SimpleItem,
        val item: Item
    )

    /**
     * Fetch all bank items and enrich each with full item metadata from the content API.
     * Items whose metadata cannot be fetched are silently skipped.
     */
    suspend fun getBankItemsWithDetails(): List<BankItemDetail> {
        val allItems = mutableListOf<SimpleItem>()
        var page = 1
        while (true) {
            val result = client.bank.getBankItems(page = page, size = 100)
            allItems.addAll(result.data)
            if (page >= (result.pages ?: Int.MAX_VALUE)) break
            if (result.data.size < 100) break
            page++
        }

        return allItems.mapNotNull { bankItem ->
            runCatching { contentCache.getItem(bankItem.code) }
                .getOrNull()
                ?.let { BankItemDetail(bankItem, it) }
        }
    }
}
