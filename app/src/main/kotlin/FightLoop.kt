package com.nguyen_anthony.app

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.SimpleItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

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
                println("  - ${char.name} (Level ${char.level}) at (${char.x}, ${char.y})")
            }
            println()

            // Get cooked_chicken healing info
            println("=== Getting Cooked Chicken Info ===")
            val cookedChickenItem = client.content.getItem("cooked_chicken")
            val healAmount = cookedChickenItem.effects.find { it.code == "heal" }?.value ?: 0
            println("Cooked chicken heals: ${healAmount} HP")
            println()

            // Find woodcutting station location (used to determine optimal chicken)
            println("=== Locating Cooking Station ===")
            val cookingStations = client.content.getMaps(
                contentType = "workshop",
                contentCode = "cooking",
                hideBlockedMaps = true,
                size = 50
            )
            if (cookingStations.data.isEmpty()) {
                println("No cooking stations found on map!")
                return@use
            }
            val cookingStation = cookingStations.data.first()
            println("Woodcutting station located at (${cookingStation.x}, ${cookingStation.y})")
            println()

            // Gather ash_wood with all characters concurrently
            println("=== Starting Concurrent Fighting ===")
            println("All characters will fight independently from their current positions")
            println("Press Ctrl+C to stop")
            println()

            // Launch a coroutine for each character to gather independently
            coroutineScope {
                for (character in characters) {
                    launch {

                        while (true) {
                            try {
                                // Refresh character data to check inventory
                                val currentChar = client.characters.getCharacter(character.name)

                                // Check HP and heal if needed
                                val hpMissing = currentChar.maxHp - currentChar.hp
                                if (hpMissing >= healAmount) {
                                    val cookedChickenCount = currentChar.inventory.find { it.code == "cooked_chicken" }?.quantity ?: 0
                                    val rawChickenCount = currentChar.inventory.find { it.code == "raw_chicken" }?.quantity ?: 0

                                    if (cookedChickenCount > 0) {
                                        // Use cooked chicken to heal
                                        println("${currentChar.name}: 🍗 HP low (${currentChar.hp}/${currentChar.maxHp}), eating cooked_chicken...")
                                        val useResult = client.actions.use(currentChar.name, "cooked_chicken", 1)
                                        println("  ✓ Healed! HP: ${useResult.character.hp}/${useResult.character.maxHp}")
                                        delay(useResult.cooldown.totalSeconds.seconds)
                                    } else if (rawChickenCount > 0) {
                                        // No cooked chicken but have raw chicken - go cook it!
                                        println("${currentChar.name}: 🍳 HP low (${currentChar.hp}/${currentChar.maxHp}), no cooked chicken but have ${rawChickenCount}x raw_chicken - cooking...")

                                        // Move to cooking station if not already there
                                        if (currentChar.x != cookingStation.x || currentChar.y != cookingStation.y) {
                                            println("  → Moving to cooking station at (${cookingStation.x}, ${cookingStation.y})")
                                            val moveResult = client.actions.move(currentChar.name, cookingStation.x, cookingStation.y)
                                            delay(moveResult.cooldown.totalSeconds.seconds)
                                        }

                                        // Cook as many raw chickens as possible (up to 10 at once to save time)
                                        println("  🍳 Cooking ${rawChickenCount}x raw_chicken into cooked_chicken...")
                                        val craftResult = client.actions.craft(currentChar.name, "cooked_chicken", rawChickenCount)
                                        delay(craftResult.cooldown.totalSeconds.seconds)

                                        // Now use one cooked chicken to heal
                                        println("  🍗 Eating freshly cooked chicken...")
                                        val useResult = client.actions.use(currentChar.name, "cooked_chicken", 1)
                                        println("  ✓ Healed! HP: ${useResult.character.hp}/${useResult.character.maxHp}")
                                        delay(useResult.cooldown.totalSeconds.seconds)
                                    } else {
                                        // No chicken available at all, rest instead
                                        println("${currentChar.name}: 😴 HP low (${currentChar.hp}/${currentChar.maxHp}), no chicken - resting...")
                                        val restResult = client.actions.rest(currentChar.name)
                                        println("  ✓ Rested! HP: ${restResult.character.hp}/${restResult.character.maxHp}")
                                        delay(restResult.cooldown.totalSeconds.seconds)
                                    }
                                }

                                // Check if inventory is getting full (90% or more)
                                val totalItems = currentChar.inventory.sumOf { it.quantity }
                                val inventoryThreshold = (currentChar.inventoryMaxItems * 0.9).toInt()

                                if (totalItems >= inventoryThreshold) {
                                    println("${currentChar.name}: 📦 Inventory is ${totalItems}/${currentChar.inventoryMaxItems}")

                                    println(" Moving to cooking workshop at (${cookingStation.x}, ${cookingStation.y})")
                                    val moveToStationResult = client.actions.move(currentChar.name, cookingStation.x, cookingStation.y)
                                    println(" Arrived at cooking workshop")

                                    // Wait for movement cooldown
                                    if (moveToStationResult.cooldown.totalSeconds > 0) {
                                        delay(moveToStationResult.cooldown.totalSeconds.seconds)
                                    }

                                    val charAtStation = client.characters.getCharacter(currentChar.name)
                                    val rawChickenCount = charAtStation.inventory.find { it.code == "raw_chicken" }?.quantity ?: 0

                                    if (rawChickenCount > 0) {
                                        println(" Cooking $rawChickenCount chicken")
                                        val craftResult = client.actions.craft(
                                            characterName = charAtStation.name,
                                            itemCode = "cooked_chicken",
                                            quantity = rawChickenCount
                                        )
                                        println(" Crafted ${craftResult.details.items.joinToString(", ") { "${it.quantity}x ${it.code}" }}")

                                        // Wait for crafting cooldown
                                        if (craftResult.cooldown.totalSeconds > 0) {
                                            delay(craftResult.cooldown.totalSeconds.seconds)
                                        }
                                    }

                                    // Find nearest bank
                                    val banks = client.content.getMaps(
                                        contentType = "bank",
                                        hideBlockedMaps = true,
                                        size = 50
                                    )

                                    if (banks.data.isEmpty()) {
                                        println("No banks found on map!")
                                        delay(30.seconds)
                                        continue
                                    }

                                    // Find closest bank
                                    val nearestBank = banks.data.minByOrNull { bank ->
                                        abs(bank.x - currentChar.x) + abs(bank.y - currentChar.y)
                                    }!!

                                    println("  📍 Moving to bank at (${nearestBank.x}, ${nearestBank.y})")
                                    val moveResult = client.actions.move(currentChar.name, nearestBank.x, nearestBank.y)
                                    println("  ✓ Arrived at bank")

                                    // Wait for movement cooldown
                                    if (moveResult.cooldown.totalSeconds > 0) {
                                        delay(moveResult.cooldown.totalSeconds.seconds)
                                    }

                                    val updatedChar = client.characters.getCharacter(currentChar.name)

                                    // Calculate how much cooked chicken we have
                                    val cookedChickenCount = updatedChar.inventory.find { it.code == "cooked_chicken" }?.quantity ?: 0
                                    val totalItems = updatedChar.inventory.sumOf { it.quantity }
                                    val cookedChickenPercentage = if (totalItems > 0) (cookedChickenCount.toDouble() / totalItems) else 0.0

                                    // Build list of items to deposit
                                    val itemsToDeposit = mutableListOf<SimpleItem>()

                                    // Always deposit eggs, feathers, and golden_eggs
                                    updatedChar.inventory
                                        .filter { it.code in listOf("egg", "feather", "golden_egg") && it.quantity > 0 }
                                        .forEach { itemsToDeposit.add(SimpleItem(it.code, it.quantity)) }

                                    // Only deposit cooked_chicken if inventory is MOSTLY full of it (>70%)
                                    if (cookedChickenPercentage > 0.7 && cookedChickenCount > 20) {
                                        // Keep 20 cooked chicken, deposit the rest
                                        val amountToDeposit = cookedChickenCount - 20
                                        println("  🍗 Inventory is ${(cookedChickenPercentage * 100).toInt()}% cooked_chicken, depositing ${amountToDeposit} (keeping 20)")
                                        itemsToDeposit.add(SimpleItem("cooked_chicken", amountToDeposit))
                                    }

                                    if (itemsToDeposit.isNotEmpty()) {
                                        println("  💰 Depositing items: ${itemsToDeposit.joinToString(", ") { "${it.quantity}x ${it.code}" }}")
                                        val depositResult = client.bank.depositItems(currentChar.name, itemsToDeposit)

                                        // Wait for bank cooldown
                                        if (depositResult.cooldown.totalSeconds > 0) {
                                            delay(depositResult.cooldown.totalSeconds.seconds)
                                        }
                                    }
                                }

                                // Now find nearest chicken and move there if not already there
                                val refreshedChar = client.characters.getCharacter(currentChar.name)
                                val currentLocation = client.content.getMapByPosition(
                                    layer = refreshedChar.layer,
                                    x = refreshedChar.x,
                                    y = refreshedChar.y
                                )

                                // Check if we're already at chickens
                                val isAtChicken = currentLocation.interactions.content?.let {
                                    it.type == "monster" && it.code == "chicken"
                                } ?: false

                                if (!isAtChicken) {
                                    println("${currentChar.name}: Looking for chickens to fight ...")

                                    // Find chickens closest to cooking station (not character)
                                    val chickenLocations = client.content.getMaps(
                                        contentType = "monster",
                                        contentCode = "chicken",
                                        hideBlockedMaps = true,
                                        size = 50
                                    )

                                    if (chickenLocations.data.isEmpty()) {
                                        println("No chickens locations found!")
                                        delay(30.seconds)
                                        continue
                                    }

                                    // Find chicken closest to cooking station for optimal gathering loop
                                    val optimalChicken = chickenLocations.data.minByOrNull { chicken ->
                                        abs(chicken.x - cookingStation.x) + abs(chicken.y - cookingStation.y)
                                    }!!

                                    println("Moving to optimal chicken at (${optimalChicken.x}, ${optimalChicken.y})")
                                    val moveResult = client.actions.move(currentChar.name, optimalChicken.x, optimalChicken.y)
                                    println("Arrived at chickens")

                                    // Wait for movement cooldown
                                    if (moveResult.cooldown.totalSeconds > 0) {
                                        delay(moveResult.cooldown.totalSeconds.seconds)
                                    }
                                }

                                // Now gather
                                val finalChar = client.characters.getCharacter(currentChar.name)
                                val finalTotalItems = finalChar.inventory.sumOf { it.quantity }

                                println("${currentChar.name}: Fighting chickens... (Inventory: $finalTotalItems/${finalChar.inventoryMaxItems})")
                                val result = client.actions.fight(currentChar.name)

                                println("  ✓ ${currentChar.name} successfully gathered!")
                                println("  XP Gained: ${result.fight.characters.first().xp}")
                                println("  Gold Gained: ${result.fight.characters.first().gold}")
                                println("  Cooldown: ${result.cooldown.totalSeconds}s")
                                println()

                                // Wait for cooldown
                                if (result.cooldown.totalSeconds > 0) {
                                    delay(result.cooldown.totalSeconds.seconds)
                                }

                            } catch (e: ArtifactsApiException) {
                                println("  ❌ ${character.name} Error: ${e.message}")

                                // If character is in cooldown, wait a bit before trying again
                                if (e.errorCode == 486) { // Cooldown error
                                    println("  ${character.name} waiting 5s before continuing...")
                                    delay(5.seconds)
                                } else {
                                    // For other errors, wait a bit longer
                                    println("  ${character.name} waiting 10s before continuing...")
                                    delay(10.seconds)
                                }
                                println()
                            }
                        }
                    }
                }
            }

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

