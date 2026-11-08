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

/**
 * Persistent, versioned store for API-validated solo fight loadouts. Every call reads the file,
 * so separate instances (each GearOptimizer, the web API) stay consistent; [LOCK] serializes
 * read-modify-write across them.
 */
class KnownLoadoutStore(private val file: File = File("known_loadouts.json")) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun find(characterName: String, exactLevel: Int, monsterCode: String, optimizerVersion: String): KnownLoadout? =
        synchronized(LOCK) {
            loadAll().firstOrNull {
                it.characterName == characterName &&
                    it.exactLevel == exactLevel &&
                    it.monsterCode == monsterCode &&
                    it.optimizerVersion == optimizerVersion
            }
        }

    fun put(loadout: KnownLoadout) = synchronized(LOCK) {
        val retained = loadAll().filterNot { it.sameKey(loadout) }
        save(retained + loadout)
    }

    fun all(): List<KnownLoadout> = synchronized(LOCK) { loadAll() }

    /** Removes every matching loadout; returns how many were removed. */
    fun removeWhere(predicate: (KnownLoadout) -> Boolean): Int = synchronized(LOCK) {
        val all = loadAll()
        val retained = all.filterNot(predicate)
        if (retained.size != all.size) save(retained)
        all.size - retained.size
    }

    /**
     * Replaces the gear/utilities of the loadout with [edited]'s key, recomputing its required
     * items. Returns the stored loadout, or null if no loadout has that key. Edited loadouts are
     * still verified by a fight simulation before the optimizer reuses them.
     */
    fun edit(edited: KnownLoadout): KnownLoadout? = synchronized(LOCK) {
        val all = loadAll()
        val existing = all.firstOrNull { it.sameKey(edited) } ?: return null
        val gear = edited.gear.filterValues { it.isNotBlank() }
        val utilities = edited.utilities.filterValues { it.code.isNotBlank() && it.quantity > 0 }
        val required = mutableMapOf<String, Int>()
        gear.values.forEach { required.merge(it, 1, Int::plus) }
        utilities.values.forEach { required.merge(it.code, it.quantity, Int::plus) }
        val updated = existing.copy(gear = gear, utilities = utilities, requiredItems = required)
        save(all.map { if (it.sameKey(edited)) updated else it })
        updated
    }

    private fun KnownLoadout.sameKey(o: KnownLoadout) =
        characterName == o.characterName && exactLevel == o.exactLevel &&
            monsterCode == o.monsterCode && optimizerVersion == o.optimizerVersion

    private companion object {
        val LOCK = Any()
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
