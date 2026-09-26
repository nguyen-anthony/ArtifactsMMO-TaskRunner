package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Condition
import com.artifactsmmo.client.models.MapInfo
import java.util.PriorityQueue

/**
 * One action in a planned route. Walking between steps is implicit: the character walks
 * (server-side A*) to the step's starting tile inside its current region.
 */
sealed interface RouteStep {
    /** Walk onto [source] and use its transition. */
    data class Transition(val source: MapInfo) : RouteStep {
        val conditions: List<Condition> get() = source.interactions.transition?.conditions.orEmpty()
        val destination: TilePos get() = source.interactions.transition!!.let { TilePos(it.x, it.y, it.layer) }
        override fun toString() = "transition ${source.pos} -> $destination"
    }

    /** Use teleport potion [code] (withdrawing it from the bank first if [fromBank]). */
    data class Potion(val code: String, val landing: TilePos, val fromBank: Boolean) : RouteStep {
        override fun toString() = "potion $code${if (fromBank) " (from bank)" else ""} -> $landing"
    }
}

/** An ordered plan to reach [target]. [cost] is in tile-equivalents. */
data class Route(val target: MapInfo, val steps: List<RouteStep>, val cost: Int) {
    val transitions: List<RouteStep.Transition> get() = steps.filterIsInstance<RouteStep.Transition>()
    override fun toString() = "Route(to=${target.pos}, cost=$cost, steps=$steps)"
}

/**
 * A teleport the planner may use as the first step. [extraCost] covers any detour needed
 * to get the potion (e.g. a bank trip) on top of [RoutePlanner.POTION_COST].
 */
data class PotionOption(val code: String, val landing: TilePos, val fromBank: Boolean, val extraCost: Int = 0)

/**
 * Plans multi-hop routes over a [RegionGraph] with Dijkstra.
 *
 * Nodes are tiles where something happens: the start, transition source/destination tiles
 * and potion landing tiles. Edges:
 *  - **walk** from a node to any transition source reachable on foot (cost = BFS tiles);
 *  - **transition** from a source to its destination ([TRANSITION_COST], plus
 *    [CONSUMING_GATE_PENALTY] if it consumes gold/items) — only if [gateAllowed] says the
 *    conditions can be met;
 *  - **potion** from the start to a landing tile ([POTION_COST] + [PotionOption.extraCost]).
 *
 * The final leg is the walk from the best settled node to the target tile. Pure and
 * synchronous — no API calls — so it's unit-testable against the static map file.
 */
class RoutePlanner(private val graph: RegionGraph) {

    companion object {
        /** A transition costs roughly one tile of walking (cooldown). */
        const val TRANSITION_COST = 1
        /** Discourages gates that consume gold/keys when a free route of similar length exists. */
        const val CONSUMING_GATE_PENALTY = 3
        /** Using a potion must save at least this many tiles to be worth consuming it. */
        const val POTION_COST = TeleportAdvisor.TILE_SAVINGS_THRESHOLD
        /** Safety cap: real routes are ≤ ~5 hops. */
        const val MAX_STEPS = 12
    }

    private data class Via(val prev: TilePos, val step: RouteStep?)

    /** Cheapest route from [from] to [target], or null if unreachable. */
    fun plan(
        from: TilePos,
        target: MapInfo,
        gateAllowed: (List<Condition>) -> Boolean = { true },
        potions: List<PotionOption> = emptyList(),
    ): Route? = planBest(from, listOf(target), gateAllowed, potions)

    /** Cheapest route from [from] to whichever of [targets] is cheapest to reach. */
    fun planBest(
        from: TilePos,
        targets: List<MapInfo>,
        gateAllowed: (List<Condition>) -> Boolean = { true },
        potions: List<PotionOption> = emptyList(),
    ): Route? {
        if (targets.isEmpty()) return null
        val (dist, via) = dijkstra(from, gateAllowed, potions)

        var best: Triple<MapInfo, TilePos, Int>? = null
        for (target in targets) {
            for ((node, d) in dist) {
                if (node.layer != target.layer) continue
                val walk = graph.walkDistance(node, target.pos) ?: continue
                val total = d + walk
                if (best == null || total < best.third) best = Triple(target, node, total)
            }
        }
        val (target, endNode, cost) = best ?: return null

        val steps = ArrayDeque<RouteStep>()
        var cur = endNode
        while (cur != from) {
            val v = via[cur] ?: break
            v.step?.let { steps.addFirst(it) }
            cur = v.prev
        }
        return Route(target, steps.toList(), cost)
    }

    /** Every tile reachable from [from] by walking and allowed transitions. */
    fun reachableTiles(
        from: TilePos,
        gateAllowed: (List<Condition>) -> Boolean = { true },
    ): Set<TilePos> {
        val (dist, _) = dijkstra(from, gateAllowed, emptyList())
        return dist.keys.flatMapTo(HashSet()) { graph.walkDistances(it).keys }
    }

    private fun dijkstra(
        from: TilePos,
        gateAllowed: (List<Condition>) -> Boolean,
        potions: List<PotionOption>,
    ): Pair<Map<TilePos, Int>, Map<TilePos, Via>> {
        val dist = HashMap<TilePos, Int>().apply { put(from, 0) }
        val via = HashMap<TilePos, Via>()
        val hops = HashMap<TilePos, Int>().apply { put(from, 0) }
        val queue = PriorityQueue<Pair<TilePos, Int>>(compareBy { it.second }).apply { add(from to 0) }

        fun relax(prev: TilePos, node: TilePos, cost: Int, step: RouteStep?) {
            val hop = hops.getValue(prev) + if (step != null) 1 else 0
            if (hop > MAX_STEPS) return
            if (cost < (dist[node] ?: Int.MAX_VALUE)) {
                dist[node] = cost; via[node] = Via(prev, step); hops[node] = hop
                queue.add(node to cost)
            }
        }

        for (p in potions) relax(from, p.landing, POTION_COST + p.extraCost, RouteStep.Potion(p.code, p.landing, p.fromBank))

        val settled = HashSet<TilePos>()
        while (queue.isNotEmpty()) {
            val (node, d) = queue.poll()
            if (!settled.add(node) || d > dist.getValue(node)) continue

            val reach = graph.walkDistances(node)
            val region = graph.regionOf(node)
            val sources = if (region != null) graph.transitionSourcesIn(region)
                          else reach.keys.mapNotNull { graph.tile(it) }.filter { it.interactions.transition != null }
            for (src in sources) {
                val walk = reach[src.pos] ?: continue
                // One edge = walk to the source tile (implicit) + cross the transition.
                val step = RouteStep.Transition(src)
                val conditions = step.conditions
                if (!gateAllowed(conditions)) continue
                val penalty = if (conditions.any { it.operator == "cost" }) CONSUMING_GATE_PENALTY else 0
                relax(node, step.destination, d + walk + TRANSITION_COST + penalty, step)
            }
        }
        return dist to via
    }
}
