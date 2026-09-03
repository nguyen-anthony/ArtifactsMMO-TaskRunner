package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.client.utils.CharacterUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration.Companion.seconds

/**
 * Runs a task loop for a single character in its own coroutine.
 * Exposes status via a StateFlow for the UI to observe.
 *
 * On task transitions (reassign or stop), runs a cleanup phase that
 * processes leftover inventory (craft + bank) before starting the new task.
 *
 * For crafting tasks, tracks a previousTask so the character can revert
 * when materials run out or the craft target is reached.
 */
class CharacterTaskRunner(
    val characterName: String,
    private val client: ArtifactsMMOClient,
    private val helper: ActionHelper,
    private val gatheringExecutor: GatheringExecutor,
    private val fightingExecutor: FightingExecutor,
    private val craftingExecutor: CraftingExecutor,
    private val taskMasterExecutor: TaskMasterExecutor,
    private val bankExecutor: BankExecutor,
    private val logger: TaskLogger,
    /**
     * Called whenever [currentTask] or [previousTask] changes due to an
     * internal revert (craft/task-master completes). Allows [TaskManager]
     * to persist the new state immediately so restarts see the correct task.
     */
    private val onTaskChanged: () -> Unit = {},
    private val bossEncounterCoordinator: BossEncounterCoordinator,
    private val eventExecutor: EventExecutor,
    private val eventDispatcher: EventDispatcher
) {
    private val _status = MutableStateFlow(RunnerStatus(characterName = characterName))
    val status: StateFlow<RunnerStatus> = _status.asStateFlow()

    private var job: Job? = null
    private var scope: CoroutineScope? = null
    var currentTask: TaskType = TaskType.Idle
        private set

    /** Task to revert to when a crafting task completes or runs out of materials. */
    var previousTask: TaskType? = null
        private set

    /** Mutable counter for specific-mode craft progress within the current task. */
    private var craftedSoFar: Int = 0

    /** Consecutive fight losses without a win. Reset to 0 on any FightWon. */
    private var consecutiveDeaths: Int = 0

    /** Threshold of consecutive losses before stopping the fight loop (or cancelling a task master monster task). */
    private val consecutiveDeathThreshold = 3

    /**
     * Assign a new task. Cancels the current task loop, runs cleanup for the
     * previous task, then starts the new one.
     */
    fun assignTask(task: TaskType, scope: CoroutineScope) {
        this.scope = scope
        val oldTask = currentTask
        val oldJob = job
        job?.cancel()

        // Save previous task when assigning a craft, task master, or quick bank/inventory task (and current task isn't idle)
        val isQuickTask = task is TaskType.BankWithdraw || task is TaskType.BankRecycle ||
            task is TaskType.InventoryDeposit || task is TaskType.InventoryRecycle ||
            task is TaskType.BulkBankWithdraw || task is TaskType.BulkInventoryDeposit ||
            task is TaskType.EventGather || task is TaskType.EventNpc || task is TaskType.EventFight
        if ((task is TaskType.Craft || task is TaskType.TaskMaster || isQuickTask) && oldTask !is TaskType.Idle) {
            previousTask = oldTask
        } else if (task !is TaskType.Craft && task !is TaskType.TaskMaster && !isQuickTask) {
            // Clear previous task when assigning a regular task (including BossFight)
            previousTask = null
        }

        currentTask = task
        craftedSoFar = if (task is TaskType.Craft) task.craftedSoFar else 0

        if (task is TaskType.Idle) {
            // Stop with cleanup
            job = scope.launch {
                runCleanup(oldTask)
                updateStatus { it.copy(task = task, statusMessage = "Idle", isRunning = false) }
            }
            return
        }

        job = scope.launch {
            // Wait for the previous job to fully stop before doing anything.
            // job?.cancel() is cooperative — the old coroutine may still be mid-action
            // (e.g. inside an HTTP gather call or waitForCooldown). Joining ensures the
            // old loop has fully exited before the new task's cleanup and cooldown wait
            // begin, preventing both loops from making API calls simultaneously.
            oldJob?.join()

            // Cleanup previous task before starting new one.
            // Skip cleanup when transitioning into or out of a BossFight or event task —
            // event tasks save and revert previousTask; BossFight participants are hard-cancelled.
            if (oldTask !is TaskType.BossFight && task !is TaskType.BossFight &&
                oldTask !is TaskType.EventGather && task !is TaskType.EventGather &&
                oldTask !is TaskType.EventNpc && task !is TaskType.EventNpc &&
                oldTask !is TaskType.EventFight && task !is TaskType.EventFight) {
                runCleanup(oldTask)
            }

            // Wait for any active cooldown before starting — prevents 486 errors
            // when a task is switched while the character is mid-action.
            try {
                helper.waitForActiveCooldown(characterName)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {}

            updateStatus { it.copy(
                task = task,
                statusMessage = "Starting...",
                isRunning = true,
                gatherCount = 0,
                fightCount = 0,
                craftCount = 0,
                recycleCount = 0,
                bankTrips = 0,
                tasksCompleted = 0,
                lastError = null,
                consecutiveDeaths = 0
            )}
            consecutiveDeaths = 0

            // Refresh character level for display
            try {
                val char = helper.refreshCharacter(characterName)
                updateStatus { it.copy(characterLevel = char.level) }
            } catch (_: Exception) {}

            // For fight tasks, execute any pending equipment actions first
            if (task is TaskType.Fight && task.equipActions.isNotEmpty()) {
                try {
                    logger.log(characterName, "Retrieving and equipping gear before fight...")
                    updateStatus { it.copy(statusMessage = "Equipping gear...") }
                    helper.retrieveAndEquipItems(characterName, task.equipActions)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.log(characterName, "[equip] Error during gear retrieval: ${e.message}")
                }
            }

            // For event fight tasks, execute pending equipment + utility actions first.
            // These are computed by EventDispatcher (weapon+gear optimization) and passed
            // through the task, so the runner applies them in the proper sequence AFTER
            // waitForActiveCooldown — avoiding 499 cooldown races between the previous task
            // and the new equipping actions.
            if (task is TaskType.EventFight && (task.equipActions.isNotEmpty() || task.utilityActions.isNotEmpty())) {
                try {
                    if (task.equipActions.isNotEmpty()) {
                        logger.log(characterName, "Equipping ${task.equipActions.size} item(s) before event fight...")
                        updateStatus { it.copy(statusMessage = "Equipping gear for event...") }
                        helper.retrieveAndEquipItems(characterName, task.equipActions)
                    }
                    if (task.utilityActions.isNotEmpty()) {
                        logger.log(characterName, "Applying ${task.utilityActions.size} utility potion(s) before event fight...")
                        updateStatus { it.copy(statusMessage = "Applying utility potions for event...") }
                        helper.retrieveAndEquipUtilities(characterName, task.utilityActions)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.log(characterName, "[event-equip] Error during gear/utility retrieval: ${e.message}")
                }
            }

            // For boss fights: apply equip actions and utility actions BEFORE the fight.
            // Applies to both initiator and participants — CoopOptimizer computes plans for all
            // participants. The runner's waitForActiveCooldown (above) already drained the
            // previous task's cooldown, so equip/utility actions execute cleanly here.
            if (task is TaskType.BossFight && (task.equipActions.isNotEmpty() || task.utilityActions.isNotEmpty() || task.reservePotions.isNotEmpty() || task.foodQuantity > 0 || task.transitionCosts.isNotEmpty() || task.spareKeys.isNotEmpty())) {
                try {
                    if (task.equipActions.isNotEmpty()) {
                        logger.log(characterName, "Equipping ${task.equipActions.size} item(s) before boss fight...")
                        updateStatus { it.copy(statusMessage = "Equipping gear for boss fight...") }
                        helper.retrieveAndEquipItems(characterName, task.equipActions)
                    }
                    if (task.utilityActions.isNotEmpty()) {
                        logger.log(characterName, "Applying ${task.utilityActions.size} utility potion(s) before boss fight...")
                        updateStatus { it.copy(statusMessage = "Applying utility potions for boss fight...") }
                        helper.retrieveAndEquipUtilities(characterName, task.utilityActions)
                    }
                    if (task.reservePotions.isNotEmpty()) {
                        logger.log(characterName, "Withdrawing reserve potions for boss fight loop (${task.reservePotions.entries.joinToString { "${it.value}x ${it.key}" }})...")
                        updateStatus { it.copy(statusMessage = "Withdrawing reserve potions...") }
                        helper.withdrawReservePotions(characterName, task.reservePotions)
                    }
                    if (task.foodCode != null && task.foodQuantity > 0) {
                        logger.log(characterName, "Withdrawing food for boss fight (${task.foodQuantity}x ${task.foodCode})...")
                        updateStatus { it.copy(statusMessage = "Withdrawing food for boss fight...") }
                        helper.bankWithdrawItems(characterName, listOf(com.artifactsmmo.client.models.SimpleItem(task.foodCode, task.foodQuantity)))
                    }
                    val allKeys = (task.transitionCosts.entries + task.spareKeys.entries)
                        .groupBy({ it.key }, { it.value })
                        .mapValues { (_, values) -> values.sum() }
                    if (allKeys.isNotEmpty()) {
                        logger.log(characterName, "Withdrawing dungeon keys (${allKeys.entries.joinToString { "${it.value}x ${it.key}" }})...")
                        updateStatus { it.copy(statusMessage = "Withdrawing dungeon keys...") }
                        helper.bankWithdrawItems(characterName, allKeys.map { (code, qty) -> com.artifactsmmo.client.models.SimpleItem(code, qty) })
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.log(characterName, "[boss-equip] Error during gear/utility retrieval: ${e.message}")
                }
            }

            // For gather tasks, check bank for leftover raw materials to craft
            if (task is TaskType.Gather) {
                try {
                    gatheringExecutor.prepareGatherTask(characterName, task) { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.log(characterName, "[prepare] Error during bank cleanup: ${e.message}")
                }
            }

            runTaskLoop(task)
        }
    }

    /**
     * Stop the current task with cleanup.
     */
    fun stop() {
        val oldTask = currentTask
        val oldJob = job
        job?.cancel()
        currentTask = TaskType.Idle
        previousTask = null

        // If stopping a boss fight initiator, clear the encounter to unblock participants
        if (oldTask is TaskType.BossFight && oldTask.isInitiator) {
            bossEncounterCoordinator.clearEncounter(characterName)
        }

        val s = scope
        if (s != null && oldTask !is TaskType.Idle) {
            // Join the old job before running cleanup so it isn't still making API calls
            job = s.launch {
                oldJob?.join()
                runCleanup(oldTask)
                updateStatus { it.copy(task = TaskType.Idle, statusMessage = "Stopped", isRunning = false) }
                job = null
            }
        } else {
            job = null
            updateStatus { it.copy(task = TaskType.Idle, statusMessage = "Stopped", isRunning = false) }
        }
    }

    /**
     * Stop immediately without cleanup (used for quit/shutdown).
     */
    fun stopImmediate() {
        val oldTask = currentTask
        job?.cancel()
        job = null
        currentTask = TaskType.Idle

        // If stopping a boss fight initiator, clear the encounter to unblock participants
        if (oldTask is TaskType.BossFight && oldTask.isInitiator) {
            bossEncounterCoordinator.clearEncounter(characterName)
        }

        updateStatus { it.copy(task = TaskType.Idle, statusMessage = "Stopped", isRunning = false) }
    }

    /**
     * Run cleanup for a previous task: craft leftover materials if applicable, then bank everything.
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    private suspend fun runCleanup(previousTask: TaskType) {
        if (previousTask is TaskType.Idle) return

        try {
            val char = helper.refreshCharacter(characterName)

            // Wait for any active cooldown before performing cleanup actions.
            // The task may have been switched while the character was mid-action.
            if (char.cooldown > 0) {
                updateStatus { it.copy(statusMessage = "Waiting for cooldown...") }
                helper.waitForCooldown(char.cooldownExpiration)
            }

            val totalItems = char.inventory.sumOf { it.quantity }
            if (totalItems == 0) return

            val onStatus: (String) -> Unit = { msg ->
                logger.log(characterName, "[cleanup] $msg")
                updateStatus { it.copy(statusMessage = "Cleanup: $msg") }
            }

            when (previousTask) {
                is TaskType.Gather -> cleanupGatherTask(previousTask, onStatus)
                is TaskType.Fight -> cleanupFightTask(previousTask, onStatus)
                is TaskType.Craft -> cleanupCraftTask(onStatus)
                is TaskType.TaskMaster -> cleanupTaskMasterTask(onStatus)
                is TaskType.BossFight -> {} // no cleanup for boss fight — bank is handled per-loop
                is TaskType.EventGather, is TaskType.EventNpc, is TaskType.EventFight -> {} // no cleanup for event tasks
                is TaskType.BankWithdraw, is TaskType.BankRecycle,
                is TaskType.InventoryDeposit, is TaskType.InventoryRecycle,
                is TaskType.BulkBankWithdraw, is TaskType.BulkInventoryDeposit -> {} // no cleanup
                is TaskType.Idle -> {} // unreachable
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(characterName, "[cleanup] Error during cleanup: ${e.message}")
        }
    }

    /**
     * Cleanup after a gather task: craft target item or cook fish if applicable, then bank all.
     */
    private suspend fun cleanupGatherTask(task: TaskType.Gather, onStatus: (String) -> Unit) {
        if (task.targetItemCode != null) {
            // Craft the specific target item from leftover inventory
            val char = helper.refreshCharacter(characterName)
            val targetItem = try { helper.getItem(task.targetItemCode) } catch (_: Exception) { null }
            val craft = targetItem?.craft
            val workshopSkill = craft?.skill

            if (craft != null && workshopSkill != null) {
                val maxCraftable = craft.items.minOfOrNull { ingredient ->
                    helper.getItemQuantity(char, ingredient.code) / ingredient.quantity
                } ?: 0

                if (maxCraftable > 0) {
                    val workshop = helper.findNearestWorkshop(char, workshopSkill)
                    if (workshop != null) {
                        onStatus("Crafting leftover materials into ${targetItem.name}...")
                        helper.moveTo(characterName, workshop.x, workshop.y)

                        val updatedChar = helper.refreshCharacter(characterName)
                        val actualCraftable = craft.items.minOfOrNull { ingredient ->
                            helper.getItemQuantity(updatedChar, ingredient.code) / ingredient.quantity
                        } ?: 0

                        if (actualCraftable > 0) {
                            onStatus("Crafting ${actualCraftable}x ${targetItem.name}...")
                            helper.craft(characterName, targetItem.code, actualCraftable)
                        }
                    }
                }
            }
        } else if (task.cookBeforeDeposit) {
            // Cook simple fish recipes from leftover inventory
            val char = helper.refreshCharacter(characterName)
            val cookable = helper.findCraftableRefinements(char, "fishing")
                .filter { (item, _) -> item.craft?.items?.size == 1 }

            if (cookable.isNotEmpty()) {
                val workshop = helper.findNearestWorkshop(char, "cooking")
                if (workshop != null) {
                    onStatus("Cooking leftover raw fish...")
                    helper.moveTo(characterName, workshop.x, workshop.y)

                    val updatedChar = helper.refreshCharacter(characterName)
                    val updatedCookable = helper.findCraftableRefinements(updatedChar, "fishing")
                        .filter { (item, _) -> item.craft?.items?.size == 1 }

                    for ((item, maxQty) in updatedCookable) {
                        onStatus("Cooking ${maxQty}x ${item.name}...")
                        helper.craft(characterName, item.code, maxQty)
                    }
                }
            }
        }

        // Bank everything
        val char = helper.refreshCharacter(characterName)
        val totalItems = char.inventory.sumOf { it.quantity }
        if (totalItems > 0) {
            onStatus("Banking $totalItems items...")
            helper.bankDepositAll(characterName)
        }
    }

    /**
     * Cleanup after a fight task: cook drops per strategy, then bank everything.
     * COOK_AND_USE and COOK_AND_BANK drops get cooked; BANK_RAW drops are deposited raw.
     * On cleanup we bank everything (including food) since we're transitioning away.
     */
    private suspend fun cleanupFightTask(task: TaskType.Fight, onStatus: (String) -> Unit) {
        var char = helper.refreshCharacter(characterName)

        // Discover cookable drops for this monster
        val cookableDrops = helper.findCookableDrops(task.monsterCode)
        val cookingLevel = CharacterUtils.getSkillLevel(char, "cooking") ?: 0
        val allCookable = cookableDrops.filter {
            it.cookingLevelRequired <= cookingLevel && it.useLevelRequired <= char.level
        }

        // Only cook drops that are COOK_AND_USE or COOK_AND_BANK (not BANK_RAW)
        val dropsToCook = allCookable.filter {
            val strategy = task.dropStrategies[it.rawCode] ?: task.defaultDropStrategy
            strategy != DropStrategy.BANK_RAW
        }

        if (dropsToCook.isNotEmpty()) {
            var needsWorkshop = false
            for (info in dropsToCook) {
                if (helper.getItemQuantity(char, info.rawCode) >= info.rawPerCraft) {
                    needsWorkshop = true
                    break
                }
            }

            if (needsWorkshop) {
                val workshop = helper.findNearestWorkshop(char, "cooking")
                if (workshop != null) {
                    onStatus("Cooking leftover raw food...")
                    helper.moveTo(characterName, workshop.x, workshop.y)
                    char = helper.refreshCharacter(characterName)

                    for (info in dropsToCook) {
                        val rawQty = helper.getItemQuantity(char, info.rawCode)
                        val craftQty = rawQty / info.rawPerCraft
                        if (craftQty > 0) {
                            onStatus("Cooking ${craftQty}x ${info.cookedCode} (from ${craftQty * info.rawPerCraft}x ${info.rawCode})...")
                            helper.craft(characterName, info.cookedCode, craftQty)
                        }
                    }
                }
            }
        }

        // Bank everything (don't keep food — transitioning away from fighting)
        char = helper.refreshCharacter(characterName)
        val totalItems = char.inventory.sumOf { it.quantity }
        if (totalItems > 0) {
            onStatus("Banking $totalItems items...")
            helper.bankDepositAll(characterName)
        }
    }

    /**
     * Cleanup after a craft task: deposit any remaining items to bank.
     */
    private suspend fun cleanupCraftTask(onStatus: (String) -> Unit) {
        val char = helper.refreshCharacter(characterName)
        val totalItems = char.inventory.sumOf { it.quantity }
        if (totalItems > 0) {
            onStatus("Banking $totalItems leftover items...")
            helper.bankDepositAll(characterName)
        }
    }

    /**
     * Cleanup after a task master task: deposit any remaining items to bank.
     */
    private suspend fun cleanupTaskMasterTask(onStatus: (String) -> Unit) {
        val char = helper.refreshCharacter(characterName)
        val totalItems = char.inventory.sumOf { it.quantity }
        if (totalItems > 0) {
            onStatus("Banking $totalItems leftover items...")
            helper.bankDepositAll(characterName)
        }
    }

    private suspend fun runTaskLoop(task: TaskType) {
        // previousChar threads the Character returned by each action response into the
        // next iteration's executeStep(), eliminating one GET /characters/{name} per tick.
        // Reset to null whenever an error or non-threading result occurs.
        var previousChar: com.artifactsmmo.client.models.Character? = null

        while (currentCoroutineContext().isActive) {
            try {
                val result = when (task) {
                    is TaskType.Gather -> gatheringExecutor.executeStep(characterName, task, { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }, previousChar = previousChar)
                    is TaskType.Fight -> fightingExecutor.executeStep(characterName, task, { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }, previousChar = previousChar)
                    is TaskType.BossFight -> {
                        // Update awaitingParticipants status for the initiator waiting state
                        if (task.isInitiator && task.participantNames.isNotEmpty()) {
                            updateStatus { it.copy(awaitingParticipants = task.participantNames) }
                        }
                        val bossResult = fightingExecutor.executeBossStep(
                            characterName, task, bossEncounterCoordinator,
                            { msg ->
                                logger.log(characterName, msg)
                                updateStatus { it.copy(statusMessage = msg) }
                            },
                            previousChar = previousChar
                        )
                        // Clear awaitingParticipants once the step returns
                        updateStatus { it.copy(awaitingParticipants = null) }
                        bossResult
                    }
                    is TaskType.Craft -> {
                        // Inject current craftedSoFar for both BANK and RECYCLE modes
                        // (RECYCLE uses it to detect the first batch and cap at targetQuantity)
                        val craftTask = if (task.mode == CraftMode.BANK || task.mode == CraftMode.RECYCLE) {
                            task.copy(craftedSoFar = craftedSoFar)
                        } else task

                        craftingExecutor.executeStep(characterName, craftTask, { msg ->
                            logger.log(characterName, msg)
                            updateStatus { it.copy(statusMessage = msg) }
                        }, previousChar = previousChar)
                    }
                    is TaskType.Idle -> break
                    is TaskType.TaskMaster -> taskMasterExecutor.executeStep(characterName, task, { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }, previousChar = previousChar)
                    is TaskType.BankWithdraw -> bankExecutor.executeBankWithdraw(characterName, task) { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }
                    is TaskType.BankRecycle -> bankExecutor.executeBankRecycle(characterName, task) { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }
                    is TaskType.InventoryDeposit -> bankExecutor.executeInventoryDeposit(characterName, task) { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }
                    is TaskType.InventoryRecycle -> bankExecutor.executeInventoryRecycle(characterName, task) { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }
                    is TaskType.BulkBankWithdraw -> bankExecutor.executeBulkBankWithdraw(characterName, task) { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }
                    is TaskType.BulkInventoryDeposit -> bankExecutor.executeBulkInventoryDeposit(characterName, task) { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }
                    is TaskType.EventGather -> eventExecutor.executeGatherStep(
                        characterName, task,
                        isEventActive = { eventDispatcher.activeEvents.value.containsKey(task.eventCode) },
                        onStatus = { msg ->
                            logger.log(characterName, msg)
                            updateStatus { it.copy(statusMessage = msg, activeEventCode = task.eventCode) }
                        },
                        previousChar = previousChar
                    )
                    is TaskType.EventNpc -> eventExecutor.executeNpcStep(
                        characterName, task
                    ) { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg, activeEventCode = task.eventCode) }
                    }
                    is TaskType.EventFight -> eventExecutor.executeEventFightStep(
                        characterName, task,
                        isEventActive = { eventDispatcher.activeEvents.value.containsKey(task.eventCode) },
                        onStatus = { msg ->
                            logger.log(characterName, msg)
                            updateStatus { it.copy(statusMessage = msg, activeEventCode = task.eventCode) }
                        },
                        previousChar = previousChar
                    )
                }

                // Update counters and thread character to next iteration where available.
                // For results that carry no character (bank trips, errors, etc.) reset to null
                // so the next tick falls back to a fresh API fetch.
                when (result) {
                    is StepResult.Gathered -> {
                        previousChar = result.character  // thread gather response character
                        updateStatus { it.copy(gatherCount = it.gatherCount + 1, lastError = null) }
                    }
                    is StepResult.FightWon -> {
                        previousChar = result.character  // thread fight response character
                        consecutiveDeaths = 0
                        updateStatus { it.copy(fightCount = it.fightCount + 1, lastError = null, consecutiveDeaths = 0) }
                    }
                    is StepResult.Crafted -> {
                        previousChar = null  // craft path involves bank trips; reset
                        craftedSoFar += result.count
                        updateStatus { it.copy(
                            craftCount = it.craftCount + result.count,
                            recycleCount = it.recycleCount + result.recycled,
                            lastError = null
                        )}
                    }
                    is StepResult.Banked -> {
                        previousChar = null
                        updateStatus { it.copy(bankTrips = it.bankTrips + 1, lastError = null) }
                    }
                    is StepResult.CraftedAndBanked -> {
                        previousChar = null
                        updateStatus { it.copy(
                            craftCount = it.craftCount + result.craftCount,
                            bankTrips = it.bankTrips + 1,
                            lastError = null
                        )}
                    }
                    is StepResult.FightLost -> {
                        previousChar = null
                        consecutiveDeaths++
                        updateStatus { it.copy(lastError = result.message, consecutiveDeaths = consecutiveDeaths) }
                        if (consecutiveDeaths >= consecutiveDeathThreshold) {
                            val monsterName = when (val t = task) {
                                is TaskType.Fight -> t.monsterName
                                is TaskType.BossFight -> t.monsterName
                                is TaskType.EventFight -> t.monsterName
                                is TaskType.TaskMaster -> result.message.removePrefix("Lost to ")
                                else -> "unknown monster"
                            }
                            val msg = "Stopped after $consecutiveDeaths consecutive deaths fighting $monsterName"
                            logger.log(characterName, msg)
                            updateStatus { it.copy(statusMessage = msg, lastError = msg) }
                            when (val taskSnapshot = task) {
                                is TaskType.TaskMaster -> {
                                    // For task master: cancel the current monster task and accept a new one.
                                    previousChar = null
                                    consecutiveDeaths = 0
                                    updateStatus { it.copy(consecutiveDeaths = 0) }
                                    val monsterCode = try {
                                        helper.refreshCharacter(characterName).task
                                    } catch (_: Exception) { "" }
                                    taskMasterExecutor.blacklistMonster(monsterCode)
                                    taskMasterExecutor.cancelCurrentMonsterTask(characterName, taskSnapshot.type, { logMsg ->
                                        logger.log(characterName, logMsg)
                                        updateStatus { it.copy(statusMessage = logMsg) }
                                    })
                                }
                                else -> {
                                    // For plain Fight, BossFight, and EventFight: revert to previous task
                                    if (task is TaskType.BossFight && task.isInitiator) {
                                        bossEncounterCoordinator.clearEncounter(characterName)
                                    }
                                    if (task is TaskType.EventFight) {
                                        logger.log(characterName, "Too many deaths in event fight — reverting to previous task")
                                    }
                                    revertToPreviousTask()
                                    break
                                }
                            }
                        } else {
                            delay(5.seconds) // Brief pause after a loss
                        }
                    }
                    is StepResult.Rested -> {
                        previousChar = null
                        updateStatus { it.copy(lastError = null) }
                    }
                    is StepResult.NeedsRestock -> {
                        previousChar = null
                        if (task is TaskType.BossFight) {
                            logger.log(characterName, "Boss fight restock — full resupply trip")
                            updateStatus { it.copy(bankTrips = it.bankTrips + 1, lastError = null) }
                            fightingExecutor.restockForBossFight(characterName, task) { msg ->
                                logger.log(characterName, msg)
                                updateStatus { it.copy(statusMessage = msg) }
                            }
                        }
                    }
                    is StepResult.Waiting -> {
                        previousChar = null
                        // BossFight steps manage their own timing internally; skip the delay.
                        if (task !is TaskType.BossFight) {
                            delay(3.seconds)
                        }
                    }
                    is StepResult.Error -> {
                        previousChar = null
                        updateStatus { it.copy(statusMessage = "Error: ${result.message}", lastError = result.message) }
                        delay(10.seconds)
                    }

                    is StepResult.OutOfMaterials -> {
                        previousChar = null
                        logger.log(characterName, "Out of materials for crafting task")
                        revertToPreviousTask()
                        break
                    }
                    is StepResult.CraftTaskComplete -> {
                        previousChar = null
                        logger.log(characterName, "Crafting task complete!")
                        revertToPreviousTask()
                        break
                    }

                    // Quick bank/inventory task results
                    is StepResult.QuickTaskComplete -> {
                        previousChar = null
                        logger.log(characterName, "Quick task complete!")
                        revertToPreviousTask()
                        break
                    }

                    is StepResult.EventExpired -> {
                        previousChar = null
                        logger.log(characterName, "Event ended — reverting to previous task")
                        updateStatus { it.copy(activeEventCode = null) }
                        revertToPreviousTask()
                        break
                    }

                    // Task master results
                    is StepResult.TaskMasterTaskCompleted -> {
                        previousChar = null
                        updateStatus { it.copy(tasksCompleted = it.tasksCompleted + 1, lastError = null) }
                        logger.log(characterName, "Task master task completed! (total: ${_status.value.tasksCompleted})")
                        // Loop continues — will accept next task on next step
                    }
                    is StepResult.TaskMasterTaskCancelled -> {
                        previousChar = null
                        updateStatus { it.copy(lastError = null) }
                        logger.log(characterName, "Task cancelled, will accept new one")
                        // Loop continues — will accept next task on next step
                    }
                    is StepResult.TaskMasterNoViableTask -> {
                        previousChar = null
                        logger.log(characterName, "No viable monster task found (exhausted attempts or no tasks_coins)")
                        revertToPreviousTask()
                        break
                    }
                    is StepResult.TooManyDeaths -> {
                        // Emitted by TaskMasterExecutor when consecutive death handling is delegated
                        // through the executor rather than inline. Loop continues — executor has
                        // already cancelled the task and the next step will accept a new one.
                        previousChar = null
                        consecutiveDeaths = 0
                        updateStatus { it.copy(consecutiveDeaths = 0, lastError = null) }
                        logger.log(characterName, "Consecutive death limit reached for ${result.monsterName} — task cancelled, accepting new one")
                    }
                }

            } catch (e: CancellationException) {
                throw e // Propagate cancellation
            } catch (e: TransitionConditionUnsatisfiableException) {
                // Character can't afford the transition condition (missing key item, etc.).
                // Retrying won't help — the missing items won't appear. Stop the task cleanly.
                previousChar = null
                val msg = "Cannot reach destination: ${e.message}"
                logger.log(characterName, msg)
                updateStatus { it.copy(statusMessage = msg, lastError = msg, isRunning = false) }
                // If stopping a boss fight initiator, unblock waiting participants
                if (task is TaskType.BossFight && task.isInitiator) {
                    bossEncounterCoordinator.clearEncounter(characterName)
                }
                break
            } catch (e: ArtifactsApiException) {
                previousChar = null
                val msg = "API Error ${e.errorCode}: ${e.message}"
                logger.log(characterName, msg)
                updateStatus { it.copy(statusMessage = msg, lastError = msg) }
                when (e.errorCode) {
                    486 -> delay(5.seconds)  // Cooldown
                    429 -> delay(15.seconds) // Rate limit
                    else -> delay(10.seconds)
                }
            } catch (e: Exception) {
                previousChar = null
                val msg = "Error: ${e.message}"
                logger.log(characterName, msg)
                updateStatus { it.copy(statusMessage = msg, lastError = msg) }
                delay(10.seconds)
            }
        }

        updateStatus { it.copy(isRunning = false) }
    }

    /**
     * Revert to the previous task (or idle) after a craft/task-master task completes.
     * Runs cleanup first, then assigns the previous task.
     */
    internal suspend fun revertToPreviousTask() {
        updateStatus { it.copy(activeEventCode = null) }
        val fallback = previousTask
        previousTask = null

        // Cleanup current task (deposit leftovers)
        val onCleanup: (String) -> Unit = { msg ->
            logger.log(characterName, "[cleanup] $msg")
            updateStatus { it.copy(statusMessage = "Cleanup: $msg") }
        }
        when (currentTask) {
            is TaskType.Craft -> cleanupCraftTask(onCleanup)
            is TaskType.TaskMaster -> cleanupTaskMasterTask(onCleanup)
            is TaskType.BankWithdraw, is TaskType.BankRecycle,
            is TaskType.InventoryDeposit, is TaskType.InventoryRecycle,
            is TaskType.BulkBankWithdraw, is TaskType.BulkInventoryDeposit -> {} // no extra cleanup needed
            else -> {}
        }

        if (fallback != null && fallback !is TaskType.Idle) {
            logger.log(characterName, "Reverting to previous task: ${describeTask(fallback)}")
            updateStatus { it.copy(statusMessage = "Reverting to previous task...") }

            // Assign the fallback task using the stored scope
            val s = scope
            if (s != null) {
                currentTask = fallback
                craftedSoFar = 0
                // Swap is complete — persist so a restart sees the correct task
                onTaskChanged()

                updateStatus { it.copy(
                    task = fallback,
                    statusMessage = "Starting...",
                    isRunning = true,
                    gatherCount = 0,
                    fightCount = 0,
                    craftCount = 0,
                    recycleCount = 0,
                    bankTrips = 0,
                    tasksCompleted = 0,
                    lastError = null
                )}

                // For gather tasks, run the prepare step
                if (fallback is TaskType.Gather) {
                    try {
                        gatheringExecutor.prepareGatherTask(characterName, fallback) { msg ->
                            logger.log(characterName, msg)
                            updateStatus { it.copy(statusMessage = msg) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.log(characterName, "[prepare] Error during bank cleanup: ${e.message}")
                    }
                }

                // Drain any cooldown before re-entering the loop
                try {
                    helper.waitForActiveCooldown(characterName)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {}

                runTaskLoop(fallback)
            }
        } else {
            currentTask = TaskType.Idle
            // Persist the now-idle state
            onTaskChanged()
            updateStatus { it.copy(task = TaskType.Idle, statusMessage = "Idle (task complete)", isRunning = false) }
        }
    }

    /**
     * Called by [TaskManager.revertEventTasks] when an event ends externally.
     * Cancels the current event task job and reverts to the previous task.
     */
    fun revertFromEvent(scope: CoroutineScope) {
        job?.cancel()
        scope.launch { revertToPreviousTask() }
    }

    private fun describeTask(task: TaskType): String {
        return when (task) {
            is TaskType.Gather -> "gather ${task.resourceName} (${task.skill})"
            is TaskType.Fight -> "fight ${task.monsterName}"
            is TaskType.BossFight -> if (task.isInitiator) "boss fight ${task.monsterName} (initiator)" else "boss fight ${task.monsterName} (participant)"
            is TaskType.Craft -> "craft ${task.itemName} (${task.skill})"
            is TaskType.TaskMaster -> "task master (${task.type})"
            is TaskType.BankWithdraw -> "withdraw ${task.quantity}x ${task.itemName} from bank"
            is TaskType.BankRecycle -> "recycle ${task.quantity}x ${task.itemName} (bank)"
            is TaskType.InventoryDeposit -> "deposit ${task.quantity}x ${task.itemName} to bank"
            is TaskType.InventoryRecycle -> "recycle ${task.quantity}x ${task.itemName} (inventory)"
            is TaskType.BulkBankWithdraw -> "withdraw ${task.items.size} items from bank"
            is TaskType.BulkInventoryDeposit -> "deposit ${task.items.size} items to bank"
            is TaskType.EventGather -> "event: gathering ${task.resourceName}"
            is TaskType.EventNpc -> "event: trading at ${task.npcName}"
            is TaskType.EventFight -> "event: fighting ${task.monsterName}"
            is TaskType.Idle -> "idle"
        }
    }

    private fun updateStatus(transform: (RunnerStatus) -> RunnerStatus) {
        _status.value = transform(_status.value)
    }
}
