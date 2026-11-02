package com.artifactsmmo.core.offering

import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Monster
import com.artifactsmmo.client.models.Resource
import kotlin.math.ceil

/** One concrete thing to do next to make progress toward an item. */
sealed interface AcquireStep {
    /** Gather [resourceCode] (a [skill] node) until enough [itemCode] has dropped. */
    data class Gather(val resourceCode: String, val skill: String, val itemCode: String) : AcquireStep
    /** Fight [monsterCode] until enough [itemCode] has dropped. */
    data class Fight(val monsterCode: String, val itemCode: String) : AcquireStep
    /** Craft [crafts] batches of [itemCode]; every ingredient is already owned. */
    data class Craft(val itemCode: String, val skill: String, val crafts: Int) : AcquireStep
}

sealed interface AcquirePlan {
    /** Enough is already owned (inventory + bank). */
    data object Ready : AcquirePlan
    data class Next(val step: AcquireStep, val estimatedSeconds: Double) : AcquirePlan
    data class Infeasible(val reason: String) : AcquirePlan

    val estimatedSecondsOrNull: Double?
        get() = when (this) {
            Ready -> 0.0
            is Next -> estimatedSeconds
            is Infeasible -> null
        }
}

/** What the planner needs to know about the game and one character. */
interface AcquisitionWorld {
    suspend fun item(code: String): Item?
    suspend fun resourcesDropping(code: String): List<Resource>
    suspend fun monstersDropping(code: String): List<Monster>
    fun skillLevel(skill: String): Int
    fun canReach(contentType: String, code: String): Boolean
    /** Expected seconds per kill with the best available loadout; null if not reliably beatable. */
    suspend fun secondsPerKill(monsterCode: String): Double?
    /** Quantity owned (inventory + bank). */
    suspend fun owned(code: String): Int
}

/**
 * Recursive "obtain N of X" planner: use what is owned, else the cheapest of crafting
 * (recursively), gathering or fighting for drops. Costs are rough seconds, used only to
 * compare options. Re-run after every step: the returned [AcquirePlan.Next] is the first
 * leaf of the cheapest tree, so restarts and bank changes are handled naturally.
 *
 * [preferResource] / [preferMonster] (the offering's favor target) get a discount because
 * the favor boosts drops from them.
 */
class AcquisitionPlanner(
    private val world: AcquisitionWorld,
    private val preferResource: String? = null,
    private val preferMonster: String? = null,
) {
    private class Node(val seconds: Double, val next: AcquireStep?)

    private var lastReason = ""

    suspend fun plan(code: String, quantity: Int): AcquirePlan {
        lastReason = "no known source for $code"
        val node = resolve(code, quantity, HashMap(), emptySet()) ?: return AcquirePlan.Infeasible(lastReason)
        return node.next?.let { AcquirePlan.Next(it, node.seconds) } ?: AcquirePlan.Ready
    }

    /** Claims owned quantities from [pool] so sibling branches don't double-count them. */
    private suspend fun resolve(code: String, quantity: Int, pool: MutableMap<String, Int>, path: Set<String>): Node? {
        val have = pool[code] ?: world.owned(code)
        val take = minOf(have, quantity)
        pool[code] = have - take
        val deficit = quantity - take
        if (deficit <= 0) return Node(0.0, null)
        if (code in path || path.size >= MAX_DEPTH) {
            lastReason = "recipe cycle or too deep at $code"
            return null
        }

        var best: Node? = null
        var bestPool: Map<String, Int>? = null
        fun offer(node: Node, newPool: Map<String, Int>? = null) {
            val current = best
            if (current == null || node.seconds < current.seconds) {
                best = node
                bestPool = newPool
            }
        }

        val craft = world.item(code)?.craft
        val craftSkill = craft?.skill
        if (craft != null && craftSkill != null) {
            val level = craft.level ?: 0
            val skillLevel = world.skillLevel(craftSkill)
            if (skillLevel < level) {
                lastReason = "$craftSkill level $skillLevel < $level for $code"
            } else {
                val crafts = ceil(deficit.toDouble() / (craft.quantity ?: 1).coerceAtLeast(1)).toInt()
                val branch = HashMap(pool)
                var seconds = CRAFT_OVERHEAD_SECONDS + crafts * CRAFT_SECONDS
                var next: AcquireStep? = null
                var feasible = true
                for (ingredient in craft.items) {
                    val sub = resolve(ingredient.code, ingredient.quantity * crafts, branch, path + code)
                    if (sub == null) { feasible = false; break }
                    seconds += sub.seconds
                    if (next == null) next = sub.next
                }
                if (feasible) offer(Node(seconds, next ?: AcquireStep.Craft(code, craftSkill, crafts)), branch)
            }
        }

        for (resource in world.resourcesDropping(code)) {
            val drop = resource.drops.firstOrNull { it.code == code } ?: continue
            val skillLevel = world.skillLevel(resource.skill)
            if (skillLevel < resource.level) {
                lastReason = "${resource.skill} level $skillLevel < ${resource.level} for ${resource.code}"
                continue
            }
            if (!world.canReach("resource", resource.code)) { lastReason = "${resource.code} unreachable"; continue }
            var seconds = TRAVEL_SECONDS + deficit / perAction(drop.rate, drop.minQuantity, drop.maxQuantity) * GATHER_SECONDS
            if (resource.code == preferResource) seconds *= FAVOR_DISCOUNT
            offer(Node(seconds, AcquireStep.Gather(resource.code, resource.skill, code)))
        }

        for (monster in world.monstersDropping(code)) {
            if (monster.type == "boss") continue // needs a group
            val drop = monster.drops.firstOrNull { it.code == code } ?: continue
            if (!world.canReach("monster", monster.code)) { lastReason = "${monster.code} unreachable"; continue }
            val perKill = world.secondsPerKill(monster.code)
            if (perKill == null) { lastReason = "can't reliably beat ${monster.code}"; continue }
            var seconds = TRAVEL_SECONDS + deficit / perAction(drop.rate, drop.minQuantity, drop.maxQuantity) * perKill
            if (monster.code == preferMonster) seconds *= FAVOR_DISCOUNT
            offer(Node(seconds, AcquireStep.Fight(monster.code, code)))
        }

        bestPool?.let { pool.putAll(it) }
        return best
    }

    private fun perAction(rate: Int, min: Int, max: Int): Double =
        ((min + max) / 2.0).coerceAtLeast(1.0) / rate.coerceAtLeast(1)

    companion object {
        const val MAX_DEPTH = 6
        const val GATHER_SECONDS = 25.0
        const val CRAFT_SECONDS = 5.0
        const val CRAFT_OVERHEAD_SECONDS = 30.0
        const val TRAVEL_SECONDS = 30.0
        const val FAVOR_DISCOUNT = 0.67
    }
}

/**
 * Hybrid bracket choice: the character's own bracket if feasible, else the cheapest feasible bracket
 * at or below its level. Higher brackets are never chosen, even when their requirement happens to be
 * obtainable at a low level (e.g. algae from a level-1 fishing spot in a level-30 salmon bracket).
 */
object BracketChooser {
    data class Candidate<T>(val offering: T, val levelMin: Int, val levelMax: Int, val plan: AcquirePlan)

    fun <T> choose(level: Int, candidates: List<Candidate<T>>): Candidate<T>? {
        if (candidates.isEmpty()) return null
        val sorted = candidates.sortedBy { it.levelMin }
        // Below every bracket: the lowest one is the only reasonable choice.
        val eligible = sorted.filter { it.levelMin <= level }.ifEmpty { sorted.filter { it.levelMin == sorted.first().levelMin } }
        val matching = eligible.firstOrNull { level in it.levelMin..it.levelMax } ?: eligible.last()
        if (matching.plan !is AcquirePlan.Infeasible) return matching
        return eligible
            .filter { it.plan !is AcquirePlan.Infeasible }
            .minByOrNull { it.plan.estimatedSecondsOrNull ?: Double.MAX_VALUE }
    }
}
