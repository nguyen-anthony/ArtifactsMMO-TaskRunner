package com.artifactsmmo.core.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val raidConfigJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

@Serializable
data class RaidConfigData(val raids: List<RaidConfig>)

/** Persists scheduled-raid configuration independently from event configuration. */
class RaidConfigStore(private val filePath: String = "raid_config.json") {
    fun load(): List<RaidConfig> {
        val file = File(filePath)
        if (!file.exists()) return emptyList()
        return try {
            raidConfigJson.decodeFromString<RaidConfigData>(file.readText()).raids
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(configs: List<RaidConfig>) {
        File(filePath).writeText(raidConfigJson.encodeToString(RaidConfigData(configs)))
    }
}
