package com.artifactsmmo.core.task

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnownLoadoutStoreTest {
    @Test
    fun persistsAndLoadsExactCharacterLevelMonsterRecord() {
        val directory = Files.createTempDirectory("known-loadouts-test").toFile()
        val store = KnownLoadoutStore(directory.resolve("known_loadouts.json"))
        val loadout = KnownLoadout(
            characterName = "Ront",
            exactLevel = 40,
            monsterCode = "lich",
            optimizerVersion = "test-v1",
            gear = mapOf("ring1" to "malefic_ring", "ring2" to "malefic_ring"),
            utilities = mapOf("utility1" to KnownUtilityLoadout("health_potion", 100)),
            requiredItems = mapOf("malefic_ring" to 2, "health_potion" to 100),
            validatedWinRate = 1.0,
            averageWinTurns = 38.0,
            validatedAtEpochMs = 1L
        )

        store.put(loadout)

        assertEquals(loadout, store.find("Ront", 40, "lich", "test-v1"))
        assertNull(store.find("Ront", 41, "lich", "test-v1"))
    }

    @Test
    fun replacesMatchingRecordInsteadOfAppendingDuplicate() {
        val directory = Files.createTempDirectory("known-loadouts-replace-test").toFile()
        val store = KnownLoadoutStore(directory.resolve("known_loadouts.json"))
        val initial = KnownLoadout(
            characterName = "Ront",
            exactLevel = 40,
            monsterCode = "lich",
            optimizerVersion = "test-v1",
            gear = mapOf("weapon" to "old_weapon"),
            requiredItems = mapOf("old_weapon" to 1),
            validatedWinRate = 0.9,
            validatedAtEpochMs = 1L
        )
        val replacement = initial.copy(
            gear = mapOf("weapon" to "new_weapon"),
            requiredItems = mapOf("new_weapon" to 1),
            validatedWinRate = 1.0,
            validatedAtEpochMs = 2L
        )

        store.put(initial)
        store.put(replacement)

        assertEquals(replacement, store.find("Ront", 40, "lich", "test-v1"))
    }
}
