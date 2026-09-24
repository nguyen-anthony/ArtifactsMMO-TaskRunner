package com.artifactsmmo.server

import com.artifactsmmo.core.task.EventConfig
import com.artifactsmmo.core.task.RaidConfig
import com.artifactsmmo.engine.config.ConfigKinds
import com.artifactsmmo.engine.config.ConfigStore
import com.artifactsmmo.engine.config.TypedConfigs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One-time import of the desktop app's `event_config.json` / `raid_config.json` into
 * `config_entries`. Only runs for a kind that is still empty, so edits made later in the
 * web UI are never overwritten. tasks.json is not imported: queue tasks replace it.
 */
object LegacyConfigImporter {
    @Serializable private data class Events(val events: List<EventConfig>)
    @Serializable private data class Raids(val raids: List<RaidConfig>)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun import(store: ConfigStore, dir: File, log: (String) -> Unit = {}) {
        val typed = TypedConfigs(store)
        val events = File(dir, "event_config.json")
        if (events.exists() && store.list(ConfigKinds.EVENT).isEmpty()) {
            val list = json.decodeFromString(Events.serializer(), events.readText()).events
            list.forEach { typed.putEvent(it) }
            log("Imported ${list.size} event configs from ${events.path}")
        }
        val raids = File(dir, "raid_config.json")
        if (raids.exists() && store.list(ConfigKinds.RAID).isEmpty()) {
            val list = json.decodeFromString(Raids.serializer(), raids.readText()).raids
            list.forEach { typed.putRaid(it) }
            log("Imported ${list.size} raid configs from ${raids.path}")
        }
    }
}
