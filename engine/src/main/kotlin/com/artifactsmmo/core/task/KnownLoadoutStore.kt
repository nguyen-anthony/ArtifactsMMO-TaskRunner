package com.artifactsmmo.core.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class KnownUtilityLoadout(
    val code: String,
    val quantity: Int
)

@Serializable
data class KnownLoadout(
    val characterName: String,
    val exactLevel: Int,
    val monsterCode: String,
    val optimizerVersion: String,
    val gear: Map<String, String>,
    val utilities: Map<String, KnownUtilityLoadout> = emptyMap(),
    val requiredItems: Map<String, Int>,
    val validatedWinRate: Double,
    val averageWinTurns: Double? = null,
    val validatedAtEpochMs: Long
)

@Serializable
private data class KnownLoadoutData(
    val schemaVersion: Int = 1,
    val loadouts: List<KnownLoadout> = emptyList()
)

/** Persistent, versioned store for API-validated solo fight loadouts. */
class KnownLoadoutStore(private val file: File = File("known_loadouts.json")) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Synchronized
    fun find(characterName: String, exactLevel: Int, monsterCode: String, optimizerVersion: String): KnownLoadout? =
        loadAll().firstOrNull {
            it.characterName == characterName &&
                it.exactLevel == exactLevel &&
                it.monsterCode == monsterCode &&
                it.optimizerVersion == optimizerVersion
        }

    @Synchronized
    fun put(loadout: KnownLoadout) {
        val retained = loadAll().filterNot {
            it.characterName == loadout.characterName &&
                it.exactLevel == loadout.exactLevel &&
                it.monsterCode == loadout.monsterCode &&
                it.optimizerVersion == loadout.optimizerVersion
        }
        save(retained + loadout)
    }

    private fun loadAll(): List<KnownLoadout> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<KnownLoadoutData>(file.readText()).loadouts
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(loadouts: List<KnownLoadout>) {
        file.parentFile?.mkdirs()
        val temp = File(file.absolutePath + ".tmp")
        temp.writeText(json.encodeToString(KnownLoadoutData(loadouts = loadouts)))
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
