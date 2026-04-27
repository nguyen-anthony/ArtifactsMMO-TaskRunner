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
    private val onTaskChanged: () -> Unit = {}
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

    /**
     * Assign a new task. Cancels the current task loop, runs cleanup for the
     * previous task, then starts the new one.
     */
    fun assignTask(task: TaskType, scope: CoroutineScope) {
        this.scope = scope
        val oldTask = currentTask
        job?.cancel()

        // Save previous task when assigning a craft, task master, or quick bank/inventory task (and current task isn't idle)
        val isQuickTask = task is TaskType.BankWithdraw || task is TaskType.BankRecycle ||
            task is TaskType.InventoryDeposit || task is TaskType.InventoryRecycle
        if ((task is TaskType.Craft || task is TaskType.TaskMaster || isQuickTask) && oldTask !is TaskType.Idle) {
            previousTask = oldTask
        } else if (task !is TaskType.Craft && task !is TaskType.TaskMaster && !isQuickTask) {
            // Clear previous task when assigning a regular task
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
            // Cleanup previous task before starting new one
            runCleanup(oldTask)

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
                lastError = null
            )}

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
        job?.cancel()
        currentTask = TaskType.Idle
        previousTask = null

        val s = scope
        if (s != null && oldTask !is TaskType.Idle) {
            // Launch cleanup then go idle
            job = s.launch {
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
        job?.cancel()
        job = null
        currentTask = TaskType.Idle
        updateStatus { it.copy(task = TaskType.Idle, statusMessage = "Stopped", isRunning = false) }
    }

    /**
     * Run cleanup for a previous task: craft leftover materials if applicable, then bank everything.
     */
    private suspend fun runCleanup(previousTask: TaskType) {
        if (previousTask is TaskType.Idle) return

        try {
            val char = helper.refreshCharacter(characterName)

            // Wait for any active cooldown before performing cleanup actions.
            // The task may have been switched while the character was mid-action.
            if (char.cooldown > 0) {
                updateStatus { it.copy(statusMessage = "Waiting for cooldown (${char.cooldown}s)...") }
                helper.waitForCooldown(char.cooldown)
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
                is TaskType.BankWithdraw, is TaskType.BankRecycle,
                is TaskType.InventoryDeposit, is TaskType.InventoryRecycle -> {} // no cleanup
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
            val strategy = task.dropStrategies[it.rawCode] ?: DropStrategy.COOK_AND_USE
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
        while (currentCoroutineContext().isActive) {
            try {
                val result = when (task) {
                    is TaskType.Gather -> gatheringExecutor.executeStep(characterName, task) { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }
                    is TaskType.Fight -> fightingExecutor.executeStep(characterName, task) { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }
                    is TaskType.Craft -> {
                        // Inject current craftedSoFar for both BANK and RECYCLE modes
                        // (RECYCLE uses it to detect the first batch and cap at targetQuantity)
                        val craftTask = if (task.mode == CraftMode.BANK || task.mode == CraftMode.RECYCLE) {
                            task.copy(craftedSoFar = craftedSoFar)
                        } else task

                        craftingExecutor.executeStep(characterName, craftTask) { msg ->
                            logger.log(characterName, msg)
                            updateStatus { it.copy(statusMessage = msg) }
                        }
                    }
                    is TaskType.Idle -> break
                    is TaskType.TaskMaster -> taskMasterExecutor.executeStep(characterName, task) { msg ->
                        logger.log(characterName, msg)
                        updateStatus { it.copy(statusMessage = msg) }
                    }
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
                }

                // Update counters based on result
                when (result) {
                    is StepResult.Gathered -> updateStatus { it.copy(gatherCount = it.gatherCount + 1, lastError = null) }
                    is StepResult.Banked -> updateStatus { it.copy(bankTrips = it.bankTrips + 1, lastError = null) }
                    is StepResult.CraftedAndBanked -> updateStatus { it.copy(
                        craftCount = it.craftCount + result.craftCount,
                        bankTrips = it.bankTrips + 1,
                        lastError = null
                    )}
                    is StepResult.FightWon -> updateStatus { it.copy(fightCount = it.fightCount + 1, lastError = null) }
                    is StepResult.FightLost -> {
                        updateStatus { it.copy(lastError = result.message) }
                        delay(5.seconds) // Brief pause after a loss
                    }
                    is StepResult.Rested -> updateStatus { it.copy(lastError = null) }
                    is StepResult.Waiting -> delay(3.seconds)
                    is StepResult.Error -> {
                        updateStatus { it.copy(statusMessage = "Error: ${result.message}", lastError = result.message) }
                        delay(10.seconds)
                    }

                    // Crafting-specific results
                    is StepResult.Crafted -> {
                        craftedSoFar += result.count
                        updateStatus { it.copy(
                            craftCount = it.craftCount + result.count,
                            recycleCount = it.recycleCount + result.recycled,
                            lastError = null
                        )}
                    }
                    is StepResult.OutOfMaterials -> {
                        logger.log(characterName, "Out of materials for crafting task")
                        revertToPreviousTask()
                        break
                    }
                    is StepResult.CraftTaskComplete -> {
                        logger.log(characterName, "Crafting task complete!")
                        revertToPreviousTask()
                        break
                    }

                    // Quick bank/inventory task results
                    is StepResult.QuickTaskComplete -> {
                        logger.log(characterName, "Quick task complete!")
                        revertToPreviousTask()
                        break
                    }

                    // Task master results
                    is StepResult.TaskMasterTaskCompleted -> {
                        updateStatus { it.copy(tasksCompleted = it.tasksCompleted + 1, lastError = null) }
                        logger.log(characterName, "Task master task completed! (total: ${_status.value.tasksCompleted})")
                        // Loop continues — will accept next task on next step
                    }
                    is StepResult.TaskMasterTaskCancelled -> {
                        updateStatus { it.copy(lastError = null) }
                        logger.log(characterName, "Task cancelled, will accept new one")
                        // Loop continues — will accept next task on next step
                    }
                    is StepResult.TaskMasterNoViableTask -> {
                        logger.log(characterName, "No viable monster task found (exhausted attempts or no tasks_coins)")
                        revertToPreviousTask()
                        break
                    }
                }

            } catch (e: CancellationException) {
                throw e // Propagate cancellation
            } catch (e: ArtifactsApiException) {
                val msg = "API Error ${e.errorCode}: ${e.message}"
                logger.log(characterName, msg)
                updateStatus { it.copy(statusMessage = msg, lastError = msg) }
                when (e.errorCode) {
                    486 -> delay(5.seconds)  // Cooldown
                    429 -> delay(15.seconds) // Rate limit
                    else -> delay(10.seconds)
                }
            } catch (e: Exception) {
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
    private suspend fun revertToPreviousTask() {
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
            is TaskType.InventoryDeposit, is TaskType.InventoryRecycle -> {} // no extra cleanup needed
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

    private fun describeTask(task: TaskType): String {
        return when (task) {
            is TaskType.Gather -> "gather ${task.resourceName} (${task.skill})"
            is TaskType.Fight -> "fight ${task.monsterName}"
            is TaskType.Craft -> "craft ${task.itemName} (${task.skill})"
            is TaskType.TaskMaster -> "task master (${task.type})"
            is TaskType.BankWithdraw -> "withdraw ${task.quantity}x ${task.itemName} from bank"
            is TaskType.BankRecycle -> "recycle ${task.quantity}x ${task.itemName} (bank)"
            is TaskType.InventoryDeposit -> "deposit ${task.quantity}x ${task.itemName} to bank"
            is TaskType.InventoryRecycle -> "recycle ${task.quantity}x ${task.itemName} (inventory)"
            is TaskType.Idle -> "idle"
        }
    }

    private fun updateStatus(transform: (RunnerStatus) -> RunnerStatus) {
        _status.value = transform(_status.value)
    }
}
