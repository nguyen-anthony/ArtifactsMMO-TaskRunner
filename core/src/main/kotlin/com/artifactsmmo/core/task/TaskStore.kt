package com.artifactsmmo.core.task

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

/**
 * Serializable representation of a task for persistence.
 * Used for both current tasks and previous tasks.
 */
@Serializable
data class StoredTask(
    val type: String, // "idle", "gather", "fight", "craft", "task_master", "bank_withdraw", "bank_recycle", "inventory_deposit", "inventory_recycle"
    val skill: String? = null,
    @SerialName("resource_code") val resourceCode: String? = null,
    @SerialName("resource_name") val resourceName: String? = null,
    @SerialName("target_item_code") val targetItemCode: String? = null,
    @SerialName("target_item_name") val targetItemName: String? = null,
    @SerialName("cook_before_deposit") val cookBeforeDeposit: Boolean? = null,
    @SerialName("monster_code") val monsterCode: String? = null,
    @SerialName("monster_name") val monsterName: String? = null,
    @SerialName("drop_strategies") val dropStrategies: Map<String, String>? = null,
    @SerialName("item_code") val itemCode: String? = null,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("craft_mode") val craftMode: String? = null, // "RECYCLE", "BANK"
    @SerialName("target_quantity") val targetQuantity: Int? = null,
    @SerialName("crafted_so_far") val craftedSoFar: Int? = null,
    @SerialName("task_master_type") val taskMasterType: String? = null, // "items", "monsters"
    val quantity: Int? = null,
    @SerialName("craft_skill") val craftSkill: String? = null
)

/**
 * A character's task assignment entry, including optional previous task.
 */
@Serializable
data class StoredAssignment(
    @SerialName("character_name") val characterName: String,
    val task: StoredTask,
    @SerialName("previous_task") val previousTask: StoredTask? = null
)

@Serializable
data class TaskStoreData(
    val tasks: List<StoredAssignment>
)

/**
 * Data holder for a character's current + previous task.
 */
data class TaskAssignment(
    val task: TaskType,
    val previousTask: TaskType? = null
)

/**
 * Persists task assignments to a JSON file so they survive restarts.
 */
class TaskStore(private val file: File = File("tasks.json")) {

    /**
     * Save current task assignments with optional previous tasks.
     */
    fun save(assignments: Map<String, TaskAssignment>) {
        val stored = assignments.map { (name, assignment) ->
            StoredAssignment(
                characterName = name,
                task = toStored(assignment.task),
                previousTask = assignment.previousTask?.let { toStored(it) }
            )
        }
        val data = TaskStoreData(tasks = stored)
        file.writeText(json.encodeToString(data))
    }

    /**
     * Load saved task assignments. Returns empty map if file doesn't exist or is invalid.
     */
    fun load(): Map<String, TaskAssignment> {
        if (!file.exists()) return emptyMap()

        return try {
            val data = json.decodeFromString<TaskStoreData>(file.readText())
            data.tasks.associate { entry ->
                entry.characterName to TaskAssignment(
                    task = fromStored(entry.task),
                    previousTask = entry.previousTask?.let { fromStored(it) }
                )
            }
        } catch (e: Exception) {
            System.err.println("Warning: Failed to load tasks.json: ${e.message}")
            emptyMap()
        }
    }

    private fun toStored(task: TaskType): StoredTask {
        return when (task) {
            is TaskType.Idle -> StoredTask(type = "idle")
            is TaskType.Gather -> StoredTask(
                type = "gather",
                skill = task.skill,
                resourceCode = task.resourceCode,
                resourceName = task.resourceName,
                targetItemCode = task.targetItemCode,
                targetItemName = task.targetItemName,
                cookBeforeDeposit = task.cookBeforeDeposit
            )
            is TaskType.Fight -> StoredTask(
                type = "fight",
                monsterCode = task.monsterCode,
                monsterName = task.monsterName,
                dropStrategies = if (task.dropStrategies.isNotEmpty())
                    task.dropStrategies.mapValues { it.value.name }
                else null
            )
            is TaskType.Craft -> StoredTask(
                type = "craft",
                skill = task.skill,
                itemCode = task.itemCode,
                itemName = task.itemName,
                craftMode = task.mode.name,
                targetQuantity = task.targetQuantity,
                craftedSoFar = task.craftedSoFar
            )
            is TaskType.TaskMaster -> StoredTask(
                type = "task_master",
                taskMasterType = task.type
            )
            is TaskType.BankWithdraw -> StoredTask(
                type = "bank_withdraw",
                itemCode = task.itemCode,
                itemName = task.itemName,
                quantity = task.quantity
            )
            is TaskType.BankRecycle -> StoredTask(
                type = "bank_recycle",
                itemCode = task.itemCode,
                itemName = task.itemName,
                quantity = task.quantity,
                craftSkill = task.craftSkill
            )
            is TaskType.InventoryDeposit -> StoredTask(
                type = "inventory_deposit",
                itemCode = task.itemCode,
                itemName = task.itemName,
                quantity = task.quantity
            )
            is TaskType.InventoryRecycle -> StoredTask(
                type = "inventory_recycle",
                itemCode = task.itemCode,
                itemName = task.itemName,
                quantity = task.quantity,
                craftSkill = task.craftSkill
            )
        }
    }

    private fun fromStored(stored: StoredTask): TaskType {
        return when (stored.type) {
            "gather" -> TaskType.Gather(
                skill = stored.skill ?: "",
                resourceCode = stored.resourceCode ?: "",
                resourceName = stored.resourceName ?: "",
                targetItemCode = stored.targetItemCode,
                targetItemName = stored.targetItemName,
                cookBeforeDeposit = stored.cookBeforeDeposit ?: false
            )
            "fight" -> TaskType.Fight(
                monsterCode = stored.monsterCode ?: "",
                monsterName = stored.monsterName ?: "",
                dropStrategies = stored.dropStrategies?.mapValues { (_, v) ->
                    try { DropStrategy.valueOf(v) } catch (_: Exception) { DropStrategy.COOK_AND_USE }
                } ?: emptyMap()
            )
            "craft" -> TaskType.Craft(
                skill = stored.skill ?: "",
                itemCode = stored.itemCode ?: "",
                itemName = stored.itemName ?: "",
                mode = stored.craftMode?.let {
                    try { CraftMode.valueOf(it) } catch (_: Exception) { CraftMode.BANK }
                } ?: CraftMode.BANK,
                targetQuantity = stored.targetQuantity ?: 0,
                craftedSoFar = stored.craftedSoFar ?: 0
            )
            "task_master" -> TaskType.TaskMaster(
                type = stored.taskMasterType ?: "items"
            )
            "bank_withdraw" -> TaskType.BankWithdraw(
                itemCode = stored.itemCode ?: "",
                itemName = stored.itemName ?: "",
                quantity = stored.quantity ?: 1
            )
            "bank_recycle" -> TaskType.BankRecycle(
                itemCode = stored.itemCode ?: "",
                itemName = stored.itemName ?: "",
                quantity = stored.quantity ?: 1,
                craftSkill = stored.craftSkill ?: ""
            )
            "inventory_deposit" -> TaskType.InventoryDeposit(
                itemCode = stored.itemCode ?: "",
                itemName = stored.itemName ?: "",
                quantity = stored.quantity ?: 1
            )
            "inventory_recycle" -> TaskType.InventoryRecycle(
                itemCode = stored.itemCode ?: "",
                itemName = stored.itemName ?: "",
                quantity = stored.quantity ?: 1,
                craftSkill = stored.craftSkill ?: ""
            )
            else -> TaskType.Idle
        }
    }
}
