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
data class UnequipRequest(
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
    val seller: String,
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

