package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Item

/**
 * Which weapon-slot tools speed up which skill, and by how much.
 *
 * Season 9 (v9.0.0) renamed the cooldown effects: gathering tools carry `<skill>_gathering`
 * (e.g. `mining_gathering`) and cooking tools carry `cooking_crafting`. The pre-season bare
 * codes (`mining`, `woodcutting`, `fishing`, `alchemy`) are still accepted so the same build
 * works on both the old and new servers.
 */
object ToolEffects {
    /** Skills that have a dedicated weapon-slot tool. */
    val TOOL_SKILLS = listOf("mining", "woodcutting", "fishing", "alchemy", "cooking")

    /** Crafting skills whose recipes are sped up by a weapon-slot tool (Season 9 cooking tools). */
    val CRAFTING_TOOL_SKILLS = setOf("cooking")

    private val GATHERING_SKILLS = setOf("mining", "woodcutting", "fishing", "alchemy")

    /** Effect codes that mark a tool for [skill] (empty if the skill has no tool). */
    fun effectCodes(skill: String): Set<String> = when (skill) {
        in GATHERING_SKILLS -> setOf("${skill}_gathering", skill)
        "cooking" -> setOf("cooking_crafting")
        else -> emptySet()
    }

    /** Cooldown reduction in percent (e.g. 70 for `-70`) that [item] gives [skill]; 0 if none. */
    fun reduction(item: Item, skill: String): Int {
        val codes = effectCodes(skill)
        if (codes.isEmpty()) return 0
        return item.effects.filter { it.code in codes }.maxOfOrNull { -it.value } ?: 0
    }

    /** True if [item] is a weapon-slot tool that reduces [skill]'s cooldown. */
    fun isToolFor(item: Item, skill: String): Boolean = item.type == "weapon" && reduction(item, skill) > 0

    /** The skill [item] is a tool for, or null if it isn't one. */
    fun skillOf(item: Item): String? = TOOL_SKILLS.firstOrNull { isToolFor(item, it) }

    /**
     * Orders tools for one skill: bigger cooldown reduction first, level as the tie-break
     * (two level-50 cooking tools give -60% and -70%, so level alone is not enough).
     */
    fun comparator(skill: String): Comparator<Item> =
        compareBy<Item>({ reduction(it, skill) }, { it.level })
}
