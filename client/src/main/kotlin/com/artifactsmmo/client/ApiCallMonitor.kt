package com.artifactsmmo.client

import java.util.concurrent.ConcurrentHashMap

/**
 * TEMPORARY diagnostic instrumentation — counts API calls per (method, normalized path)
 * and prints a sorted summary every [DUMP_INTERVAL_MS]. Used to empirically identify
 * which endpoint(s) are responsible for exhausting the "game data" 2000/hour rate limit.
 *
 * Path normalization strips query strings and replaces the trailing path segment with
 * "{code}" when it looks like a dynamic item/monster/resource/npc code or numeric id, so
 * calls like "/items/iron_sword" and "/items/copper_ore" are grouped under "/items/{code}".
 *
 * Remove this once the root cause is identified and fixed.
 */
object ApiCallMonitor {
    private val counts = ConcurrentHashMap<String, Long>()
    private val lock = Any()

    @Volatile
    private var windowStart = System.currentTimeMillis()

    private const val DUMP_INTERVAL_MS = 60_000L

    /** Known static (non-dynamic) path segments — kept as-is during normalization. */
    private val KNOWN_STATIC_SEGMENTS = setOf(
        "achievements", "badges", "skins", "season_rewards", "effects", "events", "active",
        "grandexchange", "history", "orders", "items", "leaderboard", "characters", "accounts",
        "maps", "id", "monsters", "npcs", "details", "raids", "resources",
        "tasks", "list", "rewards", "gems_shop", "payment", "success", "cancelled",
        "my", "action", "move", "fight", "gather", "craft", "bank", "equipment", "unequip",
        "equip", "use", "rest", "give", "simulation", "gold", "deposit", "withdraw",
        "logout", "bg"
    )

    fun record(method: String, path: String) {
        val key = "$method ${normalize(path)}"
        counts.merge(key, 1L, Long::plus)
        maybeDump()
    }

    private fun normalize(path: String): String {
        val withoutQuery = path.substringBefore('?')
        val segments = withoutQuery.trim('/').split('/')
        if (segments.isEmpty() || segments == listOf("")) return "/"
        val normalizedSegments = segments.mapIndexed { idx, seg ->
            when {
                seg.isEmpty() -> seg
                seg.toIntOrNull() != null -> "{id}"
                seg in KNOWN_STATIC_SEGMENTS -> seg
                idx == segments.lastIndex -> "{code}"
                else -> seg
            }
        }
        return "/" + normalizedSegments.joinToString("/")
    }

    private fun maybeDump() {
        val now = System.currentTimeMillis()
        if (now - windowStart < DUMP_INTERVAL_MS) return
        synchronized(lock) {
            val elapsed = now - windowStart
            if (elapsed < DUMP_INTERVAL_MS) return  // another thread already dumped this window
            val snapshot = counts.entries.toList()
            counts.clear()
            windowStart = now

            val total = snapshot.sumOf { it.value }
            println("[ApiCallMonitor] ========== ${elapsed / 1000}s window: $total total API calls ==========")
            for ((key, count) in snapshot.sortedByDescending { it.value }.take(25)) {
                println("[ApiCallMonitor]   $count  $key")
            }
            println("[ApiCallMonitor] ========================================================")
        }
    }
}
