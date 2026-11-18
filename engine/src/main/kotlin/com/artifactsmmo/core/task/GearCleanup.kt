package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Item
import kotlinx.serialization.Serializable

/**
 * Bank cleanup settings (config kind `cleanup`, key [KEY]). See [GearCleanup].
 */
@Serializable
data class CleanupConfig(
    /** Item types analysed (weapons exclude tools; the tool kit manages those). */
    val types: List<String> = GEAR_TYPES,
    /** Copies of each obsolete item to keep anyway. */
    val spares: Int = 0,
    /** Also keep items a character will be able to use within this many levels. */
    val lookaheadLevels: Int = 5,
    /** Never clean these up. */
    val keep: List<String> = emptyList(),
    /** Keep gear that's an ingredient of another recipe (e.g. dreadful_staff -> greater_dreadful_staff). */
    val protectIngredients: Boolean = true,
    /** Keep gear used by a saved fight loadout at the character's current level. */
    val protectLoadouts: Boolean = true,
    /** Queue unassigned bank_recycle tasks for recyclable surplus in the bank. */
    val autoRecycle: Boolean = false,
    /** Below thresholds (40) and the tool kit (35): cleanup is filler work. */
    val recyclePriority: Int = 20,
    /** Resources: gear/tools still count as a use down to (character level - margin). */
    val resourceGearMargin: Int = 5,
    /** Resources: food/potions still count as a use down to (character level - margin). */
    val resourceFoodMargin: Int = 10,
    /** Resources: a recipe counts while some character's skill < recipe level + window (0 = ignore XP). */
    val resourceXpWindow: Int = 10,
    /** Resources with outgrown products and at most this many remaining uses are flagged "thin". */
    val resourceThinUses: Int = 5,
    /** Item types that never count as a use (bags: inventory space is bought late). */
    val resourceIgnoreTypes: List<String> = listOf("bag"),
) {
    fun sanitized() = copy(
        types = types.filter { it in GEAR_TYPES }.distinct(),
        spares = spares.coerceAtLeast(0),
        lookaheadLevels = lookaheadLevels.coerceIn(0, 50),
        keep = keep.map(String::trim).filter(String::isNotEmpty).distinct(),
        resourceGearMargin = resourceGearMargin.coerceIn(0, 50),
        resourceFoodMargin = resourceFoodMargin.coerceIn(0, 50),
        resourceXpWindow = resourceXpWindow.coerceIn(0, 50),
        resourceThinUses = resourceThinUses.coerceIn(0, 50),
        resourceIgnoreTypes = resourceIgnoreTypes.map(String::trim).filter(String::isNotEmpty).distinct(),
    )

    companion object {
        const val KEY = "default"
        val GEAR_TYPES = listOf(
            "weapon", "shield", "helmet", "body_armor", "leg_armor", "boots",
            "ring", "amulet", "artifact", "rune", "bag",
        )
    }
}

/** One character as the cleanup sees it. */
data class CleanupHolder(
    val name: String,
    val level: Int,
    /** Skill -> level (for `<skill>_level` item conditions). */
    val skillLevels: Map<String, Int> = emptyMap(),
    val achievements: Set<String> = emptySet(),
    /** Item code -> equipped copies. */
    val equipped: Map<String, Int> = emptyMap(),
    /** Item code -> copies in the inventory. */
    val inventory: Map<String, Int> = emptyMap(),
)

/** One obsolete (dominated) item. */
@Serializable
data class CleanupRow(
    val code: String,
    val name: String,
    val type: String,
    val level: Int,
    val bank: Int,
    val inventory: Int,
    val equipped: Int,
    /** Copies still needed by characters that can't use anything better. */
    val kept: Int,
    val keptFor: List<String> = emptyList(),
    /** Copies nobody needs (owned - kept - spares; 0 when protected). */
    val surplus: Int,
    /** Surplus copies sitting (unreserved) in the bank, i.e. what a recycle can take right now. */
    val recyclable: Int,
    /** Owned items that are at least as good on every stat and better on one. */
    val dominatedBy: List<String>,
    /** Recycle skill when the item can be recycled (weapon/gear/jewelry crafted). */
    val craftSkill: String? = null,
    /** Why the surplus is held back (keep list, ingredient, loadout, threshold, nobody can use it yet). */
    val protectedBy: String? = null,
)

@Serializable
data class CleanupReport(
    val rows: List<CleanupRow>,
    val computedAtMillis: Long = 0,
    /** Resource suggestions (see [ResourceCleanup]); empty when game content couldn't be loaded. */
    val resources: List<ResourceRow> = emptyList(),
)

/**
 * Finds obsolete gear. Item B *dominates* A when they share a slot and, over the union of both
 * items' effects (a missing effect counts as 0), B is at least as good everywhere and strictly
 * better somewhere. Elements fall out naturally: a pure fire weapon never dominates a pure water
 * one, while a fire+earth weapon can dominate a fire-only one. Cooldown effects
 * (`*_gathering`/`*_crafting`) are better when lower; `threat` must match (tank vs. damage gear).
 * For gear with attack (weapons), crit is folded into expected damage per element (crits hit for
 * 1.5×), so skull_staff (40 fire, 5 crit) beats fire_bow (17 fire, 35 crit).
 *
 * Copies are kept for whoever still needs them: characters, highest level first, each claim
 * the non-dominated items ("frontier") among what's left that they can equip, once per slot
 * (two rings, three distinct artifacts). A second pass at level + [CleanupConfig.lookaheadLevels]
 * claims items they'll be able to use soon. Copies of a dominated item nobody claimed are surplus.
 */
object GearCleanup {
    private val SLOTS = mapOf("ring" to 2, "artifact" to 3)
    private val MUST_MATCH = setOf("threat")

    fun isLowerBetter(effect: String) = effect.endsWith("_gathering") || effect.endsWith("_crafting")

    fun isCandidate(item: Item, types: Collection<String>) = item.type in types && item.subtype != "tool"

    fun dominates(b: Item, a: Item): Boolean {
        if (b.code == a.code || b.type != a.type) return false
        val eb = b.effects.associate { it.code to it.value }
        val ea = a.effects.associate { it.code to it.value }
        val codes = eb.keys + ea.keys
        var strictly = false
        // Weapon crit only matters through damage, so judge it as expected attack per element.
        val attacks = codes.filter { it.startsWith("attack_") }
        val foldCrit = attacks.isNotEmpty() && "lifesteal" !in codes
        if (foldCrit) {
            when (val cmp = compareExpectedAttack(attacks, eb, ea)) {
                null -> return false
                else -> strictly = cmp
            }
        }
        for (code in codes) {
            if (foldCrit && (code == CRIT || code in attacks)) continue
            val vb = eb[code] ?: 0
            val va = ea[code] ?: 0
            if (code in MUST_MATCH) {
                if (vb != va) return false
                continue
            }
            val gain = if (isLowerBetter(code)) va - vb else vb - va
            if (gain < 0) return false
            if (gain > 0) strictly = true
        }
        return strictly
    }

    private const val CRIT = "critical_strike"

    /**
     * Expected attack per element is `attack × (1 + 0.5 × crit)`, crit capped at 100% and including
     * whatever crit the rest of the gear adds. The difference is piecewise linear in that extra crit,
     * so checking 0%, 100% and both kinks is exact. null = B is worse somewhere; else whether B is
     * strictly better somewhere.
     */
    private fun compareExpectedAttack(attacks: List<String>, eb: Map<String, Int>, ea: Map<String, Int>): Boolean? {
        val cb = (eb[CRIT] ?: 0).coerceIn(0, 100)
        val ca = (ea[CRIT] ?: 0).coerceIn(0, 100)
        fun mult(crit: Int) = 1.0 + 0.5 * crit.coerceIn(0, 100) / 100.0
        var strictly = false
        for (extra in listOf(0, 100 - cb, 100 - ca, 100)) {
            for (code in attacks) {
                val vb = (eb[code] ?: 0) * mult(cb + extra)
                val va = (ea[code] ?: 0) * mult(ca + extra)
                if (vb < va - 1e-9) return null
                if (vb > va + 1e-9) strictly = true
            }
        }
        return strictly
    }

    /** True if [h] meets every condition of [item] with [boost] extra levels (no conditions = item level). */
    fun canUse(h: CleanupHolder, item: Item, boost: Int = 0): Boolean {
        if (item.conditions.isEmpty()) return h.level + boost >= item.level
        return item.conditions.all { c ->
            if (c.operator == "achievement_unlocked") return@all c.code in h.achievements
            val stat = when {
                c.code == "level" -> h.level
                c.code.endsWith("_level") -> h.skillLevels[c.code.removeSuffix("_level")]
                else -> null
            } ?: return@all true
            val v = stat + boost
            when (c.operator) {
                "gt" -> v > c.value
                "lt" -> v < c.value
                "eq" -> v == c.value
                "ne" -> v != c.value
                else -> true
            }
        }
    }

    /**
     * [protected] maps item code -> reason (keep list, saved loadout, threshold, ...).
     * [bankAvailable] is the bank minus reservations (defaults to [bank]).
     */
    fun analyze(
        items: List<Item>,
        holders: List<CleanupHolder>,
        bank: Map<String, Int>,
        config: CleanupConfig,
        protected: Map<String, String> = emptyMap(),
        bankAvailable: Map<String, Int> = bank,
        nowMillis: Long = 0,
    ): CleanupReport {
        val cfg = config.sanitized()
        val candidates = items.filter { isCandidate(it, cfg.types) }.distinctBy { it.code }
        fun owned(code: String) = (bank[code] ?: 0) + holders.sumOf { (it.equipped[code] ?: 0) + (it.inventory[code] ?: 0) }
        val pool = candidates.associate { it.code to owned(it.code) }.filterValues { it > 0 }
        val ownedItems = candidates.filter { it.code in pool }
        val byType = ownedItems.groupBy { it.type }

        val remaining = pool.toMutableMap()
        val kept = mutableMapOf<String, Int>()
        val keptFor = mutableMapOf<String, MutableSet<String>>()
        val lookahead = cfg.lookaheadLevels
        for (h in holders.sortedWith(compareByDescending<CleanupHolder> { it.level }.thenBy { it.name })) {
            for ((type, typeItems) in byType) {
                val slots = SLOTS[type] ?: 1
                val distinct = type == "artifact"
                // Pass 0: what the character can use now. Pass 1: what it can use soon (not yet usable).
                for (boost in if (lookahead > 0) listOf(0, lookahead) else listOf(0)) {
                    val taken = mutableSetOf<String>()
                    repeat(slots) {
                        val usable = typeItems.filter {
                            remaining.getValue(it.code) > 0 && canUse(h, it, boost) &&
                                !(distinct && it.code in taken) && (boost == 0 || !canUse(h, it))
                        }
                        val frontier = usable.filter { u -> usable.none { o -> dominates(o, u) } }
                        for (f in frontier) {
                            remaining[f.code] = remaining.getValue(f.code) - 1
                            kept.merge(f.code, 1, Int::plus)
                            keptFor.getOrPut(f.code) { linkedSetOf() } += h.name
                            taken += f.code
                        }
                    }
                }
            }
        }

        val ingredients = if (cfg.protectIngredients) {
            items.flatMap { i -> i.craft?.items.orEmpty().map { it.code } }.toSet()
        } else emptySet()
        val keepList = cfg.keep.toSet()

        val rows = ownedItems.mapNotNull { a ->
            val beaters = ownedItems.filter { dominates(it, a) }
            if (beaters.isEmpty()) return@mapNotNull null
            val code = a.code
            val total = pool.getValue(code)
            val k = kept[code] ?: 0
            val reason = when {
                code in keepList -> "keep list"
                code in ingredients -> "recipe ingredient"
                protected[code] != null -> protected[code]
                holders.none { canUse(it, a, lookahead) } -> "no character can use it yet"
                else -> null
            }
            val surplus = if (reason != null) 0 else (total - k - cfg.spares).coerceAtLeast(0)
            val craftSkill = a.craft?.skill?.takeIf { it in BankExecutor.RECYCLABLE_SKILLS }
            CleanupRow(
                code = code, name = a.name, type = a.type, level = a.level,
                bank = bank[code] ?: 0,
                inventory = holders.sumOf { it.inventory[code] ?: 0 },
                equipped = holders.sumOf { it.equipped[code] ?: 0 },
                kept = k, keptFor = keptFor[code]?.toList().orEmpty(),
                surplus = surplus,
                recyclable = minOf(surplus, bankAvailable[code] ?: 0),
                dominatedBy = beaters.sortedWith(compareByDescending<Item> { it.level }.thenBy { it.code }).map { it.code },
                craftSkill = craftSkill,
                protectedBy = reason,
            )
        }.sortedWith(compareByDescending<CleanupRow> { it.surplus }.thenBy { it.type }.thenBy { it.level }.thenBy { it.code })
        return CleanupReport(rows, nowMillis)
    }
}
