package com.artifactsmmo.examples

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.utils.CharacterUtils
import com.artifactsmmo.client.utils.CooldownUtils
import com.artifactsmmo.client.utils.InventoryUtils
import kotlinx.coroutines.runBlocking

/**
 * Example usage of the ArtifactsMMO client library
 */
fun main() = runBlocking {
    // Replace with your actual API token
    val apiToken = System.getenv("ARTIFACTS_API_TOKEN") ?: "your-token-here"

    ArtifactsMMOClient.withToken(apiToken, enableLogging = true).use { client ->

        // Example 1: Get all your characters
        println("=== Your Characters ===")
        val characters = client.characters.getMyCharacters()
        for (character in characters) {
            println("${character.name} - Level ${character.level} (${character.skin})")
            println("  Location: (${character.x}, ${character.y}) on ${character.layer}")
            println("  HP: ${character.hp}/${character.maxHp}")
            println("  Gold: ${character.gold}")
            println()
        }

        if (characters.isEmpty()) {
            println("No characters found. Create one first!")
            return@use
        }

        val myCharacter = characters.first()
        println("Using character: ${myCharacter.name}")
        println()

        // Example 2: Check inventory
        println("=== Inventory ===")
        println("Inventory space: ${myCharacter.inventory.size}/${myCharacter.inventoryMaxItems}")
        println("Available space: ${InventoryUtils.getAvailableSpace(myCharacter)} slots")

        if (myCharacter.inventory.isNotEmpty()) {
            println("Items:")
            for (item in myCharacter.inventory) {
                println("  - ${item.code} x${item.quantity}")
            }
        }
        println()

        // Example 3: Check skills
        println("=== Skills ===")
        println("Mining: ${myCharacter.miningLevel} (${myCharacter.miningXp}/${myCharacter.miningMaxXp} XP)")
        println("Woodcutting: ${myCharacter.woodcuttingLevel} (${myCharacter.woodcuttingXp}/${myCharacter.woodcuttingMaxXp} XP)")
        println("Fishing: ${myCharacter.fishingLevel} (${myCharacter.fishingXp}/${myCharacter.fishingMaxXp} XP)")
        println()

        // Example 4: Get nearby maps
        println("=== Nearby Maps ===")
        val nearbyMaps = client.content.getMaps(
            layer = myCharacter.layer,
            page = 1,
            size = 5
        )
        for (map in nearbyMaps.data) {
            val distance = kotlin.math.abs(map.x - myCharacter.x) + kotlin.math.abs(map.y - myCharacter.y)
            println("${map.name} at (${map.x}, ${map.y}) - Distance: $distance")
            map.interactions.content?.let { content ->
                println("  Content: ${content.type} - ${content.code}")
            }
        }
        println()

        // Example 5: Get bank info
        println("=== Bank ===")
        try {
            val bank = client.bank.getBankDetails()
            println("Bank slots: ${bank.slots}")
            println("Bank gold: ${bank.gold}")
            println("Next expansion cost: ${bank.nextExpansionCost}")
        } catch (e: Exception) {
            println("Could not fetch bank details: ${e.message}")
        }
        println()

        // Example 6: Search for items
        println("=== Searching for Weapons ===")
        val weapons = client.content.getItems(type = "weapon", page = 1, size = 5)
        for (item in weapons.data) {
            println("${item.name} (Level ${item.level})")
            println("  Code: ${item.code}")
            println("  Type: ${item.type}/${item.subtype}")
            if (item.effects.isNotEmpty()) {
                println("  Effects: ${item.effects.joinToString { "${it.code}: ${it.value}" }}")
            }
            println()
        }

        // Example 7: Search for monsters
        println("=== Low-Level Monsters ===")
        val monsters = client.content.getMonsters(minLevel = 1, maxLevel = 5, page = 1, size = 3)
        for (monster in monsters.data) {
            println("${monster.name} (Level ${monster.level})")
            println("  HP: ${monster.hp}")
            println("  Gold drop: ${monster.minGold}-${monster.maxGold}")
            if (monster.drops.isNotEmpty()) {
                println("  Drops:")
                for (drop in monster.drops) {
                    println("    - ${drop.code}: ${drop.minQuantity}-${drop.maxQuantity} (1/${drop.rate})")
                }
            }
            println()
        }

        // Example 8: Check character cooldown
        println("=== Cooldown Status ===")
        if (CooldownUtils.isInCooldown(myCharacter)) {
            val remaining = CooldownUtils.getRemainingCooldown(myCharacter)
            println("Character is in cooldown for $remaining seconds")
        } else {
            println("Character is ready for action!")
        }

        // Example 9: Check HP status
        val hpPercent = CharacterUtils.getHPPercentage(myCharacter)
        println("HP: ${(hpPercent * 100).toInt()}%")
        if (!CharacterUtils.hasEnoughHP(myCharacter, 0.5)) {
            println("⚠️ Character has low HP - consider resting")
        }
        println()

        println("=== Done! ===")
    }
}

