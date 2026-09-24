package com.artifactsmmo.core.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val eventConfigJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

@Serializable
data class EventConfigData(val events: List<EventConfig>)

/**
 * Persists event configurations to a JSON file so they survive restarts.
 * Same pattern as [TaskStore].
 */
class EventConfigStore(private val filePath: String = "event_config.json") {

    fun load(): List<EventConfig> {
        val file = File(filePath)
        if (!file.exists()) return emptyList()
        return try {
            eventConfigJson.decodeFromString<EventConfigData>(file.readText()).events
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(configs: List<EventConfig>) {
        File(filePath).writeText(eventConfigJson.encodeToString(EventConfigData(configs)))
    }
}
