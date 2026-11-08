package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.client.models.Drop

private fun Drop.toSimpleItem() = SimpleItem(code, quantity)

/**
 * Executes bank and inventory quick tasks:
 * - BankWithdraw: move to bank, withdraw a specific item, done.
 * - BankRecycle:  in inventory-safe batches: withdraw, recycle at the workshop, bank the
 *                 recovered materials; repeats until the quantity is done.
 * - InventoryDeposit: move to bank, deposit a specific item, done.
 * - InventoryRecycle: move to workshop, recycle, move to bank,
 *                     deposit recovered materials, done.
 *
 * All methods return [StepResult.QuickTaskComplete] on success, which
 * triggers the same previous-task revert as crafting tasks.
 */
class BankExecutor(private val helper: ActionHelper) {

    /**
     * Move to bank and withdraw [quantity] of [task.itemCode].
     */
    suspend fun executeBankWithdraw(
        characterName: String,
        task: TaskType.BankWithdraw,
        onStatus: (String) -> Unit
    ): StepResult {
        onStatus("Moving to bank to withdraw ${task.quantity}x ${task.itemName}...")
        helper.bankWithdrawItems(characterName, listOf(SimpleItem(task.itemCode, task.quantity)))
        onStatus("Withdrew ${task.quantity}x ${task.itemName}")
        return StepResult.QuickTaskComplete
    }

    /**
     * One batch of a bank recycle: clear the inventory, withdraw as many copies as the
     * inventory can safely hold *after* recycling (see [recycleBatchSize]), recycle them at
     * the [TaskType.BankRecycle.craftSkill] workshop, and bank the recovered materials.
     * Returns [StepResult.Crafted] with `recycled = batch` until [TaskType.BankRecycle.quantity]
     * is reached (or the bank runs out), then [StepResult.QuickTaskComplete].
     */
    suspend fun executeBankRecycle(
        characterName: String,
        task: TaskType.BankRecycle,
        onStatus: (String) -> Unit
    ): StepResult {
        val left = task.quantity - task.recycledSoFar
        if (left <= 0) {
            onStatus("Recycle complete: ${task.recycledSoFar}x ${task.itemName}")
            return StepResult.QuickTaskComplete
        }

        val item = helper.getItem(task.itemCode)
        val recipe = item.craft
        if (recipe == null || recipe.skill !in RECYCLABLE_SKILLS) {
            return StepResult.Failed("${task.itemName} can't be recycled (only weapon/gear/jewelry crafts)")
        }
        val perItem = recycleReturnPerItem(recipe.items.sumOf { it.quantity })

        // Bank everything except copies of the item itself (e.g. left from an interrupted batch)
        // so the recovered materials have room.
        var char = helper.depositInventoryExcept(characterName, setOf(task.itemCode))
        val held = helper.getItemQuantity(char, task.itemCode)
        val otherUsed = char.inventory.sumOf { it.quantity } - held
        val inBank = helper.getBankItemQuantity(task.itemCode)
        val batch = recycleBatchSize(left, held + inBank, char.inventoryMaxItems - otherUsed, perItem)
        if (batch <= 0) {
            if (held + inBank <= 0) {
                onStatus("No ${task.itemName} left in the bank (recycled ${task.recycledSoFar}/${task.quantity})")
                return StepResult.OutOfMaterials
            }
            return StepResult.Failed("Not enough inventory space to recycle ${task.itemName}")
        }

        val withdraw = batch - held
        if (withdraw > 0) {
            onStatus("Withdrawing ${withdraw}x ${task.itemName} from bank...")
            char = helper.bankWithdrawItems(characterName, listOf(SimpleItem(task.itemCode, withdraw)))
        }

        val workshop = helper.findNearestWorkshop(char, recipe.skill!!)
            ?: return StepResult.Error("No ${recipe.skill} workshop found")
        if (!helper.isAt(char, workshop.x, workshop.y)) {
            onStatus("Moving to ${recipe.skill} workshop to recycle...")
            helper.navigateWithTeleport(characterName, helper.refreshCharacter(characterName), workshop)
        }

        onStatus("Recycling ${batch}x ${task.itemName} (${task.recycledSoFar + batch}/${task.quantity})...")
        val recovered = helper.recycle(characterName, task.itemCode, batch).details.items
        if (recovered.isNotEmpty()) {
            onStatus("Recovered: ${recovered.joinToString(", ") { "${it.quantity}x ${it.code}" }} — depositing to bank...")
            helper.bankDepositItems(characterName, recovered.map { it.toSimpleItem() })
        }
        return StepResult.Crafted(count = 0, recycled = batch)
    }

    companion object {
        /** Only these crafts can be recycled (per the game docs). */
        val RECYCLABLE_SKILLS = setOf("weaponcrafting", "gearcrafting", "jewelrycrafting")

        /** Free inventory units kept on top of the worst-case recovered materials. */
        const val RECYCLE_HEADROOM = 5

        /** Resources returned per recycled item: floor((recipe total − 1) / 5) + 1. */
        fun recycleReturnPerItem(recipeTotal: Int): Int = (recipeTotal - 1).coerceAtLeast(0) / 5 + 1

        /**
         * Copies to recycle in one batch: limited by what's [left] to do, what's [available]
         * (inventory + bank), and the inventory: the batch is withdrawn first (one unit each)
         * and then replaced by up to [perItem] materials each, which must fit in
         * [freeCapacity] minus [RECYCLE_HEADROOM].
         */
        fun recycleBatchSize(left: Int, available: Int, freeCapacity: Int, perItem: Int): Int {
            val space = freeCapacity - RECYCLE_HEADROOM
            val byInventory = space / maxOf(1, perItem)
            return minOf(left, available, byInventory).coerceAtLeast(0)
        }
    }

    /**
     * Move to bank and deposit [quantity] of [task.itemCode] from inventory.
     */
    suspend fun executeInventoryDeposit(
        characterName: String,
        task: TaskType.InventoryDeposit,
        onStatus: (String) -> Unit
    ): StepResult {
        onStatus("Depositing ${task.quantity}x ${task.itemName} to bank...")
        helper.bankDepositItems(characterName, listOf(SimpleItem(task.itemCode, task.quantity)))
        onStatus("Deposited ${task.quantity}x ${task.itemName}")
        return StepResult.QuickTaskComplete
    }

    /**
     * Move to workshop for [task.craftSkill], recycle inventory item,
     * move to bank, deposit recovered materials.
     */
    suspend fun executeInventoryRecycle(
        characterName: String,
        task: TaskType.InventoryRecycle,
        onStatus: (String) -> Unit
    ): StepResult {
        // Move to workshop
        var char = helper.refreshCharacter(characterName)
        val workshop = helper.findNearestWorkshop(char, task.craftSkill)
            ?: return StepResult.Error("No ${task.craftSkill} workshop found")

        if (!helper.isAt(char, workshop.x, workshop.y)) {
            onStatus("Moving to ${task.craftSkill} workshop to recycle...")
            helper.navigateWithTeleport(characterName, helper.refreshCharacter(characterName), workshop)
        }

        // Recycle
        onStatus("Recycling ${task.quantity}x ${task.itemName}...")
        val recycleResult = helper.recycle(characterName, task.itemCode, task.quantity)
        val recovered = recycleResult.details.items
        if (recovered.isNotEmpty()) {
            val desc = recovered.joinToString(", ") { "${it.quantity}x ${it.code}" }
            onStatus("Recovered: $desc — depositing to bank...")
        }

        // Deposit recovered materials to bank
        if (recovered.isNotEmpty()) {
            helper.bankDepositItems(characterName, recovered.map { it.toSimpleItem() })
        }

        onStatus("Recycle complete: ${task.itemName}")
        return StepResult.QuickTaskComplete
    }

    /**
     * Move to bank and withdraw multiple items in a single trip.
     */
    suspend fun executeBulkBankWithdraw(
        characterName: String,
        task: TaskType.BulkBankWithdraw,
        onStatus: (String) -> Unit
    ): StepResult {
        onStatus("Moving to bank to withdraw ${task.items.size} items...")
        helper.bankWithdrawItems(characterName, task.items)
        onStatus("Withdrew ${task.items.size} items from bank")
        return StepResult.QuickTaskComplete
    }

    /**
     * Move to bank and deposit multiple items in a single trip.
     */
    suspend fun executeBulkInventoryDeposit(
        characterName: String,
        task: TaskType.BulkInventoryDeposit,
        onStatus: (String) -> Unit
    ): StepResult {
        onStatus("Depositing ${task.items.size} items to bank...")
        helper.bankDepositItems(characterName, task.items)
        onStatus("Deposited ${task.items.size} items to bank")
        return StepResult.QuickTaskComplete
    }
}
