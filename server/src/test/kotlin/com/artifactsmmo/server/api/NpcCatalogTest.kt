package com.artifactsmmo.server.api

import kotlin.test.Test
import kotlin.test.assertEquals

class NpcCatalogTest {
    @Test fun onlyNpcsWithAStaticTileThatAreNotEventSpawnsArePermanent() {
        val npcs = listOf(
            NpcDto("rune_vendor", "Rune Vendor", "merchant"),
            NpcDto("fish_merchant", "Fish Merchant", "merchant"),
            NpcDto("tasks_trader", "Tasks Trader", "trader"),
            // Event NPC whose spawn also appears as a map tile (e.g. a stale snapshot).
            NpcDto("nomadic_merchant", "Nomadic Merchant", "merchant"),
        )
        val result = classifyNpcs(
            npcs,
            staticTileCodes = setOf("rune_vendor", "tasks_trader", "nomadic_merchant"),
            eventNpcCodes = setOf("fish_merchant", "nomadic_merchant"),
        ).associate { it.code to it.permanent }

        assertEquals(
            mapOf("rune_vendor" to true, "tasks_trader" to true, "fish_merchant" to false, "nomadic_merchant" to false),
            result,
        )
    }
}
