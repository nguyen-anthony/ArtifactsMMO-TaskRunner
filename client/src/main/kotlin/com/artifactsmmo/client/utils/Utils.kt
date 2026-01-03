package com.artifactsmmo.client.utils

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Cooldown
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Utility functions for working with cooldowns
 */
@OptIn(kotlin.time.ExperimentalTime::class)
object CooldownUtils {

    /**
     * Check if a character is currently in cooldown
     */
    fun isInCooldown(character: Character): Boolean {
        return character.cooldown > 0 && character.cooldownExpiration > kotlin.time.Clock.System.now()

    }

    /**
     * Get the remaining cooldown time in seconds
     */
    fun getRemainingCooldown(character: Character): Int {
        if (!isInCooldown(character)) return 0
        val now = kotlin.time.Clock.System.now()
        val remaining = character.cooldownExpiration - now
        return remaining.inWholeSeconds.toInt().coerceAtLeast(0)
    }

    /**
     * Suspend until the cooldown expires
     */
    suspend fun waitForCooldown(cooldown: Cooldown) {
        if (cooldown.remainingSeconds > 0) {
            delay(cooldown.remainingSeconds.seconds)
        }
    }

    /**
     * Suspend until the character's cooldown expires
     */
    suspend fun waitForCharacterCooldown(character: Character) {
        val remaining = getRemainingCooldown(character)
        if (remaining > 0) {
            delay(remaining.seconds)
        }
    }
}

/**
 * Utility functions for inventory management
 */
object InventoryUtils {

    /**
     * Check if inventory has space
     */
    fun hasSpace(character: Character, requiredSlots: Int = 1): Boolean {
        val usedSlots = character.inventory.size
        return (usedSlots + requiredSlots) <= character.inventoryMaxItems
    }

    /**
     * Get the quantity of a specific item in inventory
     */
    fun getItemQuantity(character: Character, itemCode: String): Int {
        return character.inventory
            .filter { it.code == itemCode }
            .sumOf { it.quantity }
    }

    /**
     * Check if inventory contains a specific item with minimum quantity
     */
    fun hasItem(character: Character, itemCode: String, minQuantity: Int = 1): Boolean {
        return getItemQuantity(character, itemCode) >= minQuantity
    }

    /**
     * Get available inventory space
     */
    fun getAvailableSpace(character: Character): Int {
        return character.inventoryMaxItems - character.inventory.size
    }
}

/**
 * Utility functions for character stats and skills
 */
object CharacterUtils {

    /**
     * Calculate total combat level
     */
    fun getCombatLevel(character: Character): Int {
        return character.level
    }

    /**
     * Get skill level by name
     */
    fun getSkillLevel(character: Character, skill: String): Int? {
        return when (skill.lowercase()) {
            "mining" -> character.miningLevel
            "woodcutting" -> character.woodcuttingLevel
            "fishing" -> character.fishingLevel
            "weaponcrafting" -> character.weaponcraftingLevel
            "gearcrafting" -> character.gearcraftingLevel
            "jewelrycrafting" -> character.jewelrycraftingLevel
            "cooking" -> character.cookingLevel
            "alchemy" -> character.alchemyLevel
            else -> null
        }
    }

    /**
     * Check if character has enough HP
     */
    fun hasEnoughHP(character: Character, minPercentage: Double = 0.5): Boolean {
        val percentage = character.hp.toDouble() / character.maxHp
        return percentage >= minPercentage
    }

    /**
     * Calculate HP percentage
     */
    fun getHPPercentage(character: Character): Double {
        return character.hp.toDouble() / character.maxHp
    }
}

/**
 * Helper for calculating distances on the map
 */
object MapUtils {

    /**
     * Calculate Manhattan distance between two points
     */
    fun manhattanDistance(x1: Int, y1: Int, x2: Int, y2: Int): Int {
        return kotlin.math.abs(x1 - x2) + kotlin.math.abs(y1 - y2)
    }

    /**
     * Calculate Euclidean distance between two points
     */
    fun euclideanDistance(x1: Int, y1: Int, x2: Int, y2: Int): Double {
        val dx = (x1 - x2).toDouble()
        val dy = (y1 - y2).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}

