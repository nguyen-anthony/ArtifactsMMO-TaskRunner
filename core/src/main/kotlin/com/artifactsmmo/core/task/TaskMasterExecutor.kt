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
    /** How often to check for tool upgrades (10 minutes). */
    private val upgradeCheckIntervalMs = 10 * 60 * 1000L

    /**
     * Cache of monster codes that have already passed the win-rate simulation gate.
     * Once a monster code is accepted as viable, we skip re-simulating it on any
     * subsequent task assignment for the same monster — saving up to 3 simulation
     * calls per repeated task. The cache is per executor instance (per character)
     * and lives for the session; monster viability doesn't change within a session.
     */
    private val acceptedMonsterCodes = mutableSetOf<String>()

    /**
     * Monster codes that have been blacklisted due to too many consecutive deaths.
     * These will be rejected (cancel + re-accept) if assigned again, just like a
     * low win-rate would be. Session-scoped.
     */
    private val blacklistedMonsterCodes = mutableSetOf<String>()

    /**
     * Blacklist a monster code so it will be cancelled if assigned as a task again.
     * Called by [CharacterTaskRunner] when the consecutive death threshold is reached.
     */
    fun blacklistMonster(monsterCode: String) {
        if (monsterCode.isNotEmpty()) {
            blacklistedMonsterCodes.add(monsterCode)
            acceptedMonsterCodes.remove(monsterCode) // Remove from accepted cache too
        }
    }

    /**
     * Cancel the currently active monster task and accept a new one.
     * Called by [CharacterTaskRunner] after too many consecutive deaths.
     * Returns once a new task has been accepted (or a [StepResult] if the cancellation fails).
     */
    suspend fun cancelCurrentMonsterTask(
        characterName: String,
        type: String,
        onStatus: (String) -> Unit
    ) {
        onStatus("Too many consecutive deaths — cancelling monster task...")
        if (!ensureTaskCoinInInventory(characterName, onStatus)) {
            onStatus("No tasks_coin available to cancel task after deaths — stopping")
            return
        }

        val char = helper.refreshCharacter(characterName)
        val taskMaster = helper.findNearestTasksMaster(char, type) ?: run {
            onStatus("No $type task master found — cannot cancel task after deaths")
            return
        }

        if (!helper.isAt(char, taskMaster.x, taskMaster.y)) {
            onStatus("Moving to task master to cancel after deaths...")
            helper.navigateToTile(characterName, taskMaster)
        }

        onStatus("Cancelling task (too many deaths)...")
        helper.cancelTask(characterName)

        onStatus("Accepting new task...")
        val taskData = helper.acceptTask(characterName)
        val t = taskData.task
        onStatus("New task accepted: ${t.total}x ${t.code} (${t.type})")
    }

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
            else -> {                onStatus("Unknown task type: ${char.taskType}, cancelling...")
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
        helper.navigateToTile(characterName, taskMaster)

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
     * optionally cancel + re-accept up to 3 times if win rate is below 90%.
     *
     * Monster codes that have already been accepted as viable are cached for the
     * session — if the same monster is assigned again, simulation is skipped entirely.
     *
     * Returns:
     *  - [StepResult.Waiting]  if a viable task was found or simulation failed gracefully
     *  - null                  if no viable task could be found (no coins or attempts exhausted)
     */
    private suspend fun simulateAndFilterMonsterTask(
        characterName: String,
        type: String,
        onStatus: (String) -> Unit
    ): StepResult? {
        val maxAttempts = 3
        repeat(maxAttempts) { attempt ->
            val char = helper.refreshCharacter(characterName)
            val monsterCode = char.task

            // Skip simulation if we already know this monster is beatable
            if (monsterCode in acceptedMonsterCodes) {
                onStatus("$monsterCode previously verified — skipping simulation")
                optimiseForMonster(characterName, monsterCode, onStatus)
                return StepResult.Waiting
            }

            // Reject immediately if blacklisted due to repeated deaths
            if (monsterCode in blacklistedMonsterCodes) {
                onStatus("$monsterCode is blacklisted (too many deaths) — cancelling task...")
                if (!ensureTaskCoinInInventory(characterName, onStatus)) {
                    return null
                }
                val blChar = helper.refreshCharacter(characterName)
                val blTaskMaster = helper.findNearestTasksMaster(blChar, type)
                    ?: return StepResult.Error("No $type task master found on map")
                if (!helper.isAt(blChar, blTaskMaster.x, blTaskMaster.y)) {
                    onStatus("Moving to task master to cancel blacklisted task...")
                    helper.moveTo(characterName, blTaskMaster.x, blTaskMaster.y)
                }
                onStatus("Cancelling blacklisted task ($monsterCode)...")
                helper.cancelTask(characterName)
                onStatus("Accepting new task...")
                val newTaskData = helper.acceptTask(characterName)
                val newT = newTaskData.task
                onStatus("New task accepted: ${newT.total}x ${newT.code} (${newT.type})")
                return@repeat // Continue to next attempt with the new task
            }

            onStatus("Simulating fight vs $monsterCode (attempt ${attempt + 1}/$maxAttempts)...")
            val simData = try {
                helper.simulateFight(characterName, monsterCode, iterations = 20)
            } catch (e: Exception) {
                onStatus("Simulation failed (${e.message}), proceeding with task")
                acceptedMonsterCodes.add(monsterCode) // treat as viable to avoid retrying
                optimiseForMonster(characterName, monsterCode, onStatus)
                return StepResult.Waiting
            }

            val winRate = simData.winrate
            // Local simulator results have an empty results list (synthetic response).
            // If monster effects were ignored, require a higher win-rate to be conservative.
            val isLocalResult = simData.results.isEmpty()
            val requiredWinRate = if (isLocalResult) 0.98 else 0.90
            val sourceLabel = if (isLocalResult) "local sim" else "API sim"
            onStatus("Win rate vs $monsterCode: ${"%.0f".format(winRate * 100)}% ($sourceLabel, threshold: ${"%.0f".format(requiredWinRate * 100)}%)")

            if (winRate >= requiredWinRate) {
                onStatus("Win rate acceptable, proceeding with $monsterCode task")
                acceptedMonsterCodes.add(monsterCode)
                optimiseForMonster(characterName, monsterCode, onStatus)
                return StepResult.Waiting
            }

            // Win rate too low — need a coin to cancel
            onStatus("Win rate too low (${"%.0f".format(winRate * 100)}% < ${"%.0f".format(requiredWinRate * 100)}%), attempting to cancel task...")
            if (!ensureTaskCoinInInventory(characterName, onStatus)) {
                return null // No coin available — give up
            }

            // Move to task master (may already be there) and cancel
            val updatedChar = helper.refreshCharacter(characterName)
            val taskMaster = helper.findNearestTasksMaster(updatedChar, type)
                ?: return StepResult.Error("No $type task master found on map")

            if (!helper.isAt(updatedChar, taskMaster.x, taskMaster.y)) {
                onStatus("Moving to task master to cancel...")
            helper.navigateToTile(characterName, taskMaster)
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

    /**
     * Unified weapon + gear + utility optimization for [monsterCode], using the session
     * cache when possible. This replaces the previous separate weapon and gear passes —
     * weapon is now part of the greedy optimization loop and can no longer end up out of
     * sync with gear.
     */
    private suspend fun optimiseForMonster(
        characterName: String,
        monsterCode: String,
        onStatus: (String) -> Unit
    ) {
        onStatus("Optimizing loadout for $monsterCode...")
        val char = helper.refreshCharacter(characterName)
        val result = try {
            fightingExecutor.optimizeGearForMonster(characterName, char, monsterCode)
        } catch (e: Exception) {
            onStatus("Optimization failed: ${e.message}")
            return
        }

        if (result.equipActions.isNotEmpty()) {
            onStatus("Swapping ${result.equipActions.size} equipment piece(s) for $monsterCode...")
            try {
                helper.retrieveAndEquipItems(characterName, result.equipActions)
            } catch (e: Exception) {
                onStatus("Equip swap failed: ${e.message}")
            }
        } else {
            onStatus("Gear already optimal for $monsterCode")
        }

        if (result.utilityActions.isNotEmpty()) {
            onStatus("Applying ${result.utilityActions.size} utility potion(s) for $monsterCode...")
            try {
                helper.retrieveAndEquipUtilities(characterName, result.utilityActions)
            } catch (e: Exception) {
                onStatus("Utility equip failed: ${e.message}")
            }
        }

        fightingExecutor.gearOptimizer.markOptimized(characterName, monsterCode)
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

        // Periodic tool upgrade check — use getOrPut(now) so the check does not
        // fire on the very first tick (lastUpgradeCheck initialises to current time).
        val now       = System.currentTimeMillis()
        val lastCheck = lastUpgradeCheck.getOrPut(characterName) { now }
        if (now - lastCheck >= upgradeCheckIntervalMs) {
            lastUpgradeCheck[characterName] = now
            currentChar = tryUpgradeTool(characterName, currentChar, ing.gatherSkill, onStatus)
        }

        // ── Move to resource and gather (handles underground/interior transitions) ──
        val resourceMap = helper.findNearest(currentChar, "resource", ing.resourceCode)
            ?: return StepResult.Error("No ${ing.resourceCode} locations found on map")

        if (!helper.isAt(currentChar, resourceMap.x, resourceMap.y) || currentChar.layer != resourceMap.layer) {
            onStatus("Moving to ${ing.resourceName}...")
            currentChar = helper.navigateToTile(characterName, resourceMap)
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
        helper.navigateToTile(characterName, taskMaster)

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

        // Create a temporary fight task and delegate to the fighting executor.
        // Pass the character already fetched at the top of executeStep so
        // FightingExecutor skips its own redundant refreshCharacter call.
        // Use BANK_RAW as the default drop strategy — task master fights depend on
        // bank food for healing rather than cooking drops on the fly.
        val fightTask = TaskType.Fight(
            monsterCode = monsterCode,
            monsterName = monsterCode,
            defaultDropStrategy = DropStrategy.BANK_RAW
        )
        return fightingExecutor.executeStep(characterName, fightTask, { msg -> onStatus(msg) }, previousChar = char)
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
            helper.navigateToTile(characterName, taskMaster)
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
            helper.navigateToTile(characterName, taskMaster)
        }

        onStatus("Cancelling current task...")
        helper.cancelTask(characterName)

        return StepResult.TaskMasterTaskCancelled
    }

    // ── Tool Upgrade ──

    /**
     * Check if a better ready-made tool is available in the bank and equip it.
     * Craftable tool upgrades are intentionally not checked here — tool crafting
     * is handled by the dedicated crafter character, not by task-master characters.
     */
    private suspend fun tryUpgradeTool(
        characterName: String,
        currentChar: Character,
        skill: String,
        onStatus: (String) -> Unit
    ): Character {
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

        return currentChar
    }
}
