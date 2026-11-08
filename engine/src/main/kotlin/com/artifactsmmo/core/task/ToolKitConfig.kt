package com.artifactsmmo.core.task

import kotlinx.serialization.Serializable

/**
 * Account-wide tool kit settings (config kind `toolkit`, key [KEY]). Every character carries
 * one tool per managed skill; when there aren't enough good tools to go around, characters
 * earlier in the priority order get the better ones. See [ToolAllocator].
 */
@Serializable
data class ToolKitConfig(
    val enabled: Boolean = true,
    /** Tool skills to manage (subset of [ToolEffects.TOOL_SKILLS]). */
    val skills: List<String> = ToolEffects.TOOL_SKILLS,
    /** Characters in claim order; unlisted characters follow in roster order. */
    val priority: List<String> = emptyList(),
    /** Per-skill claim order overriding [priority] (e.g. "mining" -> ["Ront", "Reila"]). */
    val perSkillPriority: Map<String, List<String>> = emptyMap(),
    /** Recycle tools nobody needs anymore (every character already has one at least as good). */
    val recycleSurplus: Boolean = true,
    /** Copies of each surplus tool to keep in the bank instead of recycling. */
    val spares: Int = 0,
    /** Above filler (10) and schedules (30), below manual work (50). */
    val syncPriority: Int = 45,
    val recyclePriority: Int = 35,
) {
    /** Claim order for [skill] among [roster] (roster order for anyone not listed). */
    fun orderFor(skill: String, roster: List<String>): List<String> {
        val listed = (perSkillPriority[skill]?.takeIf { it.isNotEmpty() } ?: priority).filter { it in roster }.distinct()
        return listed + roster.filter { it !in listed }
    }

    companion object {
        const val KEY = "default"
    }
}
