package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.SimpleItem

/**
 * What to do with items after crafting.
 */
enum class CraftMode {
    /** Craft the initial quantity, recycle for recovered materials, repeat until nothing left. */
    RECYCLE,
    /** Craft a target quantity and deposit to bank. */
    BANK
}

/**
 * How to handle a cookable drop from a monster during a fight task.
 */
enum class DropStrategy {
    /** Cook and keep on hand for healing during fights. */
    COOK_AND_USE,
    /** Cook then deposit to bank (stockpile cooked food). */
    COOK_AND_BANK,
    /** Deposit raw to bank without cooking (stockpile raw materials). */
    BANK_RAW
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
        val equipActions: List<ActionHelper.EquipAction> = emptyList(),
        /**
         * Per-drop strategy for cookable drops. Key = raw item code, value = strategy.
         * Drops not in this map fall back to [defaultDropStrategy].
         */
        val dropStrategies: Map<String, DropStrategy> = emptyMap(),
        /**
         * Fallback strategy for cookable drops not explicitly listed in [dropStrategies].
         * Defaults to BANK_RAW — drops are stored raw unless explicitly configured otherwise.
         */
        val defaultDropStrategy: DropStrategy = DropStrategy.BANK_RAW
    ) : TaskType()

    /** Fight a boss monster cooperatively. Initiator fires the fight; participants await the rendezvous. */
    data class BossFight(
        val monsterCode: String,
        val monsterName: String,
        val initiatorName: String,
        /** Non-empty for the initiator runner; empty for participant runners. */
        val participantNames: List<String>,
        val isInitiator: Boolean,
        val equipActions: List<ActionHelper.EquipAction> = emptyList(),
        val dropStrategies: Map<String, DropStrategy> = emptyMap(),
        /**
         * Fallback strategy for cookable drops not explicitly listed in [dropStrategies].
         * Defaults to BANK_RAW — drops are stored raw unless explicitly configured otherwise.
         */
        val defaultDropStrategy: DropStrategy = DropStrategy.BANK_RAW
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

    /** Quick task: withdraw multiple items from the bank (one bank trip). */
    data class BulkBankWithdraw(
        val items: List<SimpleItem>
    ) : TaskType()

    /** Quick task: deposit multiple items from inventory to the bank (one bank trip). */
    data class BulkInventoryDeposit(
        val items: List<SimpleItem>
    ) : TaskType()

    /** Event task: gather resources from an ephemeral event tile. */
    data class EventGather(
        val eventCode: String,
        val resourceCode: String,
        val resourceName: String,
        val skill: String,
        val eventMapX: Int,
        val eventMapY: Int,
        val eventMapLayer: String
    ) : TaskType()

    /** Event task: sell/buy items at an ephemeral NPC event tile (one-shot). */
    data class EventNpc(
        val eventCode: String,
        val npcCode: String,
        val npcName: String,
        val eventMapX: Int,
        val eventMapY: Int,
        val eventMapLayer: String,
        val itemsToSell: List<SimpleItem>,
        val itemsToBuy: List<SimpleItem>
    ) : TaskType()

    /** Event task: fight a monster that spawned from an event. Reverts to previous task on event end or death threshold. */
    data class EventFight(
        val eventCode: String,
        val monsterCode: String,
        val monsterName: String,
        val eventMapX: Int,
        val eventMapY: Int,
        val eventMapLayer: String,
        val equipActions: List<ActionHelper.EquipAction> = emptyList(),
        val dropStrategies: Map<String, DropStrategy> = emptyMap(),
        val defaultDropStrategy: DropStrategy = DropStrategy.BANK_RAW
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
    val lastError: String? = null,
    /** Number of consecutive fight losses without a win. Resets to 0 on any win. */
    val consecutiveDeaths: Int = 0,
    /** When non-null, this character is waiting for boss fight participants to be ready. */
    val awaitingParticipants: List<String>? = null,
    /** When non-null, identifies the event code this character is currently responding to. */
    val activeEventCode: String? = null
)
