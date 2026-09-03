package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.MapInfo
import kotlin.math.abs

/**
 * Evaluates whether teleport potions should be used for a given trip.
 *
 * A potion is worth using when it saves at least [TILE_SAVINGS_THRESHOLD] tiles of walking.
 * Time model: [SECONDS_PER_TILE] seconds/tile for walking, [TELEPORT_COOLDOWN_SECONDS] flat
 * for potion use (confirmed fixed regardless of quantity per the game's /action/use docs).
 *
 * Outbound and return legs are evaluated completely independently — either, both, or
 * neither may result in a potion being used/pre-loaded.
 *
 * Return potions are only ever pre-loaded during the outbound trip (there is no logical
 * "withdraw a potion from the bank in order to reach the bank" shortcut) — see
 * [ActionHelper.navigateToBank] which only ever consumes an already-held return potion.
 */
class TeleportAdvisor(
    private val contentCache: ContentCache,
    private val bankState: BankState
) {
    companion object {
        const val TILE_SAVINGS_THRESHOLD = 4          // minimum tiles saved to justify using a potion
        const val SECONDS_PER_TILE = 5
        const val TELEPORT_COOLDOWN_SECONDS = 3

        /** Preference order for return-trip potions (bank-delivering first). */
        val RETURN_POTION_PREFERENCE = listOf("forest_bank_potion", "recall_potion")
    }

    data class TeleportPlan(
        val outboundPotion: TeleportPotionStore.TeleportPotion?,  // null = walk outbound
        val returnPotion: TeleportPotionStore.TeleportPotion?,    // null = don't pre-load a return potion
        val potionsToWithdraw: List<String>                       // codes to fetch from bank before departing
    )

    /**
     * Evaluate both legs of a round trip: character going to [destination] and eventually
     * returning to a bank. Returns a [TeleportPlan] describing which potions (if any) help,
     * and which of those need to be withdrawn from the bank before departure (i.e. aren't
     * already sitting in inventory).
     */
    fun planTrip(
        char: Character,
        destination: MapInfo,
        completedAchievements: Set<String>
    ): TeleportPlan {
        val outbound = bestOutboundPotion(char, destination, completedAchievements)
        val returnPotion = bestReturnPotion(char, destination, completedAchievements)

        val potionsToWithdraw = mutableListOf<String>()
        if (outbound != null && !inInventory(char, outbound.code)) {
            potionsToWithdraw.add(outbound.code)
        }
        if (returnPotion != null && !inInventory(char, returnPotion.code)) {
            potionsToWithdraw.add(returnPotion.code)
        }

        return TeleportPlan(outbound, returnPotion, potionsToWithdraw)
    }

    /**
     * Find the best outbound potion for going from [char]'s current position to
     * [destination], or null if none saves enough time. Considers all potions owned
     * (inventory + bank), usable (level + achievement conditions met), and whose
     * destination tile is accessible (present in the pre-warmed map cache).
     */
    private fun bestOutboundPotion(
        char: Character,
        destination: MapInfo,
        completedAchievements: Set<String>
    ): TeleportPotionStore.TeleportPotion? {
        val baseDistance = distance(char.x, char.y, destination.x, destination.y)

        var best: TeleportPotionStore.TeleportPotion? = null
        var bestSavings = TILE_SAVINGS_THRESHOLD - 1  // must strictly meet/exceed threshold

        for (potion in TeleportPotionStore.getPotions()) {
            if (!ownsPotion(char, potion.code)) continue
            if (!canUsePotion(char, potion, completedAchievements)) continue

            val destTile = contentCache.getTileById(potion.destinationMapId) ?: continue
            if (!isTileAccessible(potion.destinationMapId)) continue
            // Cross-layer destinations aren't directly comparable via Manhattan distance to
            // an overworld target — only consider same-layer teleport destinations here.
            if (destTile.layer != destination.layer) continue

            val postTeleportDistance = distance(destTile.x, destTile.y, destination.x, destination.y)
            val tilesSaved = baseDistance - postTeleportDistance

            if (tilesSaved > bestSavings) {
                bestSavings = tilesSaved
                best = potion
            }
        }

        return best
    }

    /**
     * Find the best return potion to pre-load for the trip from [char]'s current position
     * via [destination] back to the nearest accessible bank, or null if none saves enough time.
     *
     * Checks [RETURN_POTION_PREFERENCE] in order (forest_bank_potion first — lands directly
     * at a bank; recall_potion second — lands at spawn, still needs a short walk to a bank).
     * The comparison baseline is the walk from [destination] (where the character will BE
     * when they're ready to return) to the nearest bank from there.
     */
    private fun bestReturnPotion(
        char: Character,
        destination: MapInfo,
        completedAchievements: Set<String>
    ): TeleportPotionStore.TeleportPotion? {
        val nearestBankFromDestination = contentCache.findNearestBankFrom(destination.x, destination.y, destination.layer)
            ?: contentCache.findNearestBankFrom(destination.x, destination.y, "overworld")
            ?: return null
        val baseReturnDistance = distance(destination.x, destination.y, nearestBankFromDestination.x, nearestBankFromDestination.y)

        for (potionCode in RETURN_POTION_PREFERENCE) {
            val potion = TeleportPotionStore.getPotions().find { it.code == potionCode } ?: continue
            if (!ownsPotion(char, potion.code)) continue
            if (!canUsePotion(char, potion, completedAchievements)) continue

            val destTile = contentCache.getTileById(potion.destinationMapId) ?: continue
            if (!isTileAccessible(potion.destinationMapId)) continue

            val bankFromPotionLanding = contentCache.findNearestBankFrom(destTile.x, destTile.y, destTile.layer)
                ?: continue
            val postTeleportDistance = distance(destTile.x, destTile.y, bankFromPotionLanding.x, bankFromPotionLanding.y)
            val tilesSaved = baseReturnDistance - postTeleportDistance

            if (tilesSaved >= TILE_SAVINGS_THRESHOLD) {
                return potion  // first match in preference order wins
            }
        }

        return null
    }

    /**
     * Re-evaluate whether an already-held [potionCode] still saves enough time returning
     * to a bank from the character's CURRENT position (not wherever it was originally
     * planned for — the character may have moved since [planTrip] was called).
     *
     * Used by [ActionHelper.navigateToBank] which only ever consumes a pre-loaded potion.
     */
    fun evaluateHeldReturnPotion(
        char: Character,
        potionCode: String,
        completedAchievements: Set<String>
    ): TeleportPotionStore.TeleportPotion? {
        val potion = TeleportPotionStore.getPotions().find { it.code == potionCode } ?: return null
        if (!canUsePotion(char, potion, completedAchievements)) return null

        val destTile = contentCache.getTileById(potion.destinationMapId) ?: return null
        if (!isTileAccessible(potion.destinationMapId)) return null

        val directBank = contentCache.findNearestBank(char) ?: contentCache.findNearestBankFrom(char.x, char.y, char.layer)
        val directDistance = if (directBank != null) distance(char.x, char.y, directBank.x, directBank.y) else Int.MAX_VALUE

        val bankFromLanding = contentCache.findNearestBankFrom(destTile.x, destTile.y, destTile.layer) ?: return null
        val teleportDistance = distance(destTile.x, destTile.y, bankFromLanding.x, bankFromLanding.y)

        val tilesSaved = directDistance - teleportDistance
        return if (tilesSaved >= TILE_SAVINGS_THRESHOLD) potion else null
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Int = abs(x1 - x2) + abs(y1 - y2)

    private fun inInventory(char: Character, code: String): Boolean =
        char.inventory.any { it.code == code && it.quantity > 0 }

    /** True if the character owns this potion — either in inventory or the bank. */
    private fun ownsPotion(char: Character, code: String): Boolean {
        if (inInventory(char, code)) return true
        return bankState.getQuantity(code) > 0
    }

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

    /** True if the destination tile passed the accessibility filter in [ContentCache.preWarmMaps]. */
    private fun isTileAccessible(mapId: Int): Boolean = contentCache.getTileById(mapId) != null
}
