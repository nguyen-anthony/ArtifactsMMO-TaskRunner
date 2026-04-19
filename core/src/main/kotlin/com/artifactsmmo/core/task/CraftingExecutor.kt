package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.SimpleItem

/**
 * Executes crafting task loops (weaponcrafting, gearcrafting, jewelrycrafting, misc).
 *
 * Two modes:
 * - LEVELING: craft items and recycle them at the same workshop for XP. Uses recycled
 *   materials to supplement bank withdrawals. Loops until no more materials available.
 * - SPECIFIC: craft a target quantity of items and deposit them to the bank.
 *   Loops until target reached or out of materials.
 *
 * Both modes:
 * - Batch crafting based on inventory capacity
 * - Check inventory first, then withdraw deficit from bank
 * - Return OutOfMaterials when no more ingredients available
 */
class CraftingExecutor(private val helper: ActionHelper) {

    /**
     * Execute a single step of the crafting loop.
     * A "step" may involve: withdrawing materials, moving to workshop, crafting a batch,
     * and recycling (leveling) or depositing (specific).
     */
    suspend fun executeStep(
        characterName: String,
        task: TaskType.Craft,
        onStatus: (String) -> Unit
    ): StepResult {
        val char = helper.refreshCharacter(characterName)

        // Look up the recipe for the target item
        val targetItem = try {
            helper.getItem(task.itemCode)
        } catch (e: Exception) {
            return StepResult.Error("Failed to look up item ${task.itemCode}: ${e.message}")
        }
        val recipe = targetItem.craft
            ?: return StepResult.Error("Item ${task.itemCode} has no crafting recipe")

        // Calculate how many we can craft from inventory + bank
        val ingredientAvailability = recipe.items.map { ingredient ->
            val invQty = helper.getItemQuantity(char, ingredient.code)
            val bankQty = helper.getBankItemQuantity(ingredient.code)
            IngredientInfo(ingredient.code, ingredient.quantity, invQty, bankQty)
        }

        val maxCraftableTotal = ingredientAvailability.minOf { (it.invQty + it.bankQty) / it.qtyPerCraft }

        if (maxCraftableTotal <= 0) {
            onStatus("No materials available for ${task.itemName}")
            return StepResult.OutOfMaterials
        }

        // For SPECIFIC mode, cap at remaining quantity
        val remaining = if (task.mode == CraftMode.SPECIFIC) {
            task.targetQuantity - task.craftedSoFar
        } else {
            Int.MAX_VALUE
        }

        if (remaining <= 0) {
            return StepResult.CraftTaskComplete
        }

        // Calculate batch size based on inventory capacity
        val totalIngredientsPerCraft = recipe.items.sumOf { it.quantity }
        val currentInvItems = char.inventory.sumOf { it.quantity }
        val freeSlots = char.inventoryMaxItems - currentInvItems
        // How many crafts fit in available inventory space (considering we need to hold ingredients)
        val batchByInventory = if (totalIngredientsPerCraft > 0) {
            maxOf(1, freeSlots / totalIngredientsPerCraft)
        } else 1

        val batchSize = minOf(maxCraftableTotal, remaining, batchByInventory)

        // Determine what to withdraw from bank (deficit = needed - already in inventory)
        val toWithdraw = mutableListOf<SimpleItem>()
        for (info in ingredientAvailability) {
            val needed = info.qtyPerCraft * batchSize
            val deficit = needed - info.invQty
            if (deficit > 0) {
                val withdrawQty = minOf(deficit, info.bankQty)
                if (withdrawQty > 0) {
                    toWithdraw.add(SimpleItem(info.code, withdrawQty))
                }
            }
        }

        // Withdraw from bank if needed
        if (toWithdraw.isNotEmpty()) {
            onStatus("Withdrawing materials from bank...")
            helper.bankWithdrawItems(characterName, toWithdraw)
        }

        // Move to workshop
        val updatedChar = helper.refreshCharacter(characterName)
        val workshop = helper.findNearestWorkshop(updatedChar, task.skill)
            ?: return StepResult.Error("No ${task.skill} workshop found")

        if (!helper.isAt(updatedChar, workshop.x, workshop.y)) {
            onStatus("Moving to ${task.skill} workshop...")
            helper.moveTo(characterName, workshop.x, workshop.y)
        }

        // Recalculate actual craftable from current inventory (after withdrawal)
        val charAtWorkshop = helper.refreshCharacter(characterName)
        val actualBatch = recipe.items.minOf { ingredient ->
            helper.getItemQuantity(charAtWorkshop, ingredient.code) / ingredient.quantity
        }.let { maxFromInv ->
            minOf(maxFromInv, remaining)
        }

        if (actualBatch <= 0) {
            onStatus("Not enough materials in inventory to craft")
            return StepResult.OutOfMaterials
        }

        // Craft the batch
        onStatus("Crafting ${actualBatch}x ${task.itemName}...")
        helper.craft(characterName, task.itemCode, actualBatch)

        return when (task.mode) {
            CraftMode.LEVELING -> handleLevelingPostCraft(characterName, task, actualBatch, onStatus)
            CraftMode.SPECIFIC -> handleSpecificPostCraft(characterName, task, actualBatch, onStatus)
        }
    }

    /**
     * After crafting in LEVELING mode: recycle the crafted items at the same workshop.
     */
    private suspend fun handleLevelingPostCraft(
        characterName: String,
        task: TaskType.Craft,
        craftedCount: Int,
        onStatus: (String) -> Unit
    ): StepResult {
        // Recycle the crafted items (we're already at the workshop)
        onStatus("Recycling ${craftedCount}x ${task.itemName}...")
        val recycleResult = helper.recycle(characterName, task.itemCode, craftedCount)

        val recovered = recycleResult.details.items
        if (recovered.isNotEmpty()) {
            val recoveredStr = recovered.joinToString(", ") { "${it.quantity}x ${it.code}" }
            onStatus("Recovered: $recoveredStr")
        }

        return StepResult.Crafted(count = craftedCount, recycled = craftedCount)
    }

    /**
     * After crafting in SPECIFIC mode: deposit crafted items to bank.
     */
    private suspend fun handleSpecificPostCraft(
        characterName: String,
        task: TaskType.Craft,
        craftedCount: Int,
        onStatus: (String) -> Unit
    ): StepResult {
        // Deposit the crafted items to the bank
        val char = helper.refreshCharacter(characterName)
        val craftedQty = helper.getItemQuantity(char, task.itemCode)
        if (craftedQty > 0) {
            onStatus("Depositing ${craftedQty}x ${task.itemName} to bank...")
            helper.bankDepositItems(characterName, listOf(SimpleItem(task.itemCode, craftedQty)))
        }

        val newTotal = task.craftedSoFar + craftedCount
        return if (newTotal >= task.targetQuantity) {
            onStatus("Completed! Crafted ${task.targetQuantity}x ${task.itemName}")
            StepResult.CraftTaskComplete
        } else {
            StepResult.Crafted(count = craftedCount)
        }
    }

    /**
     * Tracks availability info for a single ingredient.
     */
    private data class IngredientInfo(
        val code: String,
        val qtyPerCraft: Int,
        val invQty: Int,
        val bankQty: Int
    )
}
