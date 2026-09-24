package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Condition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads teleport potion definitions from all_teleport_potions.json in the working directory.
 * Used by [TeleportAdvisor] to evaluate outbound/return teleport shortcuts during navigation.
 *
 * Same load pattern as [MonsterProfileStore] — reads a static seasonal snapshot file once
 * at startup, no API calls needed afterward.
 */
object TeleportPotionStore {

    @Serializable
    private data class PotionEffect(val code: String, val value: Int)

    @Serializable
    private data class PotionEntry(
        val code: String,
        val name: String,
        val level: Int,
        val effects: List<PotionEffect> = emptyList(),
        val conditions: List<Condition> = emptyList()
    )

    @Serializable
    private data class PotionListResponse(val data: List<PotionEntry>)

    data class TeleportPotion(
        val code: String,
        val name: String,
        val level: Int,
        val destinationMapId: Int,
        /** Achievement/other conditions required to USE this potion (item.conditions). */
        val conditions: List<Condition>
    )

    private val jsonParser = Json { ignoreUnknownKeys = true }

    @Volatile
    private var potions: List<TeleportPotion> = emptyList()

    /**
     * Load potion definitions from all_teleport_potions.json. Silent no-op if the file
     * is missing or malformed. Called once during [TaskManager.initialize].
     */
    fun load() {
        val file = File("all_teleport_potions.json")
        if (!file.exists()) return
        try {
            val response = jsonParser.decodeFromString<PotionListResponse>(file.readText())
            potions = response.data.mapNotNull { entry ->
                val teleportEffect = entry.effects.find { it.code == "teleport" } ?: return@mapNotNull null
                TeleportPotion(
                    code = entry.code,
                    name = entry.name,
                    level = entry.level,
                    destinationMapId = teleportEffect.value,
                    conditions = entry.conditions
                )
            }
        } catch (_: Exception) {
            // Malformed file — leave potions empty; teleport advisor will simply find no candidates
        }
    }

    fun getPotions(): List<TeleportPotion> = potions
}
