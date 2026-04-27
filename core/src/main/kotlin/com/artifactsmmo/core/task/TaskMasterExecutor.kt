package com.artifactsmmo.core.task

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

        // We have an active task — if it's a different type than requested, cancel it first
        if (char.taskType != task.type) {
            return cancelCurrentAndAcceptNew(characterName, char, task.type, onStatus)
        }

        // We have an active task of the right type — fulfill it
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

        // For monster tasks, run the simulation/cancel loop
        if (t.type == "monsters") {
            val simResult = simulateAndFilterMonsterTask(characterName, type, onStatus)
            if (simResult != null) return simResult
            // null means no viable task found — signal revert
            return StepResult.TaskMasterNoViableTask
        }

        return StepResult.Waiting // Next step will start fulfillment
    }

    // ── Cross-Type Task Switch ──

    /**
     * The character has an active task of a different type than requested.
     * Cancel it at the appropriate task master, then accept a new one of [newType].
     */
    private suspend fun cancelCurrentAndAcceptNew(
        characterName: String,
        char: Character,
        newType: String,
        onStatus: (String) -> Unit
    ): StepResult {
        val currentType = char.taskType
        onStatus("Cancelling $currentType task to switch to $newType...")

        // Cancelling a task always consumes a tasks_coin — ensure one is available first.
        if (!ensureTaskCoinInInventory(characterName, onStatus)) {
            return StepResult.Error(
                "Cannot cancel $currentType task: no tasks_coin in inventory or bank. " +
                "Obtain a tasks_coin to switch task types."
            )
        }

        val currentTaskMaster = helper.findNearestTasksMaster(char, currentType)
            ?: return StepResult.Error("No $currentType task master found to cancel current task")

        if (!helper.isAt(char, currentTaskMaster.x, currentTaskMaster.y)) {
            onStatus("Moving to $currentType task master to cancel...")
            helper.moveTo(characterName, currentTaskMaster.x, currentTaskMaster.y)
        }

        onStatus("Cancelling $currentType task...")
        helper.cancelTask(characterName)

        val updatedChar = helper.refreshCharacter(characterName)
        return acceptNewTask(characterName, updatedChar, newType, onStatus)
    }

    // ── Monster Task Simulation / Cancel Loop ──

    /**
     * Ensure a tasks_coin is in the character's inventory.
     * Checks inventory first; if absent, tries to withdraw one from the bank.
     * Returns true if a coin is (or was made) available, false if none anywhere.
     */
    private suspend fun ensureTaskCoinInInventory(
        characterName: String,
        onStatus: (String) -> Unit
    ): Boolean {
        val char = helper.refreshCharacter(characterName)
        if (helper.getItemQuantity(char, "tasks_coin") >= 1) return true

        val inBank = helper.getBankItemQuantity("tasks_coin")
        if (inBank <= 0) {
            onStatus("No tasks_coins available in inventory or bank")
            return false
        }

        onStatus("Withdrawing 1x tasks_coin from bank...")
        helper.bankWithdrawItems(characterName, listOf(SimpleItem("tasks_coin", 1)))
        return true
    }

    /**
     * After a monster task has just been accepted, simulate the fight and
     * optionally cancel + re-accept up to 5 times if win rate is below 90%.
     *
     * Returns:
     *  - [StepResult.Waiting]  if a viable task was found (win rate >= 0.90) or simulation failed
     *  - null                  if no viable task could be found (no coins or attempts exhausted)
     */
    private suspend fun simulateAndFilterMonsterTask(
        characterName: String,
        type: String,
        onStatus: (String) -> Unit
    ): StepResult? {
        val maxAttempts = 5
        repeat(maxAttempts) { attempt ->
            val char = helper.refreshCharacter(characterName)
            val monsterCode = char.task

            onStatus("Simulating fight vs $monsterCode (attempt ${attempt + 1}/$maxAttempts)...")
            val simData = try {
                helper.simulateFight(characterName, monsterCode, iterations = 100)
            } catch (e: Exception) {
                onStatus("Simulation failed (${e.message}), proceeding with task")
                return StepResult.Waiting
            }

            val winRate = simData.winrate
            onStatus("Win rate vs $monsterCode: ${"%.0f".format(winRate * 100)}%")

            if (winRate >= 0.90) {
                onStatus("Win rate acceptable, proceeding with $monsterCode task")
                return StepResult.Waiting
            }

            // Win rate too low (<90%) — need a coin to cancel
            onStatus("Win rate too low (${"%,.0f".format(winRate * 100)}%), attempting to cancel task...")
            if (!ensureTaskCoinInInventory(characterName, onStatus)) {
                return null // No coin available — give up
            }

            // Move to task master (may already be there) and cancel
            val updatedChar = helper.refreshCharacter(characterName)
            val taskMaster = helper.findNearestTasksMaster(updatedChar, type)
                ?: return StepResult.Error("No $type task master found on map")

            if (!helper.isAt(updatedChar, taskMaster.x, taskMaster.y)) {
                onStatus("Moving to task master to cancel...")
                helper.moveTo(characterName, taskMaster.x, taskMaster.y)
            }

            onStatus("Cancelling task (low win rate)...")
            helper.cancelTask(characterName)

            onStatus("Accepting new task...")
            val taskData = helper.acceptTask(characterName)
            val t = taskData.task
            onStatus("New task accepted: ${t.total}x ${t.code} (${t.type})")
        }

        onStatus("Exhausted $maxAttempts attempts, no viable monster task found")
        return null
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
     *
     * For multi-ingredient recipes (e.g. steel bar = 3 iron_ore + 7 coal), the loop
     * works through each ingredient in recipe order: once we have enough of ingredient N
     * for [craftsTarget] crafts, it moves on to ingredient N+1, then crafts when all
     * ingredients are satisfied.
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

        // Use allIngredients when available; fall back to a single-item list from legacy fields
        val ingredients = source.allIngredients.ifEmpty {
            listOf(
                ActionHelper.TaskItemIngredient(
                    rawItemCode  = source.rawItemCode,
                    rawPerCraft  = source.rawPerCraft,
                    gatherSkill  = source.gatherSkill,
                    resourceCode = source.resourceCode,
                    resourceName = source.resourceName,
                    gatherLevel  = source.gatherLevel
                )
            )
        }

        // ── How many crafts to target this trip ──
        val craftsNeeded     = if (source.needsCrafting)
            (remaining + source.outputPerCraft - 1) / source.outputPerCraft
        else remaining

        val currentItems     = currentChar.inventory.sumOf { it.quantity }
        var freeSlots        = currentChar.inventoryMaxItems - currentItems
        val rawPerCraftTotal = if (source.needsCrafting) ingredients.sumOf { it.rawPerCraft } else 1
        val craftsFit        = if (rawPerCraftTotal > 0) (freeSlots / rawPerCraftTotal).coerceAtLeast(1) else 1
        val craftsTarget     = craftsNeeded.coerceAtMost(craftsFit)

        // ── Combined bank trip: deposit side-drop junk + withdraw needed ingredients ──
        // Side drops (gems, shells, algae, sap, etc.) accumulate in inventory across gather/craft
        // cycles and are never consumed by the task. Deposit them whenever we visit the bank, or
        // proactively once they occupy enough slots to threaten inventory capacity.
        val ingredientCodes = ingredients.map { it.rawItemCode }.toSet()
        val junkToDeposit = buildList {
            for (slot in currentChar.inventory) {
                if (slot.quantity > 0 &&
                    slot.code != taskItemCode &&
                    slot.code !in ingredientCodes &&
                    helper.shouldDepositItem(currentChar, slot.code)
                ) add(SimpleItem(slot.code, slot.quantity))
            }
        }

        val toWithdraw = if (source.needsCrafting) {
            ingredients.mapNotNull { ing ->
                val have    = helper.getItemQuantity(currentChar, ing.rawItemCode)
                val need    = ing.rawPerCraft * craftsTarget
                val inBank  = helper.getBankItemQuantity(ing.rawItemCode)
                val deficit = (need - have).coerceAtLeast(0)
                val qty     = deficit.coerceAtMost(inBank)
                if (qty > 0) SimpleItem(ing.rawItemCode, qty) else null
            }
        } else emptyList()

        // Make a bank trip if we have ingredients to withdraw, OR junk is occupying enough
        // slots to meaningfully crowd out task materials (threshold: 5 slots or less free
        // than one craft's worth of raw items).
        val junkSlotCount = junkToDeposit.sumOf { it.quantity }
        val needsBankTrip = toWithdraw.isNotEmpty() ||
            (junkSlotCount >= 5) ||
            (junkSlotCount > 0 && freeSlots < rawPerCraftTotal)

        if (needsBankTrip) {
            if (junkToDeposit.isNotEmpty()) {
                onStatus("Depositing side drops: ${junkToDeposit.joinToString { "${it.quantity}x ${it.code}" }}...")
                currentChar = helper.bankDepositItems(characterName, junkToDeposit)
                freeSlots = currentChar.inventoryMaxItems - currentChar.inventory.sumOf { it.quantity }
            }
            if (toWithdraw.isNotEmpty()) {
                onStatus("Withdrawing banked ingredients: ${toWithdraw.joinToString { "${it.quantity}x ${it.code}" }}...")
                currentChar = helper.bankWithdrawItems(characterName, toWithdraw)
                freeSlots = currentChar.inventoryMaxItems - currentChar.inventory.sumOf { it.quantity }
            }
        }

        // ── Find the first ingredient we're still short on ──
        val activeIngredient = if (source.needsCrafting) {
            ingredients.firstOrNull { ing ->
                helper.getItemQuantity(currentChar, ing.rawItemCode) < ing.rawPerCraft * craftsTarget
            }
        } else {
            ingredients.firstOrNull()
        }

        // All crafted ingredients satisfied → go craft immediately
        if (source.needsCrafting && activeIngredient == null) {
            return craftAndTrade(characterName, source, taskItemCode, onStatus)
        }

        val ing = activeIngredient ?: ingredients.first()

        // ── Inventory capacity check ──
        val ingHave   = helper.getItemQuantity(currentChar, ing.rawItemCode)
        val ingNeeded = if (source.needsCrafting) ing.rawPerCraft * craftsTarget else remaining
        val batchRaw  = (ingNeeded - ingHave).coerceAtLeast(0).coerceAtMost(freeSlots)

        if (batchRaw <= 0 && freeSlots <= 0) {
            onStatus("Inventory full, banking safe items...")
            currentChar = helper.bankDepositAll(characterName)
            return StepResult.Banked
        }

        // ── Equip best tool for this ingredient's skill ──
        onStatus("Checking tool for ${ing.gatherSkill}...")
        currentChar = helper.ensureToolEquipped(characterName, ing.gatherSkill)

        // Periodic tool upgrade check
        val now       = System.currentTimeMillis()
        val lastCheck = lastUpgradeCheck[characterName] ?: 0L
        if (now - lastCheck >= upgradeCheckIntervalMs) {
            lastUpgradeCheck[characterName] = now
            currentChar = tryUpgradeTool(characterName, currentChar, ing.gatherSkill, onStatus)
        }

        // ── Move to resource and gather ──
        val resourceMap = helper.findNearest(currentChar, "resource", ing.resourceCode)
            ?: return StepResult.Error("No ${ing.resourceCode} locations found on map")

        if (!helper.isAt(currentChar, resourceMap.x, resourceMap.y)) {
            onStatus("Moving to ${ing.resourceName}...")
            currentChar = helper.moveTo(characterName, resourceMap.x, resourceMap.y)
        }

        // Build a progress string that covers all ingredients for multi-ingredient recipes
        val totalItems = currentChar.inventory.sumOf { it.quantity }
        val progressStr = if (ingredients.size > 1) {
            ingredients.joinToString(", ") { i ->
                "${helper.getItemQuantity(currentChar, i.rawItemCode)}/${i.rawPerCraft * craftsTarget} ${i.rawItemCode}"
            }
        } else {
            "${helper.getItemQuantity(currentChar, ing.rawItemCode)}/$batchRaw"
        }
        onStatus("Gathering ${ing.resourceName} for task... ($progressStr, Inv: $totalItems/${currentChar.inventoryMaxItems})")

        val gatherResult = try {
            helper.gather(characterName)
        } catch (e: ArtifactsApiException) {
            if (e.errorCode == 486) return StepResult.Waiting
            throw e
        }

        val drops = gatherResult.details.items.joinToString(", ") { "${it.quantity}x ${it.code}" }
        onStatus("Gathered: $drops (+${gatherResult.details.xp} XP)")

        // ── Re-evaluate after gather ──
        currentChar = helper.refreshCharacter(characterName)
        val inventoryFull    = helper.isInventoryFull(currentChar)
        val updatedRemaining = currentChar.taskTotal - currentChar.taskProgress

        if (source.needsCrafting) {
            val canCraftAtLeastOne = ingredients.all { i ->
                helper.getItemQuantity(currentChar, i.rawItemCode) >= i.rawPerCraft
            }
            val possibleCrafts = ingredients.minOf { i ->
                helper.getItemQuantity(currentChar, i.rawItemCode) / i.rawPerCraft
            }
            val haveEnoughForTask = possibleCrafts * source.outputPerCraft >= updatedRemaining

            if (inventoryFull || haveEnoughForTask) {
                if (canCraftAtLeastOne) {
                    return craftAndTrade(characterName, source, taskItemCode, onStatus)
                }
                // Full but missing at least one ingredient — trade finished items if any, else bank
                val finishedQty = helper.getItemQuantity(currentChar, taskItemCode)
                if (finishedQty > 0) {
                    return tradeItems(characterName, currentChar, taskItemCode, finishedQty.coerceAtMost(updatedRemaining), onStatus)
                }
                onStatus("Inventory full, banking to make room...")
                helper.bankDepositAll(characterName)
                return StepResult.Banked
            }
        } else {
            val currentRaw = helper.getItemQuantity(currentChar, source.rawItemCode)
            if (inventoryFull || currentRaw >= updatedRemaining) {
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

        // Craft as many as possible — limited by whichever ingredient we have least of
        char = helper.refreshCharacter(characterName)
        val craftCount = if (source.allIngredients.isNotEmpty()) {
            source.allIngredients.minOf { ing ->
                helper.getItemQuantity(char, ing.rawItemCode) / ing.rawPerCraft
            }
        } else {
            helper.getItemQuantity(char, source.rawItemCode) / source.rawPerCraft
        }

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
                // Only deposit back to the bank if it's a gathering tool — never deposit combat weapons
                val oldItem = runCatching { helper.getItem(oldTool) }.getOrNull()
                if (oldItem?.subtype == "tool") {
                    helper.bankDepositItems(characterName, listOf(SimpleItem(oldTool, 1)))
                }
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
