package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Item

/**
 * Picks gear to maximize a stat total (prospecting and/or wisdom) rather than combat
 * performance — used for gathering and crafting tasks, where there's no fight to simulate.
 *
 * Pure and synchronous (no API calls): callers pass in the items the character can equip
 * (usually its inventory + the bank, already loaded). Never touches the weapon slot — for
 * gathering the best tool is chosen elsewhere ([ActionHelper.ensureToolEquipped]; voidstone
 * tools already carry +100 prospecting themselves), and crafting doesn't use a weapon at all.
 */
object StatGearOptimizer {

    /**
     * What to maximize, and in what order ties are broken.
     *
     * Follow-up: next season is expected to add cooking-speed gear (cooldown reduction).
     * When it does, add a COOKING priority here (that stat first, wisdom as tie-breaker) and
     * route cooking tasks to it instead of CRAFTING — see FOLLOW_UPS.md.
     */
    enum class Priority(val weights: List<String>) {
        /** Gathering: prospecting for more/rarer drops, wisdom as the tie-breaker (gathering XP too). */
        GATHERING(listOf("prospecting", "wisdom")),
        /** Crafting (all skills): only wisdom affects crafting XP; prospecting does nothing here. */
        CRAFTING(listOf("wisdom")),
    }

    /** Slots this optimizer ever touches — every combat slot except the weapon. */
    val STAT_SLOTS = ActionHelper.COMBAT_SLOTS.filter { it.slot != "weapon" }

    private fun score(item: Item, weights: List<String>): List<Int> =
        weights.map { stat -> item.effects.firstOrNull { it.code == stat }?.value ?: 0 }

    private val zeroScore = { weights: List<String> -> List(weights.size) { 0 } }

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
     * Build the swap plan to maximize [priority] across [STAT_SLOTS].
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
        priority: Priority,
        candidatesByType: Map<String, List<Item>>,
        inventoryQty: (String) -> Int,
        bankQty: (String) -> Int,
    ): List<ActionHelper.EquipAction> {
        val weights = priority.weights
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
        val usedArtifacts = mutableSetOf<String>()

        for (slotInfo in STAT_SLOTS) {
            val current = getEquipped(char, slotInfo.slot)
            val isArtifact = slotInfo.itemType == "artifact"
            val currentUsable = current.isNotEmpty() && (!isArtifact || current !in usedArtifacts)

            val candidates = candidatesByType[slotInfo.itemType].orEmpty()
                .filter { it.code != current }                     // "keep current" handled below
                .filter { !isArtifact || it.code !in usedArtifacts }
                .filter { poolAvailable(it.code) > 0 }

            val currentScore = candidatesByType[slotInfo.itemType].orEmpty()
                .find { it.code == current }?.let { score(it, weights) } ?: zeroScore(weights)
            val best = candidates.maxWithOrNull(Comparator { a, b -> compareScores(score(a, weights), score(b, weights)) })

            if (best != null && compareScores(score(best, weights), currentScore) > 0) {
                actions += ActionHelper.EquipAction(slotInfo.slot, best.code, source = draw(best.code))
                if (isArtifact) usedArtifacts += best.code
            } else if (isArtifact && currentUsable) {
                usedArtifacts += current
            }
        }
        return actions
    }

    /**
     * Look up every item [char] could equip in one of [STAT_SLOTS] (currently equipped +
     * inventory + bank), grouped by equip-item type, filtered to what's actually usable
     * ([equippable]) and gives at least one point of [priority]'s stats.
     */
    suspend fun candidatesFor(
        char: Character,
        helper: ActionHelper,
        achievements: Set<String>,
        priority: Priority,
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
            .filter { priority.weights.any { stat -> it.effects.any { e -> e.code == stat && e.value > 0 } } }
            .groupBy { it.type }
    }

    /** Convenience wrapper: plan gear for [char] using live inventory/bank state via [helper]. */
    suspend fun planFor(char: Character, helper: ActionHelper, achievements: Set<String>, priority: Priority): List<ActionHelper.EquipAction> {
        val candidates = candidatesFor(char, helper, achievements, priority)
        return plan(char, priority, candidates,
            inventoryQty = { helper.getItemQuantity(char, it) },
            bankQty = { helper.bankState.getQuantity(it) })
    }
}
