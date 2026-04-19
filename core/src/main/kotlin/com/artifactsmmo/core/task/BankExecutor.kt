package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.client.models.Drop

private fun Drop.toSimpleItem() = SimpleItem(code, quantity)

/**
 * Executes bank and inventory quick tasks:
 * - BankWithdraw: move to bank, withdraw a specific item, done.
 * - BankRecycle:  move to bank, withdraw item, move to workshop, recycle,
 *                 move to bank, deposit recovered materials, done.
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
     * Move to bank, withdraw item, move to workshop for [task.craftSkill],
     * recycle, move back to bank, deposit recovered materials.
     */
    suspend fun executeBankRecycle(
        characterName: String,
        task: TaskType.BankRecycle,
        onStatus: (String) -> Unit
    ): StepResult {
        // Withdraw from bank
        onStatus("Withdrawing ${task.quantity}x ${task.itemName} from bank...")
        helper.bankWithdrawItems(characterName, listOf(SimpleItem(task.itemCode, task.quantity)))

        // Move to appropriate workshop
        var char = helper.refreshCharacter(characterName)
        val workshop = helper.findNearestWorkshop(char, task.craftSkill)
            ?: return StepResult.Error("No ${task.craftSkill} workshop found")

        if (!helper.isAt(char, workshop.x, workshop.y)) {
            onStatus("Moving to ${task.craftSkill} workshop to recycle...")
            helper.moveTo(characterName, workshop.x, workshop.y)
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
            helper.moveTo(characterName, workshop.x, workshop.y)
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
}
