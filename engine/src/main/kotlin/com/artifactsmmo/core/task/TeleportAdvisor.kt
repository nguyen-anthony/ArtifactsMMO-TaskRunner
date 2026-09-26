package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.MapInfo

/**
 * Turns owned teleport potions into [PotionOption]s for the [RoutePlanner], so a single
 * route search decides between walking, crossing gates and teleporting.
 *
 * Time model: [SECONDS_PER_TILE] seconds/tile walking, [TELEPORT_COOLDOWN_SECONDS] flat for
 * a potion. A potion is only worth consuming if it saves ≥ [TILE_SAVINGS_THRESHOLD] tiles —
 * the planner charges exactly that for a potion edge ([RoutePlanner.POTION_COST]).
 *
 * Return potions are only ever pre-loaded during an outbound trip (never "withdraw a
 * potion from the bank in order to reach the bank") — [ActionHelper.navigateToBank] only
 * considers potions already in inventory.
 */
class TeleportAdvisor(
    private val contentCache: ContentCache,
    private val bankState: BankState
) {
    companion object {
        const val TILE_SAVINGS_THRESHOLD = 6          // minimum tiles saved to justify using a potion
        const val SECONDS_PER_TILE = 5
        const val TELEPORT_COOLDOWN_SECONDS = 3

        /** Preference order for return-trip potions (bank-delivering first). */
        val RETURN_POTION_PREFERENCE = listOf("forest_bank_potion", "recall_potion")
    }

    /**
     * Potions usable as the first route step. Inventory potions are free to use; bank
     * potions are included only when [bankDetourCost] is given (the cost of first walking
     * to the bank to withdraw one).
     */
    fun potionOptions(
        char: Character,
        completedAchievements: Set<String>,
        bankDetourCost: Int? = null,
        onlyCodes: Collection<String>? = null,
    ): List<PotionOption> = TeleportPotionStore.getPotions().mapNotNull { potion ->
        if (onlyCodes != null && potion.code !in onlyCodes) return@mapNotNull null
        if (!canUsePotion(char, potion, completedAchievements)) return@mapNotNull null
        val landing = contentCache.getTileById(potion.destinationMapId)?.pos ?: return@mapNotNull null
        when {
            inInventory(char, potion.code) -> PotionOption(potion.code, landing, fromBank = false)
            bankDetourCost != null && bankState.getQuantity(potion.code) > 0 ->
                PotionOption(potion.code, landing, fromBank = true, extraCost = bankDetourCost)
            else -> null
        }
    }

    /**
     * The return potion worth pre-loading for a trip to [destination]: the first potion in
     * [RETURN_POTION_PREFERENCE] (owned in inventory or bank) that the planner would actually
     * use to get from [destination] back to a bank. Null if walking back is as good.
     */
    fun bestReturnPotion(
        char: Character,
        destination: MapInfo,
        completedAchievements: Set<String>,
    ): TeleportPotionStore.TeleportPotion? {
        for (code in RETURN_POTION_PREFERENCE) {
            val potion = TeleportPotionStore.getPotions().find { it.code == code } ?: continue
            if (!inInventory(char, code) && bankState.getQuantity(code) <= 0) continue
            if (!canUsePotion(char, potion, completedAchievements)) continue
            val landing = contentCache.getTileById(potion.destinationMapId)?.pos ?: continue
            // Pretend it's already held (it will be, once pre-loaded) and see if the planner uses it.
            val route = contentCache.planRouteToBank(
                destination.pos,
                potions = listOf(PotionOption(code, landing, fromBank = false)),
            ) ?: continue
            if (route.steps.firstOrNull() is RouteStep.Potion) return potion
        }
        return null
    }

    private fun inInventory(char: Character, code: String): Boolean =
        char.inventory.any { it.code == code && it.quantity > 0 }

    /** True if the character can use this potion (level requirement + achievement conditions). */
    private fun canUsePotion(
        char: Character,
        potion: TeleportPotionStore.TeleportPotion,
        completedAchievements: Set<String>
    ): Boolean {
        if (char.level < potion.level) return false
        return potion.conditions.all { condition ->
            when (condition.operator) {
                "achievement_unlocked" -> condition.code in completedAchievements
                else -> true  // unknown condition type — don't block on it defensively
            }
        }
    }
}
