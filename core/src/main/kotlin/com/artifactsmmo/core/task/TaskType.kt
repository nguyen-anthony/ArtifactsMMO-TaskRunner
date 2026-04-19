package com.artifactsmmo.core.task

/**
 * How to handle a full inventory during a gathering task.
 */
enum class FullInventoryStrategy {
    /** Go to bank and deposit everything. */
    BANK_ONLY,
    /** Go to workshop, craft raw -> refined, then bank the results. */
    CRAFT_THEN_BANK
}

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

    /** Gather a specific resource (mining, woodcutting, fishing). */
    data class Gather(
        val skill: String,
        val resourceCode: String,
        val resourceName: String,
        val onFullInventory: FullInventoryStrategy = FullInventoryStrategy.BANK_ONLY
    ) : TaskType()

    /** Fight monsters at a specific location. */
    data class Fight(
        val monsterCode: String,
        val monsterName: String
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
