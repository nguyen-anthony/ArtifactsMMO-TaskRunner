package com.artifactsmmo.core.task

/** What planners/producers need from the bank reservation ledger (implemented by [BankState]). */
interface BankReserver {
    /** Claims up to [items] for [owner] (replacing its previous claim); returns what was granted. */
    fun reserve(owner: String, characters: Set<String>, items: Map<String, Int>): Map<String, Int>
    fun release(owner: String)

    companion object {
        /** Owner key for claims tied to a queue task (or a group parent task). */
        fun taskOwner(taskId: Long): String = "task:$taskId"
    }
}

/**
 * In-memory bank reservation ledger. Planned work claims the bank items it intends to
 * withdraw, so later planners (other characters, other events, fight/TaskMaster optimization)
 * plan around them instead of all picking the same stack.
 *
 * - A claim belongs to an owner (usually a task, see [BankReserver.taskOwner]) and to the
 *   characters that will withdraw it. Those characters still see the claimed items
 *   ([available]); everyone else sees the bank minus the claim.
 * - Withdrawals by a claiming character [consume] the claim (the bank snapshot itself drops
 *   through the same WebSocket log entry).
 * - Claims are released when their task leaves the worker, and expire after [ttlMillis]
 *   (tasks that never start, e.g. cancelled while pending). No DB table: a restart clears all
 *   claims, which only makes planning less conservative.
 */
class BankReservations(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Claim(
        val owner: String,
        val characters: Set<String>,
        val items: Map<String, Int>,
        val createdAtMillis: Long,
    )

    private val claims = LinkedHashMap<String, Claim>()

    /**
     * Claims up to the requested quantities from the bank minus every OTHER claim (strict:
     * a character's own claims for other tasks count too, so nothing is double-claimed).
     * Replaces [owner]'s previous claim. Returns the granted quantities (may be partial).
     */
    @Synchronized
    fun reserve(owner: String, characters: Set<String>, items: Map<String, Int>, bank: Map<String, Int>): Map<String, Int> {
        prune()
        claims.remove(owner)
        val claimed = totals(claims.values)
        val granted = items.mapNotNull { (code, qty) ->
            val free = (bank[code] ?: 0) - (claimed[code] ?: 0)
            val grant = minOf(qty, free)
            if (grant > 0) code to grant else null
        }.toMap()
        if (granted.isNotEmpty()) claims[owner] = Claim(owner, characters, granted, clock())
        return granted
    }

    /** [bank] minus the claims of everyone except [viewers] (a character sees its own claims). */
    @Synchronized
    fun available(viewers: Set<String>, bank: Map<String, Int>): Map<String, Int> {
        prune()
        val others = totals(claims.values.filter { it.characters.none { c -> c in viewers } })
        if (others.isEmpty()) return bank
        return bank.mapNotNull { (code, qty) ->
            val left = qty - (others[code] ?: 0)
            if (left > 0) code to left else null
        }.toMap()
    }

    /** [character] withdrew [items]: shrink its claims (oldest first). */
    @Synchronized
    fun consume(character: String, items: Map<String, Int>) {
        val remaining = items.toMutableMap()
        for (claim in claims.values.filter { character in it.characters }.toList()) {
            val left = claim.items.toMutableMap()
            for ((code, want) in remaining) {
                val have = left[code] ?: continue
                val used = minOf(have, want)
                if (used <= 0) continue
                if (have - used <= 0) left.remove(code) else left[code] = have - used
                remaining[code] = want - used
            }
            remaining.entries.removeIf { it.value <= 0 }
            if (left.isEmpty()) claims.remove(claim.owner) else claims[claim.owner] = claim.copy(items = left)
            if (remaining.isEmpty()) break
        }
    }

    @Synchronized
    fun release(owner: String): Claim? = claims.remove(owner)

    @Synchronized
    fun all(): List<Claim> { prune(); return claims.values.toList() }

    private fun prune() {
        val cutoff = clock() - ttlMillis
        claims.values.removeIf { it.createdAtMillis < cutoff }
    }

    private fun totals(of: Collection<Claim>): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        of.forEach { c -> c.items.forEach { (code, q) -> out[code] = (out[code] ?: 0) + q } }
        return out
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 30 * 60 * 1000L
    }
}
