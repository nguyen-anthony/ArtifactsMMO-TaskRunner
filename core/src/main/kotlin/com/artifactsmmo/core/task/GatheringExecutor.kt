package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.SimpleItem

/**
 * Executes gathering task loops (mining, woodcutting, fishing, alchemy).
 *
 * Loop: ensure tool equipped -> check for tool upgrade -> move to resource -> gather ->
 *       when inventory full -> handle based on task config -> return to resource
 *
 * Three modes:
 *  - Raw deposit: gather and bank (default)
 *  - Specific crafted item (targetItemCode set): gather ingredients, craft target, bank
 *  - Cook before deposit (fishing only): gather fish, cook, bank
 */
class GatheringExecutor(private val helper: ActionHelper) {

    /** Track last upgrade check time per character (epoch millis). */
    private val lastUpgradeCheck = mutableMapOf<String, Long>()

    /** How often to check for tool upgrades (5 minutes). */
    private val upgradeCheckIntervalMs = 5 * 60 * 1000L

    /**
     * Execute a single iteration of the gathering loop.
     * Returns a result describing what happened.
     */
    suspend fun executeStep(
        characterName: String,
        task: TaskType.Gather,
        onStatus: (String) -> Unit
    ): StepResult {
        var char = helper.refreshCharacter(characterName)

        // Check if inventory is full
        if (helper.isInventoryFull(char)) {
            onStatus("Inventory full, handling...")
            return handleFullInventory(characterName, task, onStatus)
        }

        // Ensure best tool is equipped
        onStatus("Checking tool...")
        char = helper.ensureToolEquipped(characterName, task.skill)

        // Check for tool upgrade from bank materials periodically
        val now = System.currentTimeMillis()
        val lastCheck = lastUpgradeCheck[characterName] ?: 0L
        if (now - lastCheck >= upgradeCheckIntervalMs) {
            lastUpgradeCheck[characterName] = now
            char = tryUpgradeTool(characterName, char, task.skill, onStatus)
        }

        // For specific crafted items, we need to gather from the right resource.
        // The resourceCode in the task may be a raw ingredient code rather than a resource code.
        // We need to find the resource that drops the needed ingredients.
        val (resourceCode, resourceName) = if (task.targetItemCode != null) {
            val resolved = findNextResourceToGather(char, task, onStatus)
            (resolved?.first ?: task.resourceCode) to (resolved?.second ?: task.resourceName)
        } else {
            task.resourceCode to task.resourceName
        }

        // Find resource location and move there
        val resourceMap = helper.findNearest(char, "resource", resourceCode)
            ?: return StepResult.Error("No $resourceCode locations found on map")

        if (!helper.isAt(char, resourceMap.x, resourceMap.y)) {
            onStatus("Moving to $resourceName...")
            char = helper.moveTo(characterName, resourceMap.x, resourceMap.y)
        }

        // Gather
        val totalItems = char.inventory.sumOf { it.quantity }
        val targetLabel = if (task.targetItemName != null) " for ${task.targetItemName}" else ""
        onStatus("Gathering $resourceName$targetLabel... (Inv: $totalItems/${char.inventoryMaxItems})")

        return try {
            val result = helper.gather(characterName)
            val drops = result.details.items.joinToString(", ") { "${it.quantity}x ${it.code}" }
            onStatus("Gathered: $drops (+${result.details.xp} XP)")
            StepResult.Gathered(result.details.xp, result.details.items.map { it.code to it.quantity })
        } catch (e: ArtifactsApiException) {
            if (e.errorCode == 486) {
                // Still in cooldown, just wait
                StepResult.Waiting
            } else throw e
        }
    }

    /**
     * Run once when a gather task starts.
     * For specific crafted item tasks: checks the bank for leftover raw materials
     * that can be crafted into the target item, withdraws them, crafts, and deposits.
     * For cook-before-deposit tasks: checks bank for leftover raw fish, cooks, deposits.
     */
    suspend fun prepareGatherTask(
        characterName: String,
        task: TaskType.Gather,
        onStatus: (String) -> Unit
    ) {
        if (task.targetItemCode != null) {
            prepareTargetItemTask(characterName, task, onStatus)
        } else if (task.cookBeforeDeposit) {
            prepareCookTask(characterName, onStatus)
        }
        // Raw deposit mode: nothing to prepare
    }

    /**
     * Prepare a specific crafted item task: check bank for leftover ingredients,
     * withdraw, craft, and deposit.
     */
    private suspend fun prepareTargetItemTask(
        characterName: String,
        task: TaskType.Gather,
        onStatus: (String) -> Unit
    ) {
        val targetItem = try {
            helper.getItem(task.targetItemCode!!)
        } catch (_: Exception) { return }

        val craft = targetItem.craft ?: return
        val workshopSkill = craft.skill ?: return

        // Check if bank has all ingredients for at least one craft
        val maxCraftable = craft.items.minOfOrNull { ingredient ->
            helper.getBankItemQuantity(ingredient.code) / ingredient.quantity
        } ?: 0

        if (maxCraftable <= 0) return

        val char = helper.refreshCharacter(characterName)
        val currentItems = char.inventory.sumOf { it.quantity }
        val withdrawList = craft.items.map { SimpleItem(it.code, it.quantity * maxCraftable) }
        val withdrawTotal = withdrawList.sumOf { it.quantity }

        if (currentItems + withdrawTotal > char.inventoryMaxItems) {
            onStatus("Not enough inventory space to process bank leftovers for ${targetItem.name}, skipping")
            return
        }

        onStatus("Withdrawing $withdrawTotal raw materials from bank for ${targetItem.name}...")
        helper.bankWithdrawItems(characterName, withdrawList)

        val workshop = helper.findNearestWorkshop(helper.refreshCharacter(characterName), workshopSkill)
        if (workshop == null) {
            helper.bankDepositItems(characterName, withdrawList)
            return
        }

        onStatus("Crafting ${maxCraftable}x ${targetItem.name} from bank leftovers...")
        helper.moveTo(characterName, workshop.x, workshop.y)
        helper.craft(characterName, targetItem.code, maxCraftable)

        // Deposit crafted items
        val updatedChar = helper.refreshCharacter(characterName)
        val toDeposit = updatedChar.inventory
            .filter { slot -> slot.quantity > 0 }
            .mapNotNull { slot ->
                val type = helper.getItemType(slot.code)
                if (type == "resource" || type == "consumable") SimpleItem(slot.code, slot.quantity) else null
            }

        if (toDeposit.isNotEmpty()) {
            onStatus("Depositing crafted ${targetItem.name}...")
            helper.bankDepositItems(characterName, toDeposit)
        }
    }

    /**
     * Prepare a cook-before-deposit task: check bank for leftover raw fish,
     * cook simple recipes, and deposit.
     */
    private suspend fun prepareCookTask(
        characterName: String,
        onStatus: (String) -> Unit
    ) {
        val char = helper.refreshCharacter(characterName)
        val cookable = helper.findCraftableRefinements(char, "fishing")
            .filter { (item, _) -> item.craft?.items?.size == 1 } // Simple single-ingredient recipes only

        if (cookable.isEmpty()) return

        // Check bank for raw fish
        val bankCookable = helper.findCraftableRefinementsFromBank(char, "fishing")
            .filter { (item, _, _) -> item.craft?.items?.size == 1 }

        if (bankCookable.isEmpty()) return

        for ((item, maxQty, withdrawList) in bankCookable) {
            val currentItems = helper.refreshCharacter(characterName).inventory.sumOf { it.quantity }
            val maxItems = helper.refreshCharacter(characterName).inventoryMaxItems
            val withdrawTotal = withdrawList.sumOf { it.quantity }

            if (currentItems + withdrawTotal > maxItems) {
                onStatus("Not enough inventory space to cook bank leftovers for ${item.name}, skipping")
                continue
            }

            onStatus("Withdrawing $withdrawTotal raw fish from bank for ${item.name}...")
            helper.bankWithdrawItems(characterName, withdrawList)

            val workshop = helper.findNearestWorkshop(helper.refreshCharacter(characterName), "cooking")
            if (workshop == null) {
                helper.bankDepositItems(characterName, withdrawList)
                continue
            }

            onStatus("Cooking ${maxQty}x ${item.name} from bank leftovers...")
            helper.moveTo(characterName, workshop.x, workshop.y)
            helper.craft(characterName, item.code, maxQty)

            val updatedChar = helper.refreshCharacter(characterName)
            val toDeposit = updatedChar.inventory
                .filter { slot -> slot.quantity > 0 }
                .mapNotNull { slot ->
                    val type = helper.getItemType(slot.code)
                    if (type == "resource" || type == "consumable") SimpleItem(slot.code, slot.quantity) else null
                }

            if (toDeposit.isNotEmpty()) {
                onStatus("Depositing cooked ${item.name}...")
                helper.bankDepositItems(characterName, toDeposit)
            }
        }
    }

    /**
     * For specific crafted item tasks with multiple ingredients, determine which
     * resource to gather next based on what's needed vs what's in inventory.
     *
     * Strategy: calculate how many crafts fit in the remaining inventory space,
     * then determine the target quantity for each ingredient. Gather each ingredient
     * to its target before switching to the next — avoids constant back-and-forth.
     *
     * Returns (resourceCode, resourceName) to gather from, or null to use the default.
     */
    private suspend fun findNextResourceToGather(
        char: Character,
        task: TaskType.Gather,
        onStatus: (String) -> Unit
    ): Pair<String, String>? {
        val targetItem = try {
            helper.getItem(task.targetItemCode!!)
        } catch (_: Exception) { return null }

        val craft = targetItem.craft ?: return null
        if (craft.items.size <= 1) return null // Single ingredient, use default resourceCode

        // Use findTaskItemSource to resolve all ingredients to their resource nodes
        val source = try {
            helper.findTaskItemSource(task.targetItemCode!!)
        } catch (_: Exception) { return null }

        if (source == null || source.allIngredients.size <= 1) return null

        // Calculate how many crafts we can fit in the inventory.
        // Total ingredients per craft = sum of all rawPerCraft values.
        val totalPerCraft = source.allIngredients.sumOf { it.rawPerCraft }
        val currentItems = char.inventory.sumOf { it.quantity }
        val freeSlots = char.inventoryMaxItems - currentItems
        // Include what we already have toward the batch
        val alreadyHave = source.allIngredients.sumOf { ingredient ->
            helper.getItemQuantity(char, ingredient.rawItemCode)
        }
        val totalCapacity = freeSlots + alreadyHave
        val batchCrafts = maxOf(1, totalCapacity / totalPerCraft)

        // Determine target quantity for each ingredient in this batch
        // Then find the first ingredient that hasn't reached its target yet
        for (ingredient in source.allIngredients) {
            val target = ingredient.rawPerCraft * batchCrafts
            val have = helper.getItemQuantity(char, ingredient.rawItemCode)
            if (have < target) {
                return ingredient.resourceCode to ingredient.resourceName
            }
        }

        // All ingredients at target — shouldn't happen (inventory would be full),
        // but fall back to the ingredient with the lowest ratio
        var mostNeededResource: Pair<String, String>? = null
        var lowestRatio = Double.MAX_VALUE
        for (ingredient in source.allIngredients) {
            val have = helper.getItemQuantity(char, ingredient.rawItemCode)
            val ratio = have.toDouble() / ingredient.rawPerCraft
            if (ratio < lowestRatio) {
                lowestRatio = ratio
                mostNeededResource = ingredient.resourceCode to ingredient.resourceName
            }
        }
        return mostNeededResource
    }

    /**
     * Check if a better tool is available — either already in the bank (ready-made)
     * or craftable from bank materials. Ready-made tools are checked first so that
     * tools crafted by other characters are picked up immediately.
     */
    private suspend fun tryUpgradeTool(
        characterName: String,
        currentChar: Character,
        skill: String,
        onStatus: (String) -> Unit
    ): Character {
        // First: check if a better tool already exists in the bank
        val readyMade = try {
            helper.findReadyMadeToolInBank(currentChar, skill)
        } catch (_: Exception) { null }

        if (readyMade != null) {
            onStatus("Found ${readyMade.tool.name} in bank! Withdrawing...")
            helper.bankWithdrawItems(characterName, listOf(SimpleItem(readyMade.tool.code, 1)))

            // Unequip current weapon if any
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

        // Second: check if we can craft a better tool from bank materials
        val upgrade = try {
            helper.findBestCraftableToolFromBank(currentChar, skill)
        } catch (_: Exception) {
            null
        } ?: return currentChar

        onStatus("Upgrade available: ${upgrade.tool.name}! Withdrawing materials...")

        // Withdraw ingredients from bank
        var char = helper.bankWithdrawItems(characterName, upgrade.ingredients)

        // Move to the crafting workshop
        val workshop = helper.findNearestWorkshop(char, upgrade.craftSkill)
        if (workshop == null) {
            onStatus("No ${upgrade.craftSkill} workshop found, skipping upgrade")
            // Deposit the ingredients back
            helper.bankDepositItems(characterName, upgrade.ingredients)
            return helper.refreshCharacter(characterName)
        }

        onStatus("Crafting ${upgrade.tool.name} at ${upgrade.craftSkill} workshop...")
        char = helper.moveTo(characterName, workshop.x, workshop.y)
        helper.craft(characterName, upgrade.tool.code, 1)

        // Unequip current weapon if any
        char = helper.refreshCharacter(characterName)
        if (char.weaponSlot.isNotEmpty()) {
            char = helper.unequip(characterName, "weapon")
        }

        // Equip the new tool
        char = helper.equip(characterName, upgrade.tool.code, "weapon")
        onStatus("Equipped ${upgrade.tool.name}!")

        return char
    }

    /**
     * Handle a full inventory based on the task configuration.
     */
    private suspend fun handleFullInventory(
        characterName: String,
        task: TaskType.Gather,
        onStatus: (String) -> Unit
    ): StepResult {
        return when {
            // Specific crafted item: craft the target item, then bank
            task.targetItemCode != null -> handleCraftTargetItem(characterName, task, onStatus)
            // Fishing with cook: cook simple fish recipes, then bank
            task.cookBeforeDeposit -> handleCookThenBank(characterName, onStatus)
            // Default: just bank everything
            else -> handleBankOnly(characterName, onStatus)
        }
    }

    /**
     * Bank only: deposit all resources, consumables, and currency.
     */
    private suspend fun handleBankOnly(
        characterName: String,
        onStatus: (String) -> Unit
    ): StepResult {
        onStatus("Banking items...")
        val char = helper.refreshCharacter(characterName)
        val safeTypes = setOf("resource", "consumable", "currency")
        val itemsToDeposit = mutableListOf<SimpleItem>()
        for (slot in char.inventory) {
            if (slot.quantity <= 0) continue
            val type = helper.getItemType(slot.code)
            if (type in safeTypes) {
                itemsToDeposit.add(SimpleItem(slot.code, slot.quantity))
            }
        }
        if (itemsToDeposit.isNotEmpty()) {
            helper.bankDepositItems(characterName, itemsToDeposit)
        }
        return StepResult.Banked
    }

    /**
     * Craft a specific target item from inventory ingredients, then bank everything.
     */
    private suspend fun handleCraftTargetItem(
        characterName: String,
        task: TaskType.Gather,
        onStatus: (String) -> Unit
    ): StepResult {
        val targetItem = try {
            helper.getItem(task.targetItemCode!!)
        } catch (_: Exception) {
            // Can't look up target item, just bank
            return handleBankOnly(characterName, onStatus)
        }

        val craft = targetItem.craft
        val workshopSkill = craft?.skill
        if (craft == null || workshopSkill == null) {
            return handleBankOnly(characterName, onStatus)
        }

        val char = helper.refreshCharacter(characterName)

        // Calculate how many we can craft from current inventory
        val maxCraftable = craft.items.minOfOrNull { ingredient ->
            helper.getItemQuantity(char, ingredient.code) / ingredient.quantity
        } ?: 0

        var totalCrafted = 0

        // Collect raw ingredient codes so we can keep leftovers
        val rawIngredientCodes = craft.items.map { it.code }.toSet()

        if (maxCraftable > 0) {
            val workshop = helper.findNearestWorkshop(char, workshopSkill)
            if (workshop != null) {
                onStatus("Moving to $workshopSkill workshop to craft ${targetItem.name}...")
                helper.moveTo(characterName, workshop.x, workshop.y)

                // Re-check after moving
                val updatedChar = helper.refreshCharacter(characterName)
                val actualCraftable = craft.items.minOfOrNull { ingredient ->
                    helper.getItemQuantity(updatedChar, ingredient.code) / ingredient.quantity
                } ?: 0

                if (actualCraftable > 0) {
                    onStatus("Crafting ${actualCraftable}x ${targetItem.name}...")
                    helper.craft(characterName, targetItem.code, actualCraftable)
                    totalCrafted = actualCraftable
                }
            }
        }

        // Bank everything EXCEPT leftover raw ingredients (keep them for next cycle)
        val updatedChar = helper.refreshCharacter(characterName)
        val safeTypes = setOf("resource", "consumable", "currency")
        val itemsToDeposit = mutableListOf<SimpleItem>()
        for (slot in updatedChar.inventory) {
            if (slot.quantity <= 0) continue
            if (slot.code in rawIngredientCodes) continue // Keep leftovers
            val type = helper.getItemType(slot.code)
            if (type in safeTypes) {
                itemsToDeposit.add(SimpleItem(slot.code, slot.quantity))
            }
        }

        if (itemsToDeposit.isNotEmpty()) {
            onStatus("Banking items (keeping leftover raw materials)...")
            helper.bankDepositItems(characterName, itemsToDeposit)
        } else {
            onStatus("Nothing to bank, keeping leftovers")
        }

        return if (totalCrafted > 0) StepResult.CraftedAndBanked(totalCrafted) else StepResult.Banked
    }

    /**
     * Cook simple fish recipes (single-ingredient only), then bank everything.
     */
    private suspend fun handleCookThenBank(
        characterName: String,
        onStatus: (String) -> Unit
    ): StepResult {
        val char = helper.refreshCharacter(characterName)

        // Find simple cookable items (single ingredient) from inventory
        val cookable = helper.findCraftableRefinements(char, "fishing")
            .filter { (item, _) -> item.craft?.items?.size == 1 }

        var totalCrafted = 0

        if (cookable.isNotEmpty()) {
            val workshop = helper.findNearestWorkshop(char, "cooking")
            if (workshop != null) {
                onStatus("Moving to cooking workshop...")
                helper.moveTo(characterName, workshop.x, workshop.y)

                // Re-check after moving
                val updatedChar = helper.refreshCharacter(characterName)
                val updatedCookable = helper.findCraftableRefinements(updatedChar, "fishing")
                    .filter { (item, _) -> item.craft?.items?.size == 1 }

                for ((item, maxQty) in updatedCookable) {
                    onStatus("Cooking ${maxQty}x ${item.name}...")
                    helper.craft(characterName, item.code, maxQty)
                    totalCrafted += maxQty
                }
            }
        }

        // Bank everything
        val updatedChar = helper.refreshCharacter(characterName)
        val safeTypes = setOf("resource", "consumable", "currency")
        val itemsToDeposit = mutableListOf<SimpleItem>()
        for (slot in updatedChar.inventory) {
            if (slot.quantity <= 0) continue
            val type = helper.getItemType(slot.code)
            if (type in safeTypes) {
                itemsToDeposit.add(SimpleItem(slot.code, slot.quantity))
            }
        }

        if (itemsToDeposit.isNotEmpty()) {
            onStatus("Banking items...")
            helper.bankDepositItems(characterName, itemsToDeposit)
        }

        return if (totalCrafted > 0) StepResult.CraftedAndBanked(totalCrafted) else StepResult.Banked
    }
}

sealed class StepResult {
    data class Gathered(val xp: Int, val items: List<Pair<String, Int>>) : StepResult()
    data object Banked : StepResult()
    data class CraftedAndBanked(val craftCount: Int) : StepResult()
    data class FightWon(val xp: Int, val gold: Int) : StepResult()
    data class FightLost(val message: String) : StepResult()
    data object Rested : StepResult()
    data object Waiting : StepResult()
    data class Error(val message: String) : StepResult()
    /** Crafting task: successfully crafted (and optionally recycled) items. */
    data class Crafted(val count: Int, val recycled: Int = 0) : StepResult()
    /** Crafting task: ran out of materials in bank + inventory. */
    data object OutOfMaterials : StepResult()
    /** Crafting task (specific mode): target quantity has been reached. */
    data object CraftTaskComplete : StepResult()
    /** Task master: a task was completed and rewards collected. */
    data object TaskMasterTaskCompleted : StepResult()
    /** Task master: current task was cancelled (can't fulfill), will accept new one. */
    data object TaskMasterTaskCancelled : StepResult()
    /** Quick task (BankWithdraw/BankRecycle/InventoryDeposit/InventoryRecycle): task is done, revert to previous. */
    data object QuickTaskComplete : StepResult()
    /** Task master (monsters): no viable task found and no tasks_coins remain to keep cancelling. */
    data object TaskMasterNoViableTask : StepResult()
}
