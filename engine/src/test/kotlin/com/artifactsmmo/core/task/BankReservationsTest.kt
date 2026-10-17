package com.artifactsmmo.core.task

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BankReservationsTest {
    private val bank = mapOf("sword" to 2, "potion" to 100)

    @Test
    fun `claims never over-grant and are hidden only from other characters`() {
        val r = BankReservations()
        assertEquals(mapOf("sword" to 2), r.reserve("task:1", setOf("alice"), mapOf("sword" to 2), bank))
        assertEquals(mapOf("potion" to 10), r.reserve("task:2", setOf("bob"), mapOf("sword" to 1, "potion" to 10), bank),
            "no swords left for bob")

        assertEquals(bank, r.available(setOf("alice", "bob"), bank))
        assertEquals(mapOf("sword" to 2, "potion" to 90), r.available(setOf("alice"), bank))
        assertEquals(mapOf("potion" to 100), r.available(setOf("bob"), bank))
        assertEquals(mapOf("potion" to 90), r.available(emptySet(), bank))
    }

    @Test
    fun `re-reserving replaces the owner's claim, release frees it`() {
        val r = BankReservations()
        r.reserve("task:1", setOf("alice"), mapOf("sword" to 2), bank)
        assertEquals(mapOf("sword" to 1), r.reserve("task:1", setOf("alice"), mapOf("sword" to 1), bank))
        assertEquals(mapOf("sword" to 1, "potion" to 100), r.available(setOf("bob"), bank))

        r.release("task:1")
        assertEquals(bank, r.available(setOf("bob"), bank))
    }

    @Test
    fun `withdrawals consume the withdrawing character's claims oldest first`() {
        val r = BankReservations()
        r.reserve("task:1", setOf("alice"), mapOf("potion" to 10), bank)
        r.reserve("task:2", setOf("alice"), mapOf("potion" to 10, "sword" to 1), bank)
        r.reserve("task:3", setOf("bob"), mapOf("potion" to 5), bank)

        r.consume("alice", mapOf("potion" to 15))
        val claims = r.all().associate { it.owner to it.items }
        assertNull(claims["task:1"])
        assertEquals(mapOf("potion" to 5, "sword" to 1), claims["task:2"])
        assertEquals(mapOf("potion" to 5), claims["task:3"], "bob's claim is untouched")
    }

    @Test
    fun `claims expire after the ttl`() {
        var now = 0L
        val r = BankReservations(ttlMillis = 1_000, clock = { now })
        r.reserve("task:1", setOf("alice"), mapOf("sword" to 2), bank)
        now = 999
        assertEquals(mapOf("potion" to 100), r.available(setOf("bob"), bank))
        now = 1_001
        assertEquals(bank, r.available(setOf("bob"), bank))
    }
}
