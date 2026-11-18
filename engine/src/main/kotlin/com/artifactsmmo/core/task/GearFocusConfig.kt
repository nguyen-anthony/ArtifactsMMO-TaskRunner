package com.artifactsmmo.core.task

import kotlinx.serialization.Serializable

/**
 * Account-wide gathering gear focus settings (config kind `gear_focus`, key [KEY]). Gathering a
 * resource that can drop one of [prospectFor] favours prospecting gear; anything else favours
 * wisdom (see [StatGearOptimizer.resolveFocus]).
 */
@Serializable
data class GearFocusConfig(
    val prospectFor: List<String> = DEFAULT_PROSPECT_FOR,
) {
    companion object {
        const val KEY = "default"

        val DEFAULT_PROSPECT_FOR = listOf(
            "topaz_stone", "emerald_stone", "ruby_stone", "sapphire_stone", "diamond_stone",
            "alexandrite_stone", "small_pearls", "golden_shrimp",
        )
    }
}
