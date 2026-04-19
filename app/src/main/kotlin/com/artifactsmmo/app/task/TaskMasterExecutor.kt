package com.artifactsmmo.app.task

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.client.utils.CharacterUtils

/**
 * Executes task master loops (items or monsters).
 *
 * Lifecycle per task:
 *   1. Move to task master -> accept task (or resume existing)
 *   2. Determine how to fulfill (gather, craft, or fight)
 *   3. Execute fulfillment loop (gather/craft/fight)
 *   4. Return to task master -> trade items / confirm kills -> complete
 *   5. Accept next task -> repeat
 *
 * For item tasks, handles multi-trip inventory management:
 *   - Gather what fits in inventory
 *   - If crafting is needed, craft at workshop
 *   - Go to task master and trade partial progress
 *   - Repeat until task total is met
 *
 * For monster tasks:
 *   - Fight loop until taskProgress >= taskTotal (game tracks kills)
 *   - Return to task master -> complete
 */
class TaskMasterExecutor(
    private val helper: ActionHelper,
    private val gatheringExecutor: GatheringExecutor,
    private val fightingExecutor: FightingExecutor
) {
    /** Track last upgrade check time per character (epoch millis). */
    private val lastUpgradeCheck = mutableMapOf<String, Long>()
    private val upgradeCheckIntervalMs = 5 * 60 * 1000L

    /**
     * Execute a single step of the task master loop.
     * This is a high-level step — it may internally do many actions
     * (e.g., an entire gather-trade cycle) before returning.
     */
    suspend fun executeStep(
        characterName: String,
        task: TaskType.TaskMaster,
        onStatus: (String) -> Unit
    ): StepResult {
        var char = helper.refreshCharacter(characterName)

        // Check if we have an active task already
        if (char.task.isEmpty()) {
            // No active task — go accept one
            return acceptNewTask(characterName, char, task.type, onStatus)
        }

        // We have an active task — fulfill it
        return when (char.taskType) {
            "items" -> fulfillItemTask(characterName, char, onStatus)
            "monsters" -> fulfillMonsterTask(characterName, char, onStatus)
            else -> {
                onStatus("Unknown task type: ${char.taskType}, cancelling...")
                cancelAndRetry(characterName, task.type, onStatus)
            }
        }
    }

    // ── Task Acceptance ──

    private suspend fun acceptNewTask(
        characterName: String,
        char: Character,
        type: String,
        onStatus: (String) -> Unit
    ): StepResult {
        val taskMaster = helper.findNearestTasksMaster(char, type)
            ?: return StepResult.Error("No $type task master found on map")

        onStatus("Moving to $type task master...")
        helper.moveTo(characterName, taskMaster.x, taskMaster.y)

        onStatus("Accepting new task...")
        val taskData = helper.acceptTask(characterName)
        val t = taskData.task
        onStatus("Task accepted: ${t.total}x ${t.code} (${t.type})")

        return StepResult.Waiting // Next step will start fulfillment
    }

    // ── Item Task Fulfillment ──

    /**
     * Fulfill an item task. This handles the full cycle:
     * check stock -> gather if needed -> craft if needed -> trade -> complete
     */
    private suspend fun fulfillItemTask(
        characterName: String,
        char: Character,
        onStatus: (String) -> Unit
    ): StepResult {
        val taskItemCode = char.task
        val remaining = char.taskTotal - char.taskProgress

        onStatus("Item task: ${char.taskProgress}/${char.taskTotal} ${taskItemCode}")

        if (remaining <= 0) {
            // Task should be complete — go complete it
            return completeCurrentTask(characterName, char, onStatus)
        }

        // Check how many we already have (inventory + bank)
        val inInventory = helper.getItemQuantity(char, taskItemCode)
        val inBank = helper.getBankItemQuantity(taskItemCode)
        val totalOnHand = inInventory + inBank

        if (totalOnHand >= remaining) {
            // We have enough — withdraw from bank if needed and trade
            return tradeItems(characterName, char, taskItemCode, remaining, onStatus)
        }

        // Need to gather more — figure out how
        val source = try {
            helper.findTaskItemSource(taskItemCode)
        } catch (_: Exception) { null }

        if (source == null) {
            onStatus("Can't determine how to obtain $taskItemCode, cancelling task...")
            return cancelAndRetry(characterName, "items", onStatus)
        }

        // Check if character has the required gathering skill level
        val charSkillLevel = CharacterUtils.getSkillLevel(char, source.gatherSkill) ?: 0
        if (charSkillLevel < source.gatherLevel) {
            onStatus("${source.gatherSkill} level too low (have $charSkillLevel, need ${source.gatherLevel}), cancelling task...")
            return cancelAndRetry(characterName, "items", onStatus)
        }

        // If crafting is needed, check crafting skill level too
        if (source.needsCrafting && source.craftSkill != null) {
            val craftLevel = CharacterUtils.getSkillLevel(char, source.craftSkill) ?: 0
            if (craftLevel < source.craftLevel) {
                onStatus("${source.craftSkill} level too low (have $craftLevel, need ${source.craftLevel}), cancelling task...")
                return cancelAndRetry(characterName, "items", onStatus)
            }
        }

        // First: if we have items in the bank, withdraw and trade them in batches
        if (inBank > 0 && inInventory == 0) {
            return tradeItems(characterName, char, taskItemCode, inBank.coerceAtMost(remaining), onStatus)
        }

        // If inventory has some items but isn't full, and we still need to gather more,
        // keep gathering until inventory is full or we have enough
        val inventoryFull = helper.isInventoryFull(char)
        if (inInventory > 0 && (inventoryFull || inInventory >= remaining)) {
            return tradeItems(characterName, char, taskItemCode, inInventory.coerceAtMost(remaining), onStatus)
        }

        // Gather a batch (keep gathering until inventory full or have enough)
        return gatherBatchForTask(characterName, char, source, taskItemCode, remaining, onStatus)
    }

    /**
     * Gather a batch of items for the task, craft if needed, then trade.
     */
    private suspend fun gatherBatchForTask(
        characterName: String,
        char: Character,
        source: ActionHelper.TaskItemSource,
        taskItemCode: String,
        remaining: Int,
        onStatus: (String) -> Unit
    ): StepResult {
        var currentChar = char

        // Equip best tool for the gathering skill
        onStatus("Checking tool for ${source.gatherSkill}...")
        currentChar = helper.ensureToolEquipped(characterName, source.gatherSkill)

        // Check for tool upgrade periodically
        val now = System.currentTimeMillis()
        val lastCheck = lastUpgradeCheck[characterName] ?: 0L
        if (now - lastCheck >= upgradeCheckIntervalMs) {
            lastUpgradeCheck[characterName] = now
            currentChar = tryUpgradeTool(characterName, currentChar, source.gatherSkill, onStatus)
        }

        // Calculate how many raw items we need to gather
        val rawNeeded = if (source.needsCrafting) {
            // Need rawPerCraft raw items per outputPerCraft task items
            val taskItemsNeeded = remaining
            val craftsNeeded = (taskItemsNeeded + source.outputPerCraft - 1) / source.outputPerCraft
            craftsNeeded * source.rawPerCraft
        } else {
            remaining
        }

        // How many can we fit in inventory?
        val currentItems = currentChar.inventory.sumOf { it.quantity }
        val freeSlots = currentChar.inventoryMaxItems - currentItems
        val batchRaw = rawNeeded.coerceAtMost(freeSlots)

        if (batchRaw <= 0) {
            // Inventory is full — need to bank safe items first
            onStatus("Inventory full, banking safe items...")
            currentChar = helper.bankDepositAll(characterName)
            return StepResult.Banked
        }

        // Move to resource and gather
        val resourceMap = helper.findNearest(currentChar, "resource", source.resourceCode)
            ?: return StepResult.Error("No ${source.resourceCode} locations found on map")

        if (!helper.isAt(currentChar, resourceMap.x, resourceMap.y)) {
            onStatus("Moving to ${source.resourceName}...")
            currentChar = helper.moveTo(characterName, resourceMap.x, resourceMap.y)
        }

        // Gather one action
        val rawInInventory = helper.getItemQuantity(currentChar, source.rawItemCode)
        val totalItems = currentChar.inventory.sumOf { it.quantity }
        onStatus("Gathering ${source.resourceName} for task... (${rawInInventory}/${batchRaw} raw, Inv: $totalItems/${currentChar.inventoryMaxItems})")

        val gatherResult = try {
            helper.gather(characterName)
        } catch (e: ArtifactsApiException) {
            if (e.errorCode == 486) return StepResult.Waiting
            throw e
        }

        val drops = gatherResult.details.items.joinToString(", ") { "${it.quantity}x ${it.code}" }
        onStatus("Gathered: $drops (+${gatherResult.details.xp} XP)")

        // Check if we have enough raw items now
        currentChar = helper.refreshCharacter(characterName)
        val currentRaw = helper.getItemQuantity(currentChar, source.rawItemCode)
        val inventoryFull = helper.isInventoryFull(currentChar)
        val updatedRemaining = currentChar.taskTotal - currentChar.taskProgress

        // Only go trade when inventory is full or we have enough for the entire remaining task
        val haveEnoughForTask = if (source.needsCrafting) {
            val possibleCrafts = currentRaw / source.rawPerCraft
            val possibleOutput = possibleCrafts * source.outputPerCraft
            possibleOutput >= updatedRemaining
        } else {
            currentRaw >= updatedRemaining
        }

        if (inventoryFull || haveEnoughForTask) {
            if (source.needsCrafting) {
                // Need at least one craft batch worth
                if (currentRaw >= source.rawPerCraft) {
                    return craftAndTrade(characterName, source, taskItemCode, onStatus)
                }
                // Inventory full but not enough to craft — trade any finished items we have
                val finishedQty = helper.getItemQuantity(currentChar, taskItemCode)
                if (finishedQty > 0) {
                    return tradeItems(characterName, currentChar, taskItemCode, finishedQty.coerceAtMost(updatedRemaining), onStatus)
                }
                // Inventory full with junk — bank it
                onStatus("Inventory full, banking to make room...")
                helper.bankDepositAll(characterName)
                return StepResult.Banked
            } else {
                // Trade raw items directly
                val tradeQty = helper.getItemQuantity(currentChar, taskItemCode)
                if (tradeQty > 0) {
                    return tradeItems(characterName, currentChar, taskItemCode, tradeQty.coerceAtMost(updatedRemaining), onStatus)
                }
            }
        }

        return StepResult.Gathered(gatherResult.details.xp, gatherResult.details.items.map { it.code to it.quantity })
    }

    /**
     * Craft raw items into the task item, then trade with the task master.
     */
    private suspend fun craftAndTrade(
        characterName: String,
        source: ActionHelper.TaskItemSource,
        taskItemCode: String,
        onStatus: (String) -> Unit
    ): StepResult {
        var char = helper.refreshCharacter(characterName)

        // Move to the crafting workshop
        val craftSkill = source.craftSkill ?: return StepResult.Error("No craft skill for $taskItemCode")
        val workshop = helper.findNearestWorkshop(char, craftSkill)
            ?: return StepResult.Error("No $craftSkill workshop found")

        onStatus("Moving to $craftSkill workshop...")
        char = helper.moveTo(characterName, workshop.x, workshop.y)

        // Craft as many as possible from raw materials in inventory
        char = helper.refreshCharacter(characterName)
        val rawQty = helper.getItemQuantity(char, source.rawItemCode)
        val craftCount = rawQty / source.rawPerCraft

        if (craftCount > 0) {
            onStatus("Crafting ${craftCount}x $taskItemCode...")
            helper.craft(characterName, taskItemCode, craftCount)
        }

        // Now trade the crafted items
        char = helper.refreshCharacter(characterName)
        val taskItemQty = helper.getItemQuantity(char, taskItemCode)
        val remaining = char.taskTotal - char.taskProgress

        if (taskItemQty > 0) {
            return tradeItems(characterName, char, taskItemCode, taskItemQty.coerceAtMost(remaining), onStatus)
        }

        return StepResult.Waiting
    }

    /**
     * Trade items with the task master. Withdraws from bank if needed.
     */
    private suspend fun tradeItems(
        characterName: String,
        char: Character,
        taskItemCode: String,
        quantity: Int,
        onStatus: (String) -> Unit
    ): StepResult {
        var currentChar = char
        val remaining = currentChar.taskTotal - currentChar.taskProgress

        // Ensure we have the items in inventory
        val inInventory = helper.getItemQuantity(currentChar, taskItemCode)
        val tradeQty = quantity.coerceAtMost(remaining)

        if (inInventory < tradeQty) {
            // Need to withdraw from bank — but respect inventory capacity
            currentChar = helper.refreshCharacter(characterName)
            val totalItems = currentChar.inventory.sumOf { it.quantity }
            val freeSpace = currentChar.inventoryMaxItems - totalItems

            // If inventory is nearly full, bank safe items first to make room
            if (freeSpace < (tradeQty - inInventory)) {
                currentChar = helper.bankDepositAll(characterName)
                val newTotal = currentChar.inventory.sumOf { it.quantity }
                val newInInv = helper.getItemQuantity(currentChar, taskItemCode)
                val newFreeSpace = currentChar.inventoryMaxItems - newTotal

                // Cap withdrawal to what fits
                val canWithdraw = (tradeQty - newInInv).coerceAtMost(newFreeSpace)
                if (canWithdraw > 0) {
                    onStatus("Withdrawing ${canWithdraw}x $taskItemCode from bank...")
                    helper.bankWithdrawItems(characterName, listOf(SimpleItem(taskItemCode, canWithdraw)))
                }
            } else {
                val deficit = (tradeQty - inInventory).coerceAtMost(freeSpace)
                if (deficit > 0) {
                    onStatus("Withdrawing ${deficit}x $taskItemCode from bank...")
                    helper.bankWithdrawItems(characterName, listOf(SimpleItem(taskItemCode, deficit)))
                }
            }
        }

        // Move to task master — trade only what we actually have in inventory
        currentChar = helper.refreshCharacter(characterName)
        val actualTradeQty = helper.getItemQuantity(currentChar, taskItemCode)
            .coerceAtMost(currentChar.taskTotal - currentChar.taskProgress)

        if (actualTradeQty <= 0) {
            return StepResult.Waiting // Nothing to trade this trip
        }

        val taskMaster = helper.findNearestTasksMaster(currentChar, currentChar.taskType)
            ?: return StepResult.Error("No ${currentChar.taskType} task master found")

        onStatus("Moving to task master to trade ${actualTradeQty}x $taskItemCode...")
        helper.moveTo(characterName, taskMaster.x, taskMaster.y)

        // Trade
        onStatus("Trading ${actualTradeQty}x $taskItemCode...")
        currentChar = helper.tradeTask(characterName, taskItemCode, actualTradeQty)

        // Check if task is complete
        val newRemaining = currentChar.taskTotal - currentChar.taskProgress
        if (newRemaining <= 0) {
            return completeCurrentTask(characterName, currentChar, onStatus)
        }

        onStatus("Traded! ${currentChar.taskProgress}/${currentChar.taskTotal} $taskItemCode")
        return StepResult.Banked // Signals a trip was made
    }

    // ── Monster Task Fulfillment ──

    /**
     * Fulfill a monster task. Runs a single fight step, or completes the task
     * if kill count is reached.
     */
    private suspend fun fulfillMonsterTask(
        characterName: String,
        char: Character,
        onStatus: (String) -> Unit
    ): StepResult {
        val monsterCode = char.task
        val remaining = char.taskTotal - char.taskProgress

        if (remaining <= 0) {
            return completeCurrentTask(characterName, char, onStatus)
        }

        onStatus("Monster task: ${char.taskProgress}/${char.taskTotal} $monsterCode")

        // Create a temporary fight task and delegate to the fighting executor
        val fightTask = TaskType.Fight(monsterCode, monsterCode)

        // Check if character can fight this monster (basic level check)
        // The fighting executor handles HP, healing, inventory management
        return fightingExecutor.executeStep(characterName, fightTask) { msg ->
            onStatus(msg)
        }
    }

    // ── Task Completion ──

    private suspend fun completeCurrentTask(
        characterName: String,
        char: Character,
        onStatus: (String) -> Unit
    ): StepResult {
        // Move to the task master
        val taskMaster = helper.findNearestTasksMaster(char, char.taskType)
            ?: return StepResult.Error("No ${char.taskType} task master found")

        if (!helper.isAt(char, taskMaster.x, taskMaster.y)) {
            onStatus("Moving to task master to complete task...")
            helper.moveTo(characterName, taskMaster.x, taskMaster.y)
        }

        onStatus("Completing task...")
        val reward = helper.completeTask(characterName)
        val rewardDesc = buildString {
            if (reward.rewards.gold > 0) append("${reward.rewards.gold} gold")
            if (reward.rewards.items.isNotEmpty()) {
                if (isNotEmpty()) append(", ")
                append(reward.rewards.items.joinToString(", ") { "${it.quantity}x ${it.code}" })
            }
        }
        onStatus("Task complete! Rewards: $rewardDesc")

        return StepResult.TaskMasterTaskCompleted
    }

    // ── Cancel & Retry ──

    private suspend fun cancelAndRetry(
        characterName: String,
        type: String,
        onStatus: (String) -> Unit
    ): StepResult {
        val char = helper.refreshCharacter(characterName)

        // Move to task master to cancel
        val taskMaster = helper.findNearestTasksMaster(char, type)
            ?: return StepResult.Error("No $type task master found")

        if (!helper.isAt(char, taskMaster.x, taskMaster.y)) {
            onStatus("Moving to task master to cancel task...")
            helper.moveTo(characterName, taskMaster.x, taskMaster.y)
        }

        onStatus("Cancelling current task...")
        helper.cancelTask(characterName)

        return StepResult.TaskMasterTaskCancelled
    }

    // ── Tool Upgrade (reused from GatheringExecutor pattern) ──

    private suspend fun tryUpgradeTool(
        characterName: String,
        currentChar: Character,
        skill: String,
        onStatus: (String) -> Unit
    ): Character {
        // Check for ready-made tool in bank first
        val readyMade = try {
            helper.findReadyMadeToolInBank(currentChar, skill)
        } catch (_: Exception) { null }

        if (readyMade != null) {
            onStatus("Found ${readyMade.tool.name} in bank! Withdrawing...")
            helper.bankWithdrawItems(characterName, listOf(SimpleItem(readyMade.tool.code, 1)))

            var char = helper.refreshCharacter(characterName)
            if (char.weaponSlot.isNotEmpty()) {
                val oldTool = char.weaponSlot
                char = helper.unequip(characterName, "weapon")
                helper.bankDepositItems(characterName, listOf(SimpleItem(oldTool, 1)))
            }

            char = helper.equip(characterName, readyMade.tool.code, "weapon")
            onStatus("Equipped ${readyMade.tool.name}!")
            return char
        }

        // Check for craftable upgrade
        val upgrade = try {
            helper.findBestCraftableToolFromBank(currentChar, skill)
        } catch (_: Exception) { null } ?: return currentChar

        onStatus("Upgrade available: ${upgrade.tool.name}! Withdrawing materials...")
        var char = helper.bankWithdrawItems(characterName, upgrade.ingredients)

        val workshop = helper.findNearestWorkshop(char, upgrade.craftSkill)
        if (workshop == null) {
            onStatus("No ${upgrade.craftSkill} workshop found, skipping upgrade")
            helper.bankDepositItems(characterName, upgrade.ingredients)
            return helper.refreshCharacter(characterName)
        }

        onStatus("Crafting ${upgrade.tool.name} at ${upgrade.craftSkill} workshop...")
        char = helper.moveTo(characterName, workshop.x, workshop.y)
        helper.craft(characterName, upgrade.tool.code, 1)

        char = helper.refreshCharacter(characterName)
        if (char.weaponSlot.isNotEmpty()) {
            char = helper.unequip(characterName, "weapon")
        }

        char = helper.equip(characterName, upgrade.tool.code, "weapon")
        onStatus("Equipped ${upgrade.tool.name}!")
        return char
    }
}
