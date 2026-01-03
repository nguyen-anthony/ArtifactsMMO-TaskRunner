package com.nguyen_anthony.app

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.ArtifactsApiException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
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

            // Gather ash_wood with all characters concurrently
            println("=== Starting Concurrent Gathering ===")
            println("All characters will gather independently from their current positions")
            println("Press Ctrl+C to stop")
            println()

            // Launch a coroutine for each character to gather independently
            coroutineScope {
                for (character in characters) {
                    launch {
                        var gatherCount = 0
                        var depositCount = 0

                        while (true) {
                            try {
                                // Refresh character data to check inventory
                                val currentChar = client.characters.getCharacter(character.name)

                                // Check if inventory is getting full (80% or more)
                                val totalItems = currentChar.inventory.sumOf { it.quantity }
                                val inventoryThreshold = (currentChar.inventoryMaxItems * 0.8).toInt()

                                if (totalItems >= inventoryThreshold) {
                                    println("${currentChar.name}: 📦 Inventory is ${totalItems}/${currentChar.inventoryMaxItems} - going to bank")

                                    // Find nearest bank
                                    val banks = client.content.getMaps(
                                        contentType = "bank",
                                        hideBlockedMaps = true,
                                        size = 50
                                    )

                                    if (banks.data.isEmpty()) {
                                        println("  ❌ No banks found on map!")
                                        delay(30.seconds)
                                        continue
                                    }

                                    // Find closest bank
                                    val nearestBank = banks.data.minByOrNull { bank ->
                                        kotlin.math.abs(bank.x - currentChar.x) + kotlin.math.abs(bank.y - currentChar.y)
                                    }!!

                                    println("  📍 Moving to bank at (${nearestBank.x}, ${nearestBank.y})")
                                    val moveResult = client.actions.move(currentChar.name, nearestBank.x, nearestBank.y)
                                    println("  ✓ Arrived at bank")

                                    // Wait for movement cooldown
                                    if (moveResult.cooldown.totalSeconds > 0) {
                                        delay(moveResult.cooldown.totalSeconds.seconds)
                                    }

                                    // Deposit ash_wood, sap, and apple items
                                    val updatedChar = client.characters.getCharacter(currentChar.name)
                                    val itemsToDeposit = updatedChar.inventory
                                        .filter { it.code in listOf("ash_wood", "sap", "apple") && it.quantity > 0 }
                                        .map { com.artifactsmmo.client.models.SimpleItem(it.code, it.quantity) }

                                    if (itemsToDeposit.isNotEmpty()) {
                                        println("  💰 Depositing items: ${itemsToDeposit.joinToString(", ") { "${it.quantity}x ${it.code}" }}")
                                        val depositResult = client.bank.depositItems(currentChar.name, itemsToDeposit)
                                        depositCount++
                                        println("  ✓ Deposited successfully (Deposit #$depositCount)")

                                        // Wait for bank cooldown
                                        if (depositResult.cooldown.totalSeconds > 0) {
                                            delay(depositResult.cooldown.totalSeconds.seconds)
                                        }
                                    }
                                }

                                // Now find nearest ash_tree and move there if not already there
                                val refreshedChar = client.characters.getCharacter(currentChar.name)
                                val currentLocation = client.content.getMapByPosition(
                                    layer = refreshedChar.layer,
                                    x = refreshedChar.x,
                                    y = refreshedChar.y
                                )

                                // Check if we're already at an ash_tree
                                val isAtAshTree = currentLocation.interactions.content?.let {
                                    it.type == "resource" && it.code == "ash_tree"
                                } ?: false

                                if (!isAtAshTree) {
                                    println("${currentChar.name}: 🌲 Looking for nearest ash_tree...")

                                    // Find nearest ash_tree
                                    val ashTrees = client.content.getMaps(
                                        contentType = "resource",
                                        contentCode = "ash_tree",
                                        hideBlockedMaps = true,
                                        size = 50
                                    )

                                    if (ashTrees.data.isEmpty()) {
                                        println("  ❌ No ash_tree locations found!")
                                        delay(30.seconds)
                                        continue
                                    }

                                    // Find closest ash_tree
                                    val nearestTree = ashTrees.data.minByOrNull { tree ->
                                        kotlin.math.abs(tree.x - refreshedChar.x) + kotlin.math.abs(tree.y - refreshedChar.y)
                                    }!!

                                    println("  📍 Moving to ash_tree at (${nearestTree.x}, ${nearestTree.y})")
                                    val moveResult = client.actions.move(currentChar.name, nearestTree.x, nearestTree.y)
                                    println("  ✓ Arrived at ash_tree")

                                    // Wait for movement cooldown
                                    if (moveResult.cooldown.totalSeconds > 0) {
                                        delay(moveResult.cooldown.totalSeconds.seconds)
                                    }
                                }

                                // Now gather
                                val finalChar = client.characters.getCharacter(currentChar.name)
                                val finalTotalItems = finalChar.inventory.sumOf { it.quantity }

                                println("${currentChar.name}: 🪓 Gathering... (Inventory: $finalTotalItems/${finalChar.inventoryMaxItems})")
                                val result = client.actions.gather(currentChar.name)
                                gatherCount++

                                println("  ✓ ${currentChar.name} successfully gathered!")
                                println("  Gained: ${result.details.items.joinToString(", ") { "${it.quantity}x ${it.code}" }}")
                                println("  XP Gained: ${result.details.xp}")
                                println("  Cooldown: ${result.cooldown.totalSeconds}s")
                                println("  Stats: Gathers: $gatherCount | Deposits: $depositCount")
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

