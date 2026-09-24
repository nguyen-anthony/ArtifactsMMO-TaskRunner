package com.artifactsmmo.domain.task

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * What a task should do. Stored as JSONB in `tasks.spec`; the `type` column holds the same
 * discriminator as [SerialName] so SQL can filter by type without parsing the spec.
 *
 * These mirror the legacy `TaskType` cases 1:1 (see engine `SpecMapper`). Event and boss/raid
 * specs are added with the group executors.
 */
@Serializable
sealed class TaskSpec {
    /** The `type` column value (same as the serial name). */
    val typeName: String get() = TYPE_NAMES.getValue(this::class.simpleName!!)

    @Serializable @SerialName("gather")
    data class Gather(
        val skill: String,
        val resourceCode: String,
        val resourceName: String = resourceCode,
        /** If set, craft this item when inventory is full, then bank. */
        val targetItemCode: String? = null,
        val targetItemName: String? = null,
        /** Fishing only: cook fish before depositing. */
        val cookBeforeDeposit: Boolean = false,
    ) : TaskSpec()

    @Serializable @SerialName("fight")
    data class Fight(
        val monsterCode: String,
        val monsterName: String = monsterCode,
        /** Gear to equip once before the first fight (executed once; tracked in the checkpoint). */
        val equip: List<EquipPlan> = emptyList(),
        val utilities: List<UtilityPlan> = emptyList(),
        /** Prevents the runtime weapon fallback from overriding an intentional loadout. */
        val loadoutOptimized: Boolean = false,
        val dropStrategies: Map<String, DropStrategy> = emptyMap(),
        val defaultDropStrategy: DropStrategy = DropStrategy.BANK_RAW,
    ) : TaskSpec()

    @Serializable @SerialName("craft")
    data class Craft(
        val skill: String,
        val itemCode: String,
        val itemName: String = itemCode,
        val mode: CraftMode,
        val targetQuantity: Int = 0,
    ) : TaskSpec()

    @Serializable @SerialName("task_master")
    data class TaskMaster(
        /** "items" or "monsters". */
        val taskType: String,
    ) : TaskSpec()

    @Serializable @SerialName("bank_withdraw")
    data class BankWithdraw(val itemCode: String, val quantity: Int, val itemName: String = itemCode) : TaskSpec()

    @Serializable @SerialName("bank_recycle")
    data class BankRecycle(val itemCode: String, val quantity: Int, val craftSkill: String, val itemName: String = itemCode) : TaskSpec()

    @Serializable @SerialName("inventory_deposit")
    data class InventoryDeposit(val itemCode: String, val quantity: Int, val itemName: String = itemCode) : TaskSpec()

    @Serializable @SerialName("inventory_recycle")
    data class InventoryRecycle(val itemCode: String, val quantity: Int, val craftSkill: String, val itemName: String = itemCode) : TaskSpec()

    @Serializable @SerialName("bulk_bank_withdraw")
    data class BulkBankWithdraw(val items: List<ItemQty>) : TaskSpec()

    @Serializable @SerialName("bulk_inventory_deposit")
    data class BulkInventoryDeposit(val items: List<ItemQty>) : TaskSpec()

    /**
     * Multi-character boss or raid fight. Enqueued as a group (see `GroupRole`): every slot
     * row carries this same spec, and each member looks up its own [plans] entry by name.
     * The initiator calls the fight endpoint with the other members as `participants`.
     */
    @Serializable @SerialName("boss_fight")
    data class BossFight(
        val monsterCode: String,
        val monsterName: String = monsterCode,
        /** Character name -> provisioning plan (from the co-op optimizer). Missing = no setup. */
        val plans: Map<String, MemberPlan> = emptyMap(),
        /** Non-null for scheduled raids (navigates to the raid tile, respects the window). */
        val raidCode: String? = null,
        val scheduledStartAtMillis: Long? = null,
        val scheduledEndAtMillis: Long? = null,
        val dropStrategies: Map<String, DropStrategy> = emptyMap(),
        val defaultDropStrategy: DropStrategy = DropStrategy.BANK_RAW,
    ) : TaskSpec()

    @Serializable @SerialName("event_gather")
    data class EventGather(
        val eventCode: String,
        val resourceCode: String,
        val skill: String,
        val map: EventMap,
        val resourceName: String = resourceCode,
    ) : TaskSpec()

    @Serializable @SerialName("event_npc")
    data class EventNpc(
        val eventCode: String,
        val npcCode: String,
        val map: EventMap,
        val sell: List<ItemQty> = emptyList(),
        val buy: List<ItemQty> = emptyList(),
        val npcName: String = npcCode,
    ) : TaskSpec()

    @Serializable @SerialName("event_fight")
    data class EventFight(
        val eventCode: String,
        val monsterCode: String,
        val map: EventMap,
        val monsterName: String = monsterCode,
        val equip: List<EquipPlan> = emptyList(),
        val utilities: List<UtilityPlan> = emptyList(),
        val dropStrategies: Map<String, DropStrategy> = emptyMap(),
        val defaultDropStrategy: DropStrategy = DropStrategy.BANK_RAW,
    ) : TaskSpec()

    /** The event code if this is an event task (used to stop it when the event ends). */
    fun eventCodeOrNull(): String? = when (this) {
        is EventGather -> eventCode
        is EventNpc -> eventCode
        is EventFight -> eventCode
        else -> null
    }

    fun toJson(): JsonObject = SpecJson.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        /** Encodes the class discriminator as "kind" inside the JSON; the DB also stores it in `type`. */
        val SpecJson = Json { ignoreUnknownKeys = true; classDiscriminator = "kind"; encodeDefaults = true }

        fun fromJson(json: JsonObject): TaskSpec = SpecJson.decodeFromJsonElement(serializer(), json)

        private val TYPE_NAMES = mapOf(
            "Gather" to "gather", "Fight" to "fight", "Craft" to "craft", "TaskMaster" to "task_master",
            "BankWithdraw" to "bank_withdraw", "BankRecycle" to "bank_recycle",
            "InventoryDeposit" to "inventory_deposit", "InventoryRecycle" to "inventory_recycle",
            "BulkBankWithdraw" to "bulk_bank_withdraw", "BulkInventoryDeposit" to "bulk_inventory_deposit",
            "BossFight" to "boss_fight", "EventGather" to "event_gather", "EventNpc" to "event_npc",
            "EventFight" to "event_fight",
        )
    }
}

/** Per-member boss/raid provisioning, done once before the first fight. */
@Serializable
data class MemberPlan(
    val equip: List<EquipPlan> = emptyList(),
    val utilities: List<UtilityPlan> = emptyList(),
    /** Potion stacks carried in inventory to refill utility slots mid-loop. */
    val reservePotions: Map<String, Int> = emptyMap(),
    val foodCode: String? = null,
    val foodQuantity: Int = 0,
    /** Items consumed by the dungeon entry transition (e.g. keys). */
    val transitionCosts: Map<String, Int> = emptyMap(),
    /** One spare set of keys for re-entry after a restock trip. */
    val spareKeys: Map<String, Int> = emptyMap(),
)

/** Where an ephemeral event tile is (the map cache doesn't know about event overlays). */
@Serializable
data class EventMap(val x: Int, val y: Int, val layer: String = "overworld")

@Serializable
data class ItemQty(val code: String, val quantity: Int)

/** One gear slot to fill. [source] is "inventory", "bank" or "craftable". */
@Serializable
data class EquipPlan(val slot: String, val itemCode: String, val source: String)

/** One utility slot to fill. [source] is "inventory" or "bank". */
@Serializable
data class UtilityPlan(val slot: String, val itemCode: String, val quantity: Int, val source: String)

@Serializable
enum class CraftMode {
    /** Craft, recycle for recovered materials, repeat until nothing left. */
    RECYCLE,
    /** Craft a target quantity and deposit to bank. */
    BANK,
}

@Serializable
enum class DropStrategy { COOK_AND_USE, COOK_AND_BANK, BANK_RAW }
