package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Resource
import com.artifactsmmo.client.utils.CharacterUtils

/**
 * Picks gear for a gathering or crafting task rather than combat performance — there's no
 * fight to simulate, so items are ranked lexicographically by a [Goal]:
 *
 * 1. cooldown reduction for the task's skill (`<skill>_gathering` / `<skill>_crafting`, e.g. the
 *    Season 9 altar doll: -10% time is ~+11% XP *and* drops per hour, which beats any stat);
 * 2. the focus stat (prospecting for rare drops, or wisdom for XP — see [resolveFocus]);
 * 3. the other stat as the tie-breaker (gathering only; prospecting does nothing for crafting).
 *
 * [plan] is pure; [candidatesFor]/[planFor] load the inventory + bank items. Never touches the
 * weapon slot — the gathering/cooking tool is chosen elsewhere ([ActionHelper.ensureToolEquipped]).
 */
object StatGearOptimizer {

    /** Which stat a gathering task maximizes after speed. */
    enum class Focus(val stats: List<String>) {
        /** More/rarer secondary drops (gems, pearls...); wisdom breaks ties. */
        PROSPECTING(listOf("prospecting", "wisdom")),
        /** Faster skill levels; prospecting breaks ties. */
        WISDOM(listOf("wisdom", "prospecting")),
    }

    /** What to maximize: [speedCodes] cooldown reduction first, then [stats] in order. */
    data class Goal(val label: String, val speedCodes: Set<String>, val stats: List<String>) {
        companion object {
            fun gathering(skill: String, focus: Focus) =
                Goal("gathering (${focus.name.lowercase()})", setOf("${skill}_gathering"), focus.stats)

            /** Only wisdom affects crafting XP; prospecting does nothing here. */
            fun crafting(skill: String) = Goal("crafting", setOf("${skill}_crafting"), listOf("wisdom"))
        }
    }

    /**
     * The stat a gathering task should favour. [requested] (from the task) wins; otherwise:
     * prospecting when [resource] can drop something on the [prospectFor] list (a secondary drop,
     * rate > 1 — the main drop is guaranteed so prospecting can't improve it), or when the
     * character earns no XP there anyway (skill 50, or more than 10 levels above the resource);
     * wisdom otherwise. An unknown resource keeps the old behaviour (prospecting).
     */
    fun resolveFocus(requested: Focus?, resource: Resource?, skillLevel: Int, prospectFor: Set<String>): Focus {
        if (requested != null) return requested
        if (resource == null) return Focus.PROSPECTING
        if (skillLevel >= MAX_SKILL_LEVEL || skillLevel - resource.level > XP_LEVEL_GAP) return Focus.PROSPECTING
        return if (resource.drops.any { it.rate > 1 && it.code in prospectFor }) Focus.PROSPECTING else Focus.WISDOM
    }

    private const val MAX_SKILL_LEVEL = 50
    private const val XP_LEVEL_GAP = 10

    /** Slots this optimizer ever touches — every combat slot except the weapon. */
    val STAT_SLOTS = ActionHelper.COMBAT_SLOTS.filter { it.slot != "weapon" }

    /** Cooldown reduction in percent (10 for `-10`) [item] gives [goal]'s skill. */
    private fun speed(item: Item, goal: Goal): Int =
        item.effects.filter { it.code in goal.speedCodes }.maxOfOrNull { -it.value }?.coerceAtLeast(0) ?: 0

    private fun score(item: Item, goal: Goal): List<Int> =
        listOf(speed(item, goal)) + goal.stats.map { stat -> item.effects.firstOrNull { it.code == stat }?.value ?: 0 }

    private fun zeroScore(goal: Goal) = List(goal.stats.size + 1) { 0 }

    /** True if [item] helps [goal] at all (faster, or any positive goal stat). */
    private fun useful(item: Item, goal: Goal): Boolean = score(item, goal).any { it > 0 }

    private fun compareScores(a: List<Int>, b: List<Int>): Int {
        for (i in a.indices) { val c = a[i].compareTo(b[i]); if (c != 0) return c }
        return 0
    }

    /** True if [item]'s equip conditions (typically `level gt N`) are met. */
    private fun equippable(char: Character, item: Item, achievements: Set<String>): Boolean =
        item.conditions.all { TeleportAdvisor.meetsCondition(char, it, achievements) }

    private fun getEquipped(char: Character, slot: String): String = when (slot) {
        "shield" -> char.shieldSlot; "helmet" -> char.helmetSlot; "body_armor" -> char.bodyArmorSlot
        "leg_armor" -> char.legArmorSlot; "boots" -> char.bootsSlot; "ring1" -> char.ring1Slot
        "ring2" -> char.ring2Slot; "amulet" -> char.amuletSlot; "artifact1" -> char.artifact1Slot
        "artifact2" -> char.artifact2Slot; "artifact3" -> char.artifact3Slot; "rune" -> char.runeSlot
        else -> ""
    }

    /**
     * Build the swap plan to maximize [goal] across [STAT_SLOTS].
     *
     * Keeping the current item in a slot is always free (no copy consumed). Swapping a slot
     * to a NEW code draws one copy from that code's inventory+bank pool ([inventoryQty] /
     * [bankQty]) — inventory is drawn from first, so [EquipAction.source] routes correctly
     * without a bank trip when a copy is already on hand. Two ring slots may equip the same
     * code only if a second copy exists in that pool; artifact slots must all differ.
     *
     * Only [candidatesByType] items scoring above the slot's current item are proposed, so a
     * slot already holding the best (or tied) available item is omitted from the result.
     */
    internal fun plan(
        char: Character,
        goal: Goal,
        candidatesByType: Map<String, List<Item>>,
        inventoryQty: (String) -> Int,
        bankQty: (String) -> Int,
    ): List<ActionHelper.EquipAction> {
        val invLeft = mutableMapOf<String, Int>()
        val bankLeft = mutableMapOf<String, Int>()
        fun invRemaining(code: String) = invLeft.getOrPut(code) { inventoryQty(code) }
        fun bankRemaining(code: String) = bankLeft.getOrPut(code) { bankQty(code) }
        fun poolAvailable(code: String) = invRemaining(code) + bankRemaining(code)
        fun draw(code: String): String {
            val fromInv = minOf(1, invRemaining(code))
            invLeft[code] = invRemaining(code) - fromInv
            if (fromInv == 0) bankLeft[code] = bankRemaining(code) - 1
            return if (fromInv > 0) "inventory" else "bank"
        }

        val actions = mutableListOf<ActionHelper.EquipAction>()

        // Slots sharing an item type (ring1/ring2, artifact1-3) are chosen together: pick the
        // best N items overall, keep the ones already equipped, and put new ones only in the
        // slots that lose theirs — so a better item replaces the *weakest* one, not slot 1's.
        for ((type, slots) in STAT_SLOTS.groupBy { it.itemType }) {
            val unique = type == "artifact"
            val byCode = candidatesByType[type].orEmpty().associateBy { it.code }
            fun scoreOf(code: String) = byCode[code]?.let { score(it, goal) } ?: zeroScore(goal)

            data class Pick(val code: String, val slot: String?)   // slot != null: already equipped there
            val units = mutableListOf<Pick>()
            val equipped = slots.map { it.slot to getEquipped(char, it.slot) }.filter { it.second.isNotEmpty() }
            equipped.forEach { (slot, code) -> units += Pick(code, slot) }
            for (item in byCode.values) {
                if (unique && equipped.any { it.second == item.code }) continue
                if (!useful(item, goal)) continue
                val copies = minOf(poolAvailable(item.code), if (unique) 1 else slots.size)
                repeat(copies) { units += Pick(item.code, null) }
            }

            val chosen = units
                .sortedWith(Comparator<Pick> { a, b -> compareScores(scoreOf(b.code), scoreOf(a.code)) }
                    .thenBy { if (it.slot != null) 0 else 1 })
                .let { sorted -> if (unique) sorted.distinctBy { it.code } else sorted }
                .take(slots.size)

            val kept = chosen.mapNotNull { it.slot }.toSet()
            val freeSlots = slots.map { it.slot }.filter { it !in kept }.iterator()
            for (unit in chosen.filter { it.slot == null }) {
                val slot = freeSlots.next()
                actions += ActionHelper.EquipAction(slot, unit.code, source = draw(unit.code))
            }
        }
        // Keep the documented slot order (STAT_SLOTS) for callers and tests.
        val order = STAT_SLOTS.map { it.slot }
        return actions.sortedBy { order.indexOf(it.slot) }
    }

    /**
     * Look up every item [char] could equip in one of [STAT_SLOTS] (currently equipped +
     * inventory + bank), grouped by equip-item type, filtered to what's actually usable
     * ([equippable]) and helps [goal] ([useful]: faster, or at least one point of a goal stat).
     */
    suspend fun candidatesFor(
        char: Character,
        helper: ActionHelper,
        achievements: Set<String>,
        goal: Goal,
    ): Map<String, List<Item>> {
        val types = STAT_SLOTS.map { it.itemType }.toSet()
        val codes = mutableSetOf<String>()
        STAT_SLOTS.forEach { getEquipped(char, it.slot).takeIf(String::isNotEmpty)?.let(codes::add) }
        char.inventory.forEach { if (it.quantity > 0) codes += it.code }
        codes += helper.bankState.snapshot.value.keys

        val items = codes.mapNotNull { code -> runCatching { helper.getItem(code) }.getOrNull() }
        return items
            .filter { it.type in types }
            .filter { equippable(char, it, achievements) }
            .filter { useful(it, goal) }
            .groupBy { it.type }
    }

    /**
     * The goal for gathering [skill] at [resourceCode] (or, when only the wanted item is known,
     * the first [skill] resource dropping [dropCode]), honouring a [requested] focus.
     */
    suspend fun gatheringGoal(
        char: Character,
        helper: ActionHelper,
        skill: String,
        resourceCode: String? = null,
        dropCode: String? = null,
        requested: Focus? = null,
    ): Goal {
        val resource = runCatching {
            when {
                resourceCode != null -> helper.contentCache.getResource(resourceCode)
                dropCode != null -> helper.contentCache.getResourcesByDrop(dropCode).firstOrNull { it.skill == skill }
                else -> null
            }
        }.getOrNull()
        val level = CharacterUtils.getSkillLevel(char, skill) ?: 0
        return Goal.gathering(skill, resolveFocus(requested, resource, level, helper.gearFocus.prospectFor.toSet()))
    }

    /** Convenience wrapper: plan gear for [char] using live inventory/bank state via [helper]. */
    suspend fun planFor(char: Character, helper: ActionHelper, achievements: Set<String>, goal: Goal): List<ActionHelper.EquipAction> {
        val candidates = candidatesFor(char, helper, achievements, goal)
        return plan(char, goal, candidates,
            inventoryQty = { helper.getItemQuantity(char, it) },
            bankQty = { helper.bankState.getQuantity(it) })
    }
}
