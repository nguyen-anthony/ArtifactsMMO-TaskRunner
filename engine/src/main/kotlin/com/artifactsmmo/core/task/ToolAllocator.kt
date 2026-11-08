package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Item
import kotlinx.serialization.Serializable

/** One character's tool-relevant state: skill levels and the tools it holds (inventory + weapon slot). */
data class ToolHolder(
    val name: String,
    val skillLevels: Map<String, Int>,
    /** Tool code -> quantity held, counting the equipped weapon. */
    val tools: Map<String, Int>,
    /** Currently equipped weapon (may be a tool). */
    val weapon: String = "",
)

/** What one character has to do to match its assignment. */
@Serializable
data class ToolKitActions(
    /** Tool code -> quantity to bank (tools assigned to others, or spare copies). */
    val deposit: Map<String, Int> = emptyMap(),
    /** Assigned tools the character doesn't hold yet. */
    val withdraw: List<String> = emptyList(),
) {
    fun isEmpty() = deposit.isEmpty() && withdraw.isEmpty()

    /** True if a bank trip would change something right now. */
    fun actionable(bank: Map<String, Int>) = deposit.isNotEmpty() || withdraw.any { (bank[it] ?: 0) > 0 }
}

/** Result of [ToolAllocator.allocate]; also the enforcement policy read by [ActionHelper]. */
@Serializable
data class ToolKitPlan(
    val skills: Set<String>,
    /** Character -> skill -> assigned tool code (absent = no usable tool available). */
    val assignments: Map<String, Map<String, String>>,
    val actions: Map<String, ToolKitActions>,
    /** Tool code -> copies nobody needs (every character's assigned tool is at least as good). */
    val surplus: Map<String, Int>,
    val computedAtMillis: Long = 0,
) {
    /** True if the plan decides which [skill] tool [character] may take from the bank. */
    fun manages(character: String, skill: String) = skill in skills && character in assignments

    fun assignedTool(character: String, skill: String): String? = assignments[character]?.get(skill)
}

/**
 * Pure tool distribution. For each managed skill, the pool is every copy in the bank plus every
 * copy any character holds. Characters, in the skill's claim order, each take the best remaining
 * tool they can use (tool level <= skill level). Everything else a character holds for that
 * skill goes back to the bank.
 */
object ToolAllocator {
    fun allocate(
        tools: List<Item>,
        config: ToolKitConfig,
        holders: List<ToolHolder>,
        bank: Map<String, Int>,
        recyclable: (Item) -> Boolean = { it.craft?.skill in BankExecutor.RECYCLABLE_SKILLS },
        nowMillis: Long = 0,
    ): ToolKitPlan {
        val roster = holders.map { it.name }
        val byName = holders.associateBy { it.name }
        val skills = config.skills.filter { it in ToolEffects.TOOL_SKILLS }.toSet()
        val assignments = roster.associateWith { mutableMapOf<String, String>() }
        val surplus = mutableMapOf<String, Int>()
        val toolSkill = mutableMapOf<String, String>()

        for (skill in skills) {
            val cmp = ToolEffects.comparator(skill)
            val candidates = tools.filter { ToolEffects.skillOf(it) == skill }
                .sortedWith(cmp.reversed().thenBy { it.code })
            if (candidates.isEmpty()) continue
            candidates.forEach { toolSkill[it.code] = skill }

            val pool = candidates.associate { t ->
                t.code to (bank[t.code] ?: 0) + holders.sumOf { it.tools[t.code] ?: 0 }
            }.toMutableMap()
            val assignedItem = mutableMapOf<String, Item>()
            for (name in config.orderFor(skill, roster)) {
                val level = byName.getValue(name).skillLevels[skill] ?: 0
                val pick = candidates.firstOrNull { it.level <= level && pool.getValue(it.code) > 0 } ?: continue
                pool[pick.code] = pool.getValue(pick.code) - 1
                assignedItem[name] = pick
                assignments.getValue(name)[skill] = pick.code
            }

            for (t in candidates) {
                val left = pool.getValue(t.code)
                if (left <= 0 || !recyclable(t)) continue
                // Nobody would ever fall back to it: everyone already has something at least as good.
                val redundant = roster.all { n -> assignedItem[n]?.let { cmp.compare(it, t) >= 0 } == true }
                if (redundant && left > config.spares) surplus[t.code] = left - config.spares
            }
        }

        val actions = holders.associate { h ->
            h.name to actionsFor(h, assignments.getValue(h.name), skills) { toolSkill[it] }
        }
        return ToolKitPlan(skills, assignments, actions, surplus, nowMillis)
    }

    /**
     * Bank trip for [holder] given its [assigned] skill -> tool: keep one copy of each assigned
     * tool, bank every other tool of a managed skill, withdraw assigned tools it lacks.
     * [skillOf] maps a tool code to its skill (null = not a tool).
     */
    fun actionsFor(
        holder: ToolHolder,
        assigned: Map<String, String>,
        skills: Set<String>,
        skillOf: (String) -> String?,
    ): ToolKitActions {
        val deposit = mutableMapOf<String, Int>()
        for ((code, qty) in holder.tools) {
            val skill = skillOf(code) ?: continue
            if (skill !in skills) continue
            val extra = qty - if (assigned[skill] == code) 1 else 0
            if (extra > 0) deposit[code] = extra
        }
        val withdraw = assigned.filter { (skill, code) -> skill in skills && (holder.tools[code] ?: 0) == 0 }.values.sorted()
        return ToolKitActions(deposit, withdraw)
    }
}
