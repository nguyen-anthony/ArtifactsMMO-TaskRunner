package com.artifactsmmo.core.task

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.abs

data class MonsterResistanceProfile(
    val code: String,
    val resFire: Int,
    val resEarth: Int,
    val resWater: Int,
    val resAir: Int
)

/**
 * Loads monster resistance profiles from all_monsters.json in the working directory.
 * Used by [GearOptimizer] to compute resistance distance between monsters and decide
 * whether a full bank-trip gear optimization is warranted.
 */
object MonsterProfileStore {

    @Serializable
    private data class MonsterEntry(
        val code: String,
        @SerialName("res_fire")  val resFire:  Int = 0,
        @SerialName("res_earth") val resEarth: Int = 0,
        @SerialName("res_water") val resWater: Int = 0,
        @SerialName("res_air")   val resAir:   Int = 0
    )

    @Serializable
    private data class MonsterListResponse(val data: List<MonsterEntry>)

    private val jsonParser = Json { ignoreUnknownKeys = true }

    @Volatile
    private var profiles: Map<String, MonsterResistanceProfile> = emptyMap()

    /**
     * Load profiles from all_monsters.json. Silent no-op if file is missing or malformed.
     * Called once during [TaskManager.initialize].
     */
    fun load() {
        val file = File("all_monsters.json")
        if (!file.exists()) return
        try {
            val response = jsonParser.decodeFromString<MonsterListResponse>(file.readText())
            profiles = response.data.associate { entry ->
                entry.code to MonsterResistanceProfile(
                    code     = entry.code,
                    resFire  = entry.resFire,
                    resEarth = entry.resEarth,
                    resWater = entry.resWater,
                    resAir   = entry.resAir
                )
            }
        } catch (_: Exception) {
            // Malformed file — leave profiles empty; gear optimizer will treat all pairs as distant
        }
    }

    fun getProfile(monsterCode: String): MonsterResistanceProfile? = profiles[monsterCode]

    /**
     * Manhattan distance across 4 resistance values.
     * Used to decide whether a new monster's gear needs a full re-optimization.
     */
    fun resistanceDistance(a: MonsterResistanceProfile, b: MonsterResistanceProfile): Int =
        abs(a.resFire  - b.resFire)  +
        abs(a.resEarth - b.resEarth) +
        abs(a.resWater - b.resWater) +
        abs(a.resAir   - b.resAir)
}
