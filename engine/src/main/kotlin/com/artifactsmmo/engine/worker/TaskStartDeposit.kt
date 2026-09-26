package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.core.task.TaskType

/**
 * Rules for the deposit a character makes right after claiming (or resuming) a task, so
 * it starts with as much free inventory as possible — e.g. a craft task can then withdraw
 * a full load of materials instead of whatever fits next to leftovers of the last task.
 *
 * Always deposits when anything is depositable. Never deposited: gathering tools (handled
 * by the helper) and the items returned by [keepFor].
 */
object TaskStartDeposit {

    /**
     * Item codes that must stay in inventory for [task], or null to skip the deposit
     * entirely (quick bank/inventory tasks act on the inventory themselves, NPC events sell
     * from it).
     *
     * @param useFoodCodes cooked food this fight eats between fights (COOK_AND_USE drops),
     *   so a resumed fight task keeps its food instead of banking and re-withdrawing it.
     */
    fun keepFor(task: TaskType, char: Character, useFoodCodes: Set<String> = emptySet()): Set<String>? = when (task) {
        is TaskType.Fight ->
            task.equipActions.filter { it.source == "inventory" }.map { it.itemCode }.toSet() +
                task.utilityActions.map { it.itemCode } + useFoodCodes
        is TaskType.EventFight ->
            task.equipActions.filter { it.source == "inventory" }.map { it.itemCode }.toSet() +
                task.utilityActions.map { it.itemCode } + useFoodCodes
        // An in-progress task-master items task: the collected items are the progress.
        is TaskType.TaskMaster ->
            if (char.taskType == "items" && char.task.isNotEmpty()) setOf(char.task) else emptySet()
        is TaskType.Gather, is TaskType.Craft, is TaskType.EventGather -> emptySet()
        is TaskType.BankWithdraw, is TaskType.BankRecycle, is TaskType.InventoryDeposit,
        is TaskType.InventoryRecycle, is TaskType.BulkBankWithdraw, is TaskType.BulkInventoryDeposit,
        is TaskType.EventNpc, is TaskType.BossFight, TaskType.Idle -> null
    }
}
