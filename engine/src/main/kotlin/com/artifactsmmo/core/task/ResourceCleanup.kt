package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Item
import kotlinx.serialization.Serializable

/** A Task Master items task target (`GET /tasks/list`, type `items`). */
@Serializable
data class ItemTaskTarget(val code: String, val level: Int, val skill: String? = null)

/** One NPC trade entry; [permanent] = the NPC has a fixed map tile (not an event merchant). */
@Serializable
data class NpcTrade(
    val npc: String,
    val npcName: String = npc,
    val code: String,
    val currency: String,
    val buyPrice: Int? = null,
    val sellPrice: Int? = null,
    val permanent: Boolean = false,
)

/** Static game content the resource analysis needs. */
data class ResourceContent(
    val items: List<Item>,
    val tasks: List<ItemTaskTarget> = emptyList(),
    val npcTrades: List<NpcTrade> = emptyList(),
)

/**
 * A remaining use of a resource. [path] runs from the resource to [code]; [perUnit] is how many
 * of the resource one [code] takes, so [crafts] = owned / perUnit.
 */
@Serializable
data class ResourceUse(
    val code: String,
    val name: String,
    val type: String,
    val level: Int,
    /** gear | tool | consumable | xp | other | npc | task */
    val kind: String,
    val reason: String,
    val path: List<String>,
    val perUnit: Double,
    val crafts: Int,
)

@Serializable
data class NpcSellOffer(val npc: String, val npcName: String, val price: Int, val currency: String, val permanent: Boolean)

/** A resource that looks unneeded ([ResourceCleanup.UNUSED], [ResourceCleanup.NO_RECIPE]) or nearly so ([ResourceCleanup.THIN]). */
@Serializable
data class ResourceRow(
    val code: String,
    val name: String,
    val subtype: String,
    val level: Int,
    val bank: Int,
    val inventory: Int,
    val verdict: String,
    /** Uses still worth something at your levels (thin rows only). */
    val uses: List<ResourceUse> = emptyList(),
    /** Task Master items tasks this resource (or something crafted from it) can satisfy. */
    val taskTargets: List<ResourceUse> = emptyList(),
    /** Highest level among everything it can end up in (0 = no recipe uses). */
    val maxRecipeLevel: Int = 0,
    val npcSell: List<NpcSellOffer> = emptyList(),
    val protectedBy: String? = null,
)

/**
 * Suggests resources you no longer need. Each resource is followed up the recipe graph through
 * intermediates (ore -> bar, wood -> plank/sap) to what it finally becomes. A final product still
 * counts as a use when some character:
 * - has trained the craft skill (above 1) and can earn XP from it (skill < recipe level + [CleanupConfig.resourceXpWindow]);
 * - could wear it (gear/tool level >= character's relevant level - [CleanupConfig.resourceGearMargin]);
 * - would eat/drink it (consumable level >= level - [CleanupConfig.resourceFoodMargin]).
 * Being an NPC currency is a use too. Task Master targets don't count as a use but are called out.
 *
 * Verdicts: [NO_RECIPE] (not an ingredient, currency or task target anywhere), [UNUSED] (nothing useful
 * at the end of any chain), [THIN] (some products are outgrown and at most [CleanupConfig.resourceThinUses]
 * uses remain, or only Task Master targets remain). Others are omitted.
 */
object ResourceCleanup {
    const val UNUSED = "unused"
    const val NO_RECIPE = "no_recipe"
    const val THIN = "thin"

    private val SKIPPED_SUBTYPES = setOf("key")
    private val EQUIPPABLE = setOf(
        "weapon", "shield", "helmet", "body_armor", "leg_armor", "boots",
        "ring", "amulet", "artifact", "rune", "bag",
    )
    private val CONSUMABLE = setOf("consumable", "utility")

    private data class Edge(val product: Item, val qty: Double)
    private data class Node(val item: Item, val path: List<String>, val perUnit: Double)

    fun analyze(
        content: ResourceContent,
        holders: List<CleanupHolder>,
        bank: Map<String, Int>,
        config: CleanupConfig,
        protected: Map<String, String> = emptyMap(),
    ): List<ResourceRow> {
        val cfg = config.sanitized()
        val byCode = content.items.associateBy { it.code }
        val usedIn = mutableMapOf<String, MutableList<Edge>>()
        for (p in content.items) {
            val craft = p.craft ?: continue
            val yield = (craft.quantity ?: 1).coerceAtLeast(1)
            craft.items.forEach { usedIn.getOrPut(it.code) { mutableListOf() } += Edge(p, it.quantity.toDouble() / yield) }
        }
        val targets = content.tasks.associateBy { it.code }
        val currencyFor = content.npcTrades.filter { it.buyPrice != null && it.currency != "gold" }.groupBy { it.currency }
        val sellOffers = content.npcTrades.filter { it.sellPrice != null }.groupBy { it.code }
        val keep = cfg.keep.toSet()

        fun owned(code: String) = (bank[code] ?: 0) + holders.sumOf { it.inventory[code] ?: 0 }

        fun usefulReason(p: Item): Pair<String, String>? {
            val craft = p.craft
            val craftSkill = craft?.skill
            val craftLevel = craft?.level
            if (cfg.resourceXpWindow > 0 && craftSkill != null && craftLevel != null) {
                // Only characters who have trained the skill count: a level-1 skill nobody crafts
                // would otherwise keep every low-level material alive.
                val learner = holders.filter {
                    val lvl = it.skillLevels[craftSkill] ?: 0
                    lvl > 1 && lvl < craftLevel + cfg.resourceXpWindow
                }.minByOrNull { it.skillLevels[craftSkill] ?: 0 }
                if (learner != null) {
                    return "xp" to "$craftSkill XP (${learner.name} ${learner.skillLevels[craftSkill] ?: 0})"
                }
            }
            return when (p.type) {
                in EQUIPPABLE -> {
                    val skill = p.conditions.firstOrNull { it.code.endsWith("_level") && it.code != "level" }
                        ?.code?.removeSuffix("_level")
                    val user = holders.filter { h ->
                        val lvl = if (skill != null) h.skillLevels[skill] ?: 0 else h.level
                        lvl <= p.level + cfg.resourceGearMargin
                    }.minByOrNull { if (skill != null) it.skillLevels[skill] ?: 0 else it.level }
                    when {
                        user == null -> null
                        p.subtype == "tool" -> "tool" to "tool for ${user.name}"
                        else -> "gear" to "gear for ${user.name} (${user.level})"
                    }
                }
                in CONSUMABLE -> holders.filter { it.level <= p.level + cfg.resourceFoodMargin }.minByOrNull { it.level }
                    ?.let { "consumable" to "${p.type} for ${it.name} (${it.level})" }
                "resource" -> null
                else -> "other" to p.type
            }
        }

        val rows = mutableListOf<ResourceRow>()
        for (r in content.items) {
            if (r.type != "resource" || r.subtype in SKIPPED_SUBTYPES) continue
            val total = owned(r.code)
            if (total <= 0) continue
            fun crafts(per: Double) = if (per <= 0) 0 else (total / per).toInt()
            fun use(i: Item, kind: String, reason: String, n: Node) =
                ResourceUse(i.code, i.name, i.type, i.level, kind, reason, n.path, n.perUnit, crafts(n.perUnit))

            val uses = mutableListOf<ResourceUse>()
            val tasks = mutableListOf<ResourceUse>()
            val feeds = mutableListOf<String>()
            var maxLevel = 0
            var outgrown = 0
            val start = Node(r, listOf(r.code), 1.0)
            targets[r.code]?.let { tasks += use(r, "task", taskReason(it), start) }
            currencyFor[r.code]?.forEach { t ->
                val n = start.copy(perUnit = t.buyPrice!!.toDouble())
                uses += ResourceUse(t.code, byCode[t.code]?.name ?: t.code, byCode[t.code]?.type ?: "", byCode[t.code]?.level ?: 0,
                    "npc", "currency at ${t.npcName}${if (t.permanent) "" else " (event)"}", n.path + t.code, n.perUnit, crafts(n.perUnit))
            }

            val seen = mutableSetOf(r.code)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                for (edge in usedIn[node.item.code].orEmpty()) {
                    val p = edge.product
                    if (!seen.add(p.code) || p.type in cfg.resourceIgnoreTypes) continue
                    val next = Node(p, node.path + p.code, node.perUnit * edge.qty)
                    maxLevel = maxOf(maxLevel, p.level)
                    protected[p.code]?.let { feeds += "${p.code}: $it" }
                    targets[p.code]?.let { tasks += use(p, "task", taskReason(it), next) }
                    usefulReason(p)?.let { (kind, why) -> uses += use(p, kind, why, next) }
                        ?: run { if (p.type != "resource") outgrown++ }
                    if (p.type == "resource") queue += next
                }
            }

            val hasRecipes = usedIn[r.code].orEmpty().any { it.product.type !in cfg.resourceIgnoreTypes }
            val counted = uses.count { it.kind == "npc" || byCode[it.code]?.type != "resource" }
            // Thin = most of what it makes is outgrown and only a few uses remain (spruce_wood: planks
            // only feed outgrown gear, sap still feeds potions). Few recipes that all still matter isn't thin.
            val verdict = when {
                uses.isEmpty() && tasks.isEmpty() -> if (hasRecipes) UNUSED else NO_RECIPE
                uses.isEmpty() -> THIN
                outgrown > 0 && counted <= cfg.resourceThinUses -> THIN
                else -> null
            } ?: continue
            val reason = when {
                r.code in keep -> "keep list"
                protected[r.code] != null -> protected[r.code]
                feeds.isNotEmpty() -> "feeds ${feeds.first()}"
                else -> null
            }
            rows += ResourceRow(
                code = r.code, name = r.name, subtype = r.subtype, level = r.level,
                bank = bank[r.code] ?: 0, inventory = holders.sumOf { it.inventory[r.code] ?: 0 },
                verdict = verdict,
                uses = uses.sortedWith(compareByDescending<ResourceUse> { it.level }.thenBy { it.code }),
                taskTargets = tasks,
                maxRecipeLevel = maxLevel,
                npcSell = sellOffers[r.code].orEmpty().map {
                    NpcSellOffer(it.npc, it.npcName, it.sellPrice!!, it.currency, it.permanent)
                }.sortedWith(compareByDescending<NpcSellOffer> { it.permanent }.thenByDescending { it.price }),
                protectedBy = reason,
            )
        }
        val order = listOf(UNUSED, NO_RECIPE, THIN)
        return rows.sortedWith(
            compareBy<ResourceRow> { order.indexOf(it.verdict) }.thenByDescending { it.bank + it.inventory }.thenBy { it.code }
        )
    }

    private fun taskReason(t: ItemTaskTarget) = "Task Master item (${t.skill ?: "items"} L${t.level})"
}
