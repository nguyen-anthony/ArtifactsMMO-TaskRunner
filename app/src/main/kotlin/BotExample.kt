package com.nguyen_anthony.app

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.utils.CharacterUtils
import com.artifactsmmo.client.utils.CooldownUtils
import com.artifactsmmo.client.utils.InventoryUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * Simple bot example using the ArtifactsMMO client
 */
fun main() = runBlocking {
    println("=== ArtifactsMMO Bot ===")
    println()

    // Get API token from environment variable
    val apiToken = System.getenv("ARTIFACTS_API_TOKEN")
    if (apiToken.isNullOrBlank()) {
        println("❌ Error: ARTIFACTS_API_TOKEN environment variable not set")
        println()
        println("To use this bot:")
        println("1. Get your API token from https://artifactsmmo.com")
        println("2. Set the environment variable:")
        println("   Windows: \$env:ARTIFACTS_API_TOKEN=\"your-token-here\"")
        println("   Linux/Mac: export ARTIFACTS_API_TOKEN=\"your-token-here\"")
        println()
        return@runBlocking
    }

    // Create client
    ArtifactsMMOClient.withToken(apiToken, enableLogging = false).use { client ->
        println("Connected to ArtifactsMMO API")
        println()

        try {
            // Get characters
            val characters = client.characters.getMyCharacters()
            if (characters.isEmpty()) {
                println("❌ No characters found. Please create a character first.")
                return@use
            }

            println("Found ${characters.size} character(s):")
            for (char in characters) {
                println("  - ${char.name} (Level ${char.level})")
            }
            println()

            val character = characters.first()
            println("Using character: ${character.name}")
            println()

        // Display character stats
        println("=== Character Stats ===")
        println("Level: ${character.level} (${character.xp}/${character.maxXp} XP)")
        println("HP: ${character.hp}/${character.maxHp} (${(CharacterUtils.getHPPercentage(character) * 100).toInt()}%)")
        println("Gold: ${character.gold}")
        println("Location: (${character.x}, ${character.y}) on ${character.layer}")
        println()

        println("Skills:")
        println("  Mining: ${character.miningLevel}")
        println("  Woodcutting: ${character.woodcuttingLevel}")
        println("  Fishing: ${character.fishingLevel}")
        println("  Weaponcrafting: ${character.weaponcraftingLevel}")
        println("  Gearcrafting: ${character.gearcraftingLevel}")
        println("  Jewelrycrafting: ${character.jewelrycraftingLevel}")
        println("  Cooking: ${character.cookingLevel}")
        println("  Alchemy: ${character.alchemyLevel}")
        println()

        println("Inventory: ${character.inventory.size}/${character.inventoryMaxItems} slots")
        if (character.inventory.isNotEmpty()) {
            for (item in character.inventory.take(5)) {
                println("  - ${item.code} x${item.quantity}")
            }
            if (character.inventory.size > 5) {
                println("  ... and ${character.inventory.size - 5} more items")
            }
        }
        println()

        // Check cooldown status
        if (CooldownUtils.isInCooldown(character)) {
            val remaining = CooldownUtils.getRemainingCooldown(character)
            println("⏳ Character is in cooldown for $remaining seconds")
            println()
        } else {
            println("✓ Character is ready for action!")
            println()
        }

        // Example: Get nearby content
        println("=== Exploring Current Location ===")
        try {
            val currentMap = client.content.getMapByPosition(
                layer = character.layer,
                x = character.x,
                y = character.y
            )

            println("Current map: ${currentMap.name}")

            currentMap.interactions.content?.let { content ->
                println("  Content: ${content.type} - ${content.code}")

                when (content.type) {
                    "monster" -> {
                        val monster = client.content.getMonster(content.code)
                        println("  Monster: ${monster.name} (Level ${monster.level})")
                        println("    HP: ${monster.hp}")
                        println("    Gold: ${monster.minGold}-${monster.maxGold}")
                    }
                    "resource" -> {
                        val resource = client.content.getResource(content.code)
                        println("  Resource: ${resource.name}")
                        println("    Skill: ${resource.skill} (Level ${resource.level})")
                    }
                }
            } ?: println("  No content at this location")

        } catch (e: Exception) {
            println("Could not fetch map info: ${e.message}")
        }
        println()

        println("=== Bot Ready ===")
        println("You can now write your bot logic using the client!")
        println()
        println("Example actions:")
        println("  client.actions.move(\"${character.name}\", x = 5, y = 10)")
        println("  client.actions.fight(\"${character.name}\")")
        println("  client.actions.gather(\"${character.name}\")")
        println("  client.actions.rest(\"${character.name}\")")
        println()

        } catch (e: ArtifactsApiException) {
            println("❌ API Error (Code ${e.errorCode}): ${e.message}")
            println()

            when (e.errorCode) {
                452 -> {
                    println("This error indicates an invalid or expired API token.")
                    println()
                    println("Please:")
                    println("  1. Visit https://artifactsmmo.com")
                    println("  2. Generate a new API token")
                    println("  3. Update your ARTIFACTS_API_TOKEN environment variable")
                    println()
                    println("Windows (PowerShell):")
                    println("  \$env:ARTIFACTS_API_TOKEN=\"your-new-token-here\"")
                    println()
                    println("Linux/Mac:")
                    println("  export ARTIFACTS_API_TOKEN=\"your-new-token-here\"")
                }
                401 -> {
                    println("Authentication failed. Please check your API token.")
                    println("Get your token from: https://artifactsmmo.com")
                }
                429 -> {
                    println("Rate limit exceeded. Please wait before retrying.")
                }
                else -> {
                    println("Please check the API documentation or your account status.")
                }
            }
            println()
        } catch (e: Exception) {
            println("❌ Unexpected error: ${e.message}")
            e.printStackTrace()
        }
    }
}

