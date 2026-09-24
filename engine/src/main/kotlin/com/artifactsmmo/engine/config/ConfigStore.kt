package com.artifactsmmo.engine.config

import com.artifactsmmo.core.task.EventConfig
import com.artifactsmmo.core.task.RaidConfig
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Key/value config documents (the `config_entries` table). Replaces event_config.json and
 * raid_config.json. [kind] groups documents, e.g. "event" keyed by event code.
 */
interface ConfigStore {
    suspend fun list(kind: String): Map<String, JsonElement>
    suspend fun put(kind: String, key: String, value: JsonElement)
    suspend fun delete(kind: String, key: String)
}

object ConfigKinds {
    const val EVENT = "event"
    const val RAID = "raid"
}

private val configJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** Typed access to the documents the engine understands. */
class TypedConfigs(private val store: ConfigStore) {
    suspend fun events(): List<EventConfig> = decodeAll(ConfigKinds.EVENT, EventConfig.serializer())
    suspend fun raids(): List<RaidConfig> = decodeAll(ConfigKinds.RAID, RaidConfig.serializer())

    suspend fun putEvent(c: EventConfig) =
        store.put(ConfigKinds.EVENT, c.eventCode, configJson.encodeToJsonElement(EventConfig.serializer(), c))

    suspend fun putRaid(c: RaidConfig) =
        store.put(ConfigKinds.RAID, c.raidCode, configJson.encodeToJsonElement(RaidConfig.serializer(), c))

    private suspend fun <T> decodeAll(kind: String, s: KSerializer<T>): List<T> =
        store.list(kind).values.mapNotNull { runCatching { configJson.decodeFromJsonElement(s, it) }.getOrNull() }
}
