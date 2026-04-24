package com.artifactsmmo.core.task

/**
 * Whether a crafting task is for leveling (craft & recycle) or crafting specific items.
 */
enum class CraftMode {
    /** Craft and recycle repeatedly for XP. */
    LEVELING,
    /** Craft a specific quantity and deposit to bank. */
    SPECIFIC
}

/**
 * Represents a task that a character can be assigned to.
 */
sealed class TaskType {
    /** Character is idle and not doing anything. */
    data object Idle : TaskType()

    /** Gather a specific resource (mining, woodcutting, fishing, alchemy). */
    data class Gather(
        val skill: String,
        val resourceCode: String,
        val resourceName: String,
        /** If set, craft this specific item when inventory is full, then bank. */
        val targetItemCode: String? = null,
        val targetItemName: String? = null,
        /** Fishing only: cook fish before depositing. */
        val cookBeforeDeposit: Boolean = false
    ) : TaskType()

    /** Fight monsters at a specific location. */
    data class Fight(
        val monsterCode: String,
        val monsterName: String,
        /**
         * One-shot equip actions to execute before the fight loop begins.
         * NOT persisted — these are set by the wizard and cleared after execution.
         */
        val equipActions: List<ActionHelper.EquipAction> = emptyList()
    ) : TaskType()

    /** Craft items at a workshop (weaponcrafting, gearcrafting, jewelrycrafting, or misc). */
    data class Craft(
        val skill: String,
        val itemCode: String,
        val itemName: String,
        val mode: CraftMode,
        val targetQuantity: Int = 0,
        val craftedSoFar: Int = 0
    ) : TaskType()

    /** Run tasks from an NPC task master (items or monsters). */
    data class TaskMaster(
        val type: String  // "items" or "monsters"
    ) : TaskType()

    /** Quick task: withdraw a specific item from the bank. */
    data class BankWithdraw(
        val itemCode: String,
        val itemName: String,
        val quantity: Int
    ) : TaskType()

    /** Quick task: withdraw an item from the bank and recycle it at the appropriate workshop. */
    data class BankRecycle(
        val itemCode: String,
        val itemName: String,
        val quantity: Int,
        val craftSkill: String
    ) : TaskType()

    /** Quick task: deposit a specific item from inventory to the bank. */
    data class InventoryDeposit(
        val itemCode: String,
        val itemName: String,
        val quantity: Int
    ) : TaskType()

    /** Quick task: recycle an inventory item at the appropriate workshop, then deposit recovered materials. */
    data class InventoryRecycle(
        val itemCode: String,
        val itemName: String,
        val quantity: Int,
        val craftSkill: String
    ) : TaskType()
}

/**
 * Current status of a character's task runner.
 */
data class RunnerStatus(
    val characterName: String,
    val characterLevel: Int = 0,
    val task: TaskType = TaskType.Idle,
    val statusMessage: String = "Idle",
    val gatherCount: Int = 0,
    val fightCount: Int = 0,
    val craftCount: Int = 0,
    val recycleCount: Int = 0,
    val bankTrips: Int = 0,
    val tasksCompleted: Int = 0,
    val isRunning: Boolean = false,
    val lastError: String? = null
)
