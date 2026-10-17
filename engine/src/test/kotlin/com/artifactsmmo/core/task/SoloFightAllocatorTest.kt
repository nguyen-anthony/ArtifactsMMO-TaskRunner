package com.artifactsmmo.core.task

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SoloFightAllocatorTest {
    /** Each character uses the sword if the pool has one, else a dagger; [swordGain] = its kills/h edge. */
    private fun planner(swordGain: Map<String, Double>, minKph: Double = 0.0, calls: MutableList<String> = mutableListOf()) =
        SoloFightAllocator<String>(plan = { name, bank ->
            calls += name
            if ((bank["sword"] ?: 0) > 0) SoloFightOption("sword", mapOf("sword" to 1), 10.0 + swordGain.getValue(name))
            else SoloFightOption("dagger", emptyMap(), 10.0).takeIf { it.killsPerHour >= minKph }
        })

    @Test
    fun `scarce items go to the characters that lose the most without them`() = runTest {
        val gains = mapOf("alice" to 1.0, "bob" to 8.0, "carol" to 5.0)
        val result = planner(gains).allocate(listOf("alice", "bob", "carol"), mapOf("sword" to 2))
        assertEquals(mapOf("alice" to "dagger", "bob" to "sword", "carol" to "sword"), result.mapValues { it.value.value })
    }

    @Test
    fun `no conflict plans each character once`() = runTest {
        val calls = mutableListOf<String>()
        val result = planner(mapOf("alice" to 1.0, "bob" to 2.0), calls = calls).allocate(listOf("alice", "bob"), mapOf("sword" to 5))
        assertEquals(listOf("alice", "bob"), calls)
        assertEquals(setOf("sword"), result.values.map { it.value }.toSet())
    }

    @Test
    fun `a character with no acceptable fallback is dropped`() = runTest {
        // Without a sword nobody reaches 12 kills/h; bob gains most and keeps the only sword.
        val result = planner(mapOf("alice" to 3.0, "bob" to 6.0), minKph = 12.0).allocate(listOf("alice", "bob"), mapOf("sword" to 1))
        assertEquals(mapOf("bob" to "sword"), result.mapValues { it.value.value })
    }
}
