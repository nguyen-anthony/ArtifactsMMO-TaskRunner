package com.artifactsmmo.client.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Item(
    val name: String,
    val code: String,
    val level: Int,
    val type: String,
    val subtype: String,
    val description: String,
    val conditions: List<Condition> = emptyList(),
    val effects: List<SimpleEffect> = emptyList(),
    val craft: CraftInfo? = null,
    val tradeable: Boolean
)

@Serializable
data class CraftInfo(
    val skill: String? = null,
    val level: Int? = null,
    val items: List<SimpleItem> = emptyList(),
    val quantity: Int? = null
)

@Serializable
data class Monster(
    val name: String,
    val code: String,
    val level: Int,
    val type: String,
    val hp: Int,
    @SerialName("attack_fire") val attackFire: Int,
    @SerialName("attack_earth") val attackEarth: Int,
    @SerialName("attack_water") val attackWater: Int,
    @SerialName("attack_air") val attackAir: Int,
    @SerialName("res_fire") val resFire: Int,
    @SerialName("res_earth") val resEarth: Int,
    @SerialName("res_water") val resWater: Int,
    @SerialName("res_air") val resAir: Int,
    @SerialName("critical_strike") val criticalStrike: Int,
    val initiative: Int,
    val effects: List<SimpleEffect> = emptyList(),
    @SerialName("min_gold") val minGold: Int,
    @SerialName("max_gold") val maxGold: Int,
    val drops: List<DropRate>
)

@Serializable
data class Resource(
    val name: String,
    val code: String,
    val skill: String,
    val level: Int,
    val drops: List<DropRate>
)

@Serializable
data class MapInfo(
    @SerialName("map_id") val mapId: Int,
    val name: String,
    val skin: String,
    val x: Int,
    val y: Int,
    val layer: String,
    val access: MapAccess,
    val interactions: MapInteraction
)

@Serializable
data class MapAccess(
    val type: String,
    val conditions: List<Condition>? = null
)

@Serializable
data class MapInteraction(
    val content: MapContent? = null,
    val transition: MapTransition? = null
)

@Serializable
data class MapContent(
    val type: String,
    val code: String
)

@Serializable
data class MapTransition(
    @SerialName("map_id") val mapId: Int,
    val x: Int,
    val y: Int,
    val layer: String,
    val conditions: List<Condition>? = null
)

@Serializable
data class NPC(
    val name: String,
    val code: String,
    val description: String,
    val type: String
)

@Serializable
data class NPCItem(
    val code: String,
    val npc: String,
    val currency: String,
    @SerialName("buy_price") val buyPrice: Int? = null,
    @SerialName("sell_price") val sellPrice: Int? = null
)

