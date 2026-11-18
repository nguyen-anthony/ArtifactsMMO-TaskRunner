package com.artifactsmmo.client.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CraftRequest(
    val code: String,
    val quantity: Int
)

@Serializable
data class MoveRequest(
    val x: Int,
    val y: Int
)

@Serializable
data class MoveToMapRequest(
    @SerialName("map_id") val mapId: Int
)

@Serializable
data class FightRequest(
    val participants: List<String> = emptyList()
)

@Serializable
data class EquipRequest(
    val code: String,
    val slot: String,
    val quantity: Int = 1
)

@Serializable
data class EquipmentRequest(
    val code: String,
    val slot: String,
    val quantity: Int = 1
)

@Serializable
data class UnequipRequest(
    val slot: String,
    val quantity: Int = 1
)

@Serializable
data class UnequipmentRequest(
    val slot: String,
    val quantity: Int = 1
)

@Serializable
data class UseItemRequest(
    val code: String,
    val quantity: Int = 1
)

@Serializable
data class DeleteItemRequest(
    val code: String,
    val quantity: Int
)

@Serializable
data class DeleteItemData(
    val cooldown: Cooldown,
    val item: SimpleItem,
    val character: Character
)

@Serializable
data class RecycleRequest(
    val code: String,
    val quantity: Int = 1
)

@Serializable
data class BankGoldRequest(
    val quantity: Int
)

@Serializable
data class GESellOrderRequest(
    val code: String,
    val quantity: Int,
    val price: Int
)

@Serializable
data class GEBuyOrderRequest(
    val id: String,
    val quantity: Int
)

@Serializable
data class GECancelOrderRequest(
    val id: String
)

@Serializable
data class Bank(
    val slots: Int,
    val expansions: Int,
    @SerialName("next_expansion_cost") val nextExpansionCost: Int,
    val gold: Int
)

@Serializable
data class BankItemTransactionData(
    val cooldown: Cooldown,
    val items: List<SimpleItem>,
    val bank: List<SimpleItem>,
    val character: Character
)

@Serializable
data class BankGoldTransactionData(
    val cooldown: Cooldown,
    val bank: GoldInfo,
    val character: Character
)

@Serializable
data class GoldInfo(
    val quantity: Int
)

@Serializable
data class GrandExchangeOrder(
    val id: String,
    /** Older API name for [account]. */
    val seller: String = "",
    val account: String = "",
    /** "sell" or "buy". */
    val type: String = "sell",
    val code: String,
    val quantity: Int,
    val price: Int,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class GETransaction(
    val id: String,
    val code: String,
    val quantity: Int,
    val price: Int,
    @SerialName("total_price") val totalPrice: Int
)

@Serializable
data class GETransactionData(
    val cooldown: Cooldown,
    val order: GETransaction,
    val character: Character
)

@Serializable
data class GEOrderCreated(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    val code: String,
    val quantity: Int,
    val price: Int,
    @SerialName("total_price") val totalPrice: Int,
    val tax: Int
)

@Serializable
data class GECreateOrderData(
    val cooldown: Cooldown,
    val order: GEOrderCreated,
    val character: Character
)

@Serializable
data class Task(
    val code: String,
    val type: String,
    val total: Int,
    val rewards: Rewards
)

/** One entry of `GET /tasks/list`: a task the Task Master can hand out. */
@Serializable
data class TaskDefinition(
    val code: String,
    val level: Int,
    val type: String,
    val skill: String? = null,
    @SerialName("min_quantity") val minQuantity: Int = 0,
    @SerialName("max_quantity") val maxQuantity: Int = 0,
)

/** One completed Grand Exchange sale (`GET /grandexchange/history/{code}`). */
@Serializable
data class GEHistoryEntry(
    @SerialName("order_id") val orderId: String = "",
    val seller: String = "",
    val buyer: String = "",
    val code: String,
    val quantity: Int,
    val price: Int,
    @SerialName("sold_at") val soldAt: String = "",
)

@Serializable
data class Rewards(
    val items: List<SimpleItem>,
    val gold: Int
)

@Serializable
data class TaskData(
    val cooldown: Cooldown,
    val task: Task,
    val character: Character
)

@Serializable
data class RewardData(
    val cooldown: Cooldown,
    val rewards: Rewards,
    val character: Character
)

@Serializable
data class RecyclingData(
    val cooldown: Cooldown,
    val details: RecyclingInfo,
    val character: Character
)

@Serializable
data class RecyclingInfo(
    val items: List<Drop>
)

@Serializable
data class NPCTransaction(
    val code: String,
    val quantity: Int,
    val currency: String,
    val price: Int,
    @SerialName("total_price") val totalPrice: Int
)

@Serializable
data class NPCTransactionData(
    val cooldown: Cooldown,
    val transaction: NPCTransaction,
    val character: Character
)

/**
 * Minimal task-progress snapshot returned inside action responses.
 * (Different from the full [Task] which includes rewards.)
 */
@Serializable
data class TaskProgress(
    val code: String = "",
    val type: String = "",
    val total: Int = 0,
    val progress: Int = 0
)

/** Response for /my/{name}/action/task/trade */
@Serializable
data class TaskTradeResponseData(
    val cooldown: Cooldown,
    val trade: SimpleItem? = null,
    val task: TaskProgress? = null,
    val character: Character
)

/** Response for /my/{name}/action/task/cancel */
@Serializable
data class TaskCancelResponseData(
    val cooldown: Cooldown,
    val task: TaskProgress? = null,
    val character: Character
)

/** Response for /my/{name}/action/transition */
@Serializable
data class CharacterTransitionData(
    val cooldown: Cooldown,
    val destination: MapInfo,
    val character: Character
)

