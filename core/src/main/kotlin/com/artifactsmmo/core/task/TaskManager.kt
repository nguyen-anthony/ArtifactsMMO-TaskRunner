package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.RealtimeClient
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.EventDefinition
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
    val logger: TaskLogger = TaskLogger(),
    private val token: String = ""
) {
    private val contentCache = ContentCache(client.content)
    val bankState = BankState(client, scope, logger)
    internal val helper = ActionHelper(client, contentCache, bankState)
    private val gatheringExecutor = GatheringExecutor(helper)
    internal val fightingExecutor = FightingExecutor(helper)
    private val craftingExecutor = CraftingExecutor(helper)
    private val taskMasterExecutor = TaskMasterExecutor(helper, gatheringExecutor, fightingExecutor)
    private val bankExecutor = BankExecutor(helper)
    private val bossEncounterCoordinator = BossEncounterCoordinator()
    private val eventExecutor = EventExecutor(helper, fightingExecutor)
    private val eventConfigStore = EventConfigStore()
    private lateinit var webSocketManager: WebSocketManager
    lateinit var eventDispatcher: EventDispatcher
        private set

    private val runners = mutableMapOf<String, CharacterTaskRunner>()

    /**
     * Initialize runners for all characters. Restores saved tasks if available.
     */
    suspend fun initialize(): List<String> {
        // Fetch the account's completed achievements so conditional map tiles (e.g. tasks_trader)
        // are included in the map cache when the account has the required achievement unlocked.
        val completedAchievements = try {
            val details = client.account.getMyDetails()
            client.account.getCompletedAchievementCodes(details.username)
        } catch (_: Exception) {
            emptySet() // Graceful fallback — conditional tiles will be excluded
        }

        // Pre-warm the map cache before restoring tasks so findNearest* calls are ready
        contentCache.preWarmMaps(completedAchievements)
        MonsterProfileStore.load()

        // Set up WebSocket and event dispatcher before creating runners
        webSocketManager = WebSocketManager(RealtimeClient(token), client.events, scope, bankState)
        eventDispatcher = EventDispatcher(webSocketManager, eventConfigStore, this, contentCache, scope)
        bankState.start()

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
                onTaskChanged = { persistTasks() },
                bossEncounterCoordinator = bossEncounterCoordinator,
                eventExecutor = eventExecutor,
                eventDispatcher = eventDispatcher
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

        webSocketManager.start()
        eventDispatcher.start()

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
     * Returns the list of items and the character's skill level.
     */
    suspend fun getAvailableCraftedItems(characterName: String, skill: String, minLevel: Int? = null, maxLevel: Int? = null): Pair<List<Item>, Int> {
        val char = client.characters.getCharacter(characterName)
        val skillLevel = CharacterUtils.getSkillLevel(char, skill) ?: 0
        val effectiveMax = maxLevel?.coerceAtMost(skillLevel) ?: skillLevel
        return Pair(helper.getAvailableCraftedItems(skill, effectiveMax, minLevel), skillLevel)
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
    suspend fun getAvailableCraftingItems(characterName: String, skill: String, minLevel: Int? = null, maxLevel: Int? = null): List<ActionHelper.CraftableItemInfo> {
        val char = client.characters.getCharacter(characterName)
        return helper.getAvailableCraftingItems(char, skill, minLevel, maxLevel)
    }

    /**
     * Get available misc (non-weapon/gear/jewelry) craftable items from inventory + bank.
     */
    suspend fun getAvailableMiscCraftingItems(characterName: String, minLevel: Int? = null, maxLevel: Int? = null): List<ActionHelper.CraftableItemInfo> {
        val char = client.characters.getCharacter(characterName)
        return helper.getAvailableMiscCraftingItems(char, minLevel, maxLevel)
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
     * Assign a cooperative boss fight to an initiator and one or two participants.
     *
     * The initiator fires the fight API; participants navigate to the boss tile and signal
     * ready via the [BossEncounterCoordinator] before each fight round. Any participant
     * with an active task has that task cancelled immediately (no cleanup, per spec).
     */
    fun assignBossFight(
        initiatorName: String,
        participantNames: List<String>,
        monsterCode: String,
        monsterName: String,
        equipActions: List<ActionHelper.EquipAction> = emptyList(),
        dropStrategies: Map<String, DropStrategy> = emptyMap(),
        defaultDropStrategy: DropStrategy = DropStrategy.BANK_RAW
    ) {
        val initiatorRunner = runners[initiatorName]
            ?: throw IllegalArgumentException("Unknown initiator character: $initiatorName")
        for (name in participantNames) {
            runners[name] ?: throw IllegalArgumentException("Unknown participant character: $name")
        }
        require(participantNames.size <= 2) { "Boss fights support at most 2 participants" }

        // Register the encounter before launching runners so the coordinator is ready
        bossEncounterCoordinator.registerEncounter(initiatorName, participantNames, monsterCode)

        // Stop participant runners immediately (no cleanup — per spec, just cancel)
        for (name in participantNames) {
            runners[name]!!.stopImmediate()
        }

        // Assign participant tasks
        for (name in participantNames) {
            runners[name]!!.assignTask(
                TaskType.BossFight(
                    monsterCode = monsterCode,
                    monsterName = monsterName,
                    initiatorName = initiatorName,
                    participantNames = emptyList(),
                    isInitiator = false,
                    dropStrategies = dropStrategies,
                    defaultDropStrategy = defaultDropStrategy
                ),
                scope
            )
        }

        // Assign initiator task
        initiatorRunner.assignTask(
            TaskType.BossFight(
                monsterCode = monsterCode,
                monsterName = monsterName,
                initiatorName = initiatorName,
                participantNames = participantNames,
                isInitiator = true,
                equipActions = equipActions,
                dropStrategies = dropStrategies,
                defaultDropStrategy = defaultDropStrategy
            ),
            scope
        )

        persistTasks()
    }

    /**
     * Assign an event task to a character. Does not run cleanup — event tasks
     * save and restore previousTask via the runner's isQuickTask path.
     */
    fun assignEventTask(characterName: String, task: TaskType) {
        runners[characterName]?.assignTask(task, scope)
        persistTasks()
    }

    /**
     * Revert all characters currently running an event task for [eventCode] back to
     * their previous task. Called by [EventDispatcher] when an event_removed message arrives.
     */
    fun revertEventTasks(eventCode: String) {
        runners.values
            .filter { r ->
                r.currentTask.let {
                    (it is TaskType.EventGather && it.eventCode == eventCode) ||
                    (it is TaskType.EventNpc    && it.eventCode == eventCode) ||
                    (it is TaskType.EventFight  && it.eventCode == eventCode)
                }
            }
            .forEach { it.revertFromEvent(scope) }
        persistTasks()
    }

    /** Return the current event configs from the store. */
    fun getEventConfigs(): List<EventConfig> = eventConfigStore.load()

    /** Save event configs and reload the dispatcher's in-memory config. */
    fun saveEventConfigs(configs: List<EventConfig>) {
        eventConfigStore.save(configs)
        eventDispatcher.reloadConfig()
    }

    /** Fetch all event definitions from the API (all pages). */
    suspend fun getEventDefinitions(): List<EventDefinition> {
        val defs = mutableListOf<EventDefinition>()
        var page = 1
        while (true) {
            val result = client.events.getEvents(page = page, size = 100)
            defs.addAll(result.data)
            if (page >= (result.pages ?: Int.MAX_VALUE) || result.data.size < 100) break
            page++
        }
        return defs
    }

    /** Fetch all items sold/bought by an NPC (all pages). */
    suspend fun getNpcItems(npcCode: String): List<com.artifactsmmo.client.models.NPCItem> {
        val items = mutableListOf<com.artifactsmmo.client.models.NPCItem>()
        var page = 1
        while (true) {
            val result = client.content.getNPCItems(npcCode, page = page, size = 100)
            items.addAll(result.data)
            if (page >= (result.pages ?: Int.MAX_VALUE) || result.data.size < 100) break
            page++
        }
        return items
    }

    /** Return the most recent WebSocket log entries from the ring buffer. */
    fun getRecentWebSocketLogs(limit: Int = 500): List<WebSocketManager.WebSocketLogEntry> =
        if (::webSocketManager.isInitialized) webSocketManager.getRecentLogs(limit) else emptyList()

    /** Run a full gear optimization for [characterName] vs [monsterCode]. */
    suspend fun optimizeGear(characterName: String, monsterCode: String): GearOptimizer.OptimizationResult {
        val char = client.characters.getCharacter(characterName)
        return fightingExecutor.gearOptimizer.optimize(char, monsterCode)
    }

    /** Find the best combat weapon for [characterName] vs [monsterCode]. */
    suspend fun findBestWeapon(characterName: String, monsterCode: String): ActionHelper.EquipAction? {
        val char = client.characters.getCharacter(characterName)
        return helper.findBestCombatWeapon(char, monsterCode)
    }

    /**
     * Re-optimize gear for [characterName]'s current fight task in the background.
     * Equips the result immediately — the runner's fight loop uses the new gear next iteration.
     */
    suspend fun reoptimizeGear(characterName: String) {
        val runner = runners[characterName] ?: return
        val monsterCode = when (val task = runner.currentTask) {
            is TaskType.Fight      -> task.monsterCode
            is TaskType.TaskMaster -> client.characters.getCharacter(characterName).task
                                          .takeIf { it.isNotEmpty() } ?: return
            else -> return
        }
        val char = client.characters.getCharacter(characterName)
        val result = fightingExecutor.gearOptimizer.optimize(char, monsterCode)
        if (result.equipActions.isNotEmpty()) {
            helper.retrieveAndEquipItems(characterName, result.equipActions)
            fightingExecutor.gearOptimizer.markOptimized(characterName, monsterCode)
        }
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
        if (::webSocketManager.isInitialized) webSocketManager.stop()
        bankState.stop()
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
     * Reads from [bankState] snapshot — eliminates one API call from the GUI bank dialog.
     * Items whose metadata cannot be fetched are silently skipped.
     */
    suspend fun getBankItemsWithDetails(): List<BankItemDetail> {
        val allItems = bankState.getItems()

        return allItems.mapNotNull { bankItem ->
            runCatching { contentCache.getItem(bankItem.code) }
                .getOrNull()
                ?.let { BankItemDetail(bankItem, it) }
        }
    }
}
