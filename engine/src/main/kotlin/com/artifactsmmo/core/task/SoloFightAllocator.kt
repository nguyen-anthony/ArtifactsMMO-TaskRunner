package com.artifactsmmo.core.task

/** One character's candidate plan for a solo fight. */
data class SoloFightOption<T>(
    val value: T,
    /** Bank items this plan withdraws. */
    val bankDemand: Map<String, Int>,
    /** Expected kills per hour (win rate / seconds per kill). */
    val killsPerHour: Double,
)

/**
 * Plans several characters' solo fights against ONE shared bank so they don't all pick the
 * same scarce items (e.g. 2 adamantite swords for 5 characters), maximizing the team's total
 * kills per hour.
 *
 *  1. Everyone plans against the whole pool (no extra cost when nothing conflicts).
 *  2. Items demanded beyond the pool are "contested". Characters not touching them keep their
 *     plan. Each contender is re-planned WITHOUT the contested items; the difference in kills
 *     per hour is what the items are worth to it. Contenders that lose the most get them first;
 *     the rest take their fallback plan.
 *  3. Repeat on what's left (fallbacks can collide on other items) for up to [maxRounds],
 *     then settle the remainder sequentially, best first.
 *
 * [plan] returns null when the character can't fight acceptably with that pool (below the
 * event's minimum win rate) — that character is dropped and keeps its normal work.
 */
class SoloFightAllocator<T>(
    private val plan: suspend (character: String, bank: Map<String, Int>) -> SoloFightOption<T>?,
    private val maxRounds: Int = 3,
    private val log: (String) -> Unit = {},
) {
    suspend fun allocate(characters: List<String>, bank: Map<String, Int>): Map<String, SoloFightOption<T>> {
        val locked = linkedMapOf<String, SoloFightOption<T>>()
        val remaining = bank.toMutableMap()
        val current = mutableMapOf<String, SoloFightOption<T>>()

        fun lock(name: String, option: SoloFightOption<T>) {
            locked[name] = option
            option.bankDemand.forEach { (code, q) -> remaining[code] = (remaining[code] ?: 0) - q }
        }
        fun fits(option: SoloFightOption<T>) = option.bankDemand.all { (code, q) -> q <= (remaining[code] ?: 0) }
        suspend fun replan(name: String, pool: Map<String, Int>): SoloFightOption<T>? =
            plan(name, pool.filterValues { it > 0 }).also { if (it == null) log("$name: no acceptable plan with the remaining items — skipped") }

        var pending = characters.distinct().filter { name ->
            replan(name, remaining)?.also { current[name] = it } != null
        }
        var round = 0
        while (pending.isNotEmpty()) {
            // Plans that no longer fit (items given to someone else) are redone first.
            pending = pending.filter { name ->
                if (fits(current.getValue(name))) true
                else replan(name, remaining)?.also { current[name] = it } != null
            }
            val demand = mutableMapOf<String, Int>()
            pending.forEach { n -> current.getValue(n).bankDemand.forEach { (c, q) -> demand.merge(c, q, Int::plus) } }
            val contested = demand.filter { (code, q) -> q > (remaining[code] ?: 0) }.keys
            if (contested.isEmpty()) {
                pending.forEach { lock(it, current.getValue(it)) }
                break
            }
            if (round >= maxRounds) {
                for (name in pending.sortedByDescending { current.getValue(it).killsPerHour }) {
                    val option = current.getValue(name).takeIf { fits(it) } ?: replan(name, remaining) ?: continue
                    lock(name, option)
                }
                break
            }
            val contenders = pending.filter { n -> current.getValue(n).bankDemand.keys.any { it in contested } }
            (pending - contenders.toSet()).forEach { lock(it, current.getValue(it)) }

            val withoutContested = remaining.filterKeys { it !in contested }
            val fallback = contenders.associateWith { replan(it, withoutContested) }
            val loss = contenders.associateWith { current.getValue(it).killsPerHour - (fallback[it]?.killsPerHour ?: 0.0) }
            log("contested ${contested.joinToString()}: " + contenders.joinToString { "$it loses ${"%.1f".format(loss[it])} kills/h without" })

            val next = mutableListOf<String>()
            for (name in contenders.sortedByDescending { loss.getValue(it) }) {
                val option = current.getValue(name)
                if (fits(option)) { lock(name, option); continue }
                val alt = fallback[name] ?: continue
                current[name] = alt
                next += name
            }
            pending = next
            round++
        }
        return locked
    }
}
