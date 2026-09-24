package com.artifactsmmo.engine.worker

import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.GearOptimizer
import com.artifactsmmo.core.task.TaskType
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.core.task.CraftMode as LegacyCraftMode
import com.artifactsmmo.core.task.DropStrategy as LegacyDropStrategy

/**
 * Converts the persisted [TaskSpec] into the legacy in-memory [TaskType] that the existing
 * executors understand. This lets us keep the tuned executor logic as-is while the queue,
 * worker and persistence are new.
 */
object SpecMapper {
    fun toLegacy(spec: TaskSpec, craftedSoFar: Int = 0): TaskType = when (spec) {
        is TaskSpec.Gather -> TaskType.Gather(
            skill = spec.skill, resourceCode = spec.resourceCode, resourceName = spec.resourceName,
            targetItemCode = spec.targetItemCode, targetItemName = spec.targetItemName,
            cookBeforeDeposit = spec.cookBeforeDeposit,
        )
        is TaskSpec.Fight -> TaskType.Fight(
            monsterCode = spec.monsterCode, monsterName = spec.monsterName,
            equipActions = spec.equip.map { ActionHelper.EquipAction(it.slot, it.itemCode, it.source) },
            utilityActions = spec.utilities.map { GearOptimizer.UtilityEquipAction(it.slot, it.itemCode, it.quantity, it.source) },
            loadoutOptimized = spec.loadoutOptimized,
            dropStrategies = spec.dropStrategies.mapValues { LegacyDropStrategy.valueOf(it.value.name) },
            defaultDropStrategy = LegacyDropStrategy.valueOf(spec.defaultDropStrategy.name),
        )
        is TaskSpec.Craft -> TaskType.Craft(
            skill = spec.skill, itemCode = spec.itemCode, itemName = spec.itemName,
            mode = LegacyCraftMode.valueOf(spec.mode.name), targetQuantity = spec.targetQuantity,
            craftedSoFar = craftedSoFar,
        )
        is TaskSpec.TaskMaster -> TaskType.TaskMaster(spec.taskType)
        is TaskSpec.BankWithdraw -> TaskType.BankWithdraw(spec.itemCode, spec.itemName, spec.quantity)
        is TaskSpec.BankRecycle -> TaskType.BankRecycle(spec.itemCode, spec.itemName, spec.quantity, spec.craftSkill)
        is TaskSpec.InventoryDeposit -> TaskType.InventoryDeposit(spec.itemCode, spec.itemName, spec.quantity)
        is TaskSpec.InventoryRecycle -> TaskType.InventoryRecycle(spec.itemCode, spec.itemName, spec.quantity, spec.craftSkill)
        is TaskSpec.BulkBankWithdraw -> TaskType.BulkBankWithdraw(spec.items.map { SimpleItem(it.code, it.quantity) })
        is TaskSpec.BulkInventoryDeposit -> TaskType.BulkInventoryDeposit(spec.items.map { SimpleItem(it.code, it.quantity) })
    }
}
