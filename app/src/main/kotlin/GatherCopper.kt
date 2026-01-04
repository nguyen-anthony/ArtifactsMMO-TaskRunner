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

            // Get copper_bar crafting requirements
            println("=== Getting Copper Bar Crafting Info ===")
            val copperBarItem = client.content.getItem("copper_bar")
            val copperOrePerBar = copperBarItem.craft?.items?.find { it.code == "copper_ore" }?.quantity ?: 10
            println("Copper bar crafting requires: ${copperOrePerBar}x copper_ore -> 1x copper_bar")
            println()

            // Clean up bank copper_ore first
            println("=== Checking Bank for Copper Ore ===")
            val bankItems = client.bank.getBankItems(itemCode = "copper_ore", size = 1)
            val copperOreInBank = bankItems.data.firstOrNull()?.quantity ?: 0
            println("Found ${copperOreInBank}x copper_ore in bank")

            if (copperOreInBank >= copperOrePerBar * 5) {
                println("Enough copper_ore to craft ${copperOreInBank / copperOrePerBar} bars (cleaning up bank first)")
                println()

                // Use all characters to clean up the bank concurrently
                println("Using all ${characters.size} characters to clean up bank copper_ore")

                coroutineScope {
                    for (cleanupChar in characters) {
                        launch {
                            try {
                                // Find nearest bank
                                val banks = client.content.getMaps(contentType = "bank", hideBlockedMaps = true, size = 50)
                                val nearestBank = banks.data.minByOrNull { bank ->
                                    abs(bank.x - cleanupChar.x) + abs(bank.y - cleanupChar.y)
                                }!!

                                // Move to bank
                                println("  ${cleanupChar.name}: 📍 Moving to bank at (${nearestBank.x}, ${nearestBank.y})")
                                var result = client.actions.move(cleanupChar.name, nearestBank.x, nearestBank.y)
                                delay(result.cooldown.totalSeconds.seconds)

                                // Check current bank inventory and withdraw copper_ore
                                var currentBankItems = client.bank.getBankItems(itemCode = "copper_ore", size = 1)
                                var currentCopperOre = currentBankItems.data.firstOrNull()?.quantity ?: 0

                                while (currentCopperOre >= copperOrePerBar) {
                                    val maxWithdraw = minOf(currentCopperOre, cleanupChar.inventoryMaxItems - 10) // Leave some space
                                    println("  ${cleanupChar.name}: 💰 Withdrawing ${maxWithdraw}x copper_ore from bank")
                                    val withdrawResult = client.bank.withdrawItems(cleanupChar.name, listOf(SimpleItem("copper_ore", maxWithdraw)))
                                    delay(withdrawResult.cooldown.totalSeconds.seconds)

                                    // Find mining station
                                    val miningStations = client.content.getMaps(contentType = "workshop", contentCode = "mining", hideBlockedMaps = true, size = 50)
                                    if (miningStations.data.isEmpty()) {
                                        println("  ${cleanupChar.name}: ❌ No mining stations found!")
                                    } else {
                                        val nearestStation = miningStations.data.minByOrNull { station ->
                                            abs(station.x - cleanupChar.x) + abs(station.y - cleanupChar.y)
                                        }!!

                                        // Move to mining station
                                        println("  ${cleanupChar.name}: 📍 Moving to mining station at (${nearestStation.x}, ${nearestStation.y})")
                                        result = client.actions.move(cleanupChar.name, nearestStation.x, nearestStation.y)
                                        delay(result.cooldown.totalSeconds.seconds)

                                        // Craft copper bars
                                        val updatedChar = client.characters.getCharacter(cleanupChar.name)
                                        val copperOreCount = updatedChar.inventory.find { it.code == "copper_ore" }?.quantity ?: 0
                                        val barsToCraft = copperOreCount / copperOrePerBar

                                        if (barsToCraft > 0) {
                                            println("  ${cleanupChar.name}: 🔨 Crafting ${barsToCraft}x copper_bar from ${copperOreCount}x copper_ore")
                                            val craftResult = client.actions.craft(cleanupChar.name, "copper_bar", barsToCraft)
                                            println("  ${cleanupChar.name}: ✓ Crafted successfully! XP gained: ${craftResult.details.xp}")
                                            delay(craftResult.cooldown.totalSeconds.seconds)

                                            // Move back to bank
                                            println("  ${cleanupChar.name}: 📍 Moving back to bank")
                                            result = client.actions.move(cleanupChar.name, nearestBank.x, nearestBank.y)
                                            delay(result.cooldown.totalSeconds.seconds)

                                            // Deposit copper bars
                                            val charAfterCraft = client.characters.getCharacter(cleanupChar.name)
                                            val copperBars = charAfterCraft.inventory.find { it.code == "copper_bar" }?.quantity ?: 0
                                            if (copperBars > 0) {
                                                println("  ${cleanupChar.name}: 💰 Depositing ${copperBars}x copper_bar to bank")
                                                val depositResult = client.bank.depositItems(cleanupChar.name, listOf(SimpleItem("copper_bar", copperBars)))
                                                delay(depositResult.cooldown.totalSeconds.seconds)
                                                println("  ${cleanupChar.name}: ✓ Cleanup complete!")
                                            }
                                        }
                                    }
                                    currentBankItems = client.bank.getBankItems(itemCode = "copper_ore", size = 1)
                                    currentCopperOre = currentBankItems.data.firstOrNull()?.quantity ?: 0
                                }
                            } catch (e: ArtifactsApiException) {
                                // Handle specific error codes that shouldn't stop the cleanup
                                when (e.errorCode) {
                                    497 -> {
                                        println("  ${cleanupChar.name}: ⚠️ Already at destination, continuing...")
                                    }
                                    490 -> {
                                        println("  ${cleanupChar.name}: ⚠️ Inventory issue, continuing...")
                                    }
                                    else -> {
                                        println("  ${cleanupChar.name}: ❌ Error during bank cleanup: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                }
                println("=== Bank Cleanup Complete ===")
            } else {
                println("Not enough copper_ore in bank to craft 5+ bars, skipping cleanup")
                println()
            }

            // Gather copper_ore with all characters concurrently
            println("=== Starting Concurrent Gathering ===")
            println("All characters will gather independently from their current positions")
            println("Press Ctrl+C to stop")
            println()

            // Launch a coroutine for each character to gather independently
            coroutineScope {
                for (character in characters) {
                    launch {
                        var gatherCount = 0
                        var craftCount = 0
                        var depositCount = 0

                        while (true) {
                            try {
                                // Refresh character data to check inventory
                                val currentChar = client.characters.getCharacter(character.name)

                                // Check if inventory is getting full (80% or more)
                                val totalItems = currentChar.inventory.sumOf { it.quantity }
                                val inventoryThreshold = (currentChar.inventoryMaxItems * 0.8).toInt()

                                if (totalItems >= inventoryThreshold) {
                                    println("${currentChar.name}: 📦 Inventory is ${totalItems}/${currentChar.inventoryMaxItems} - processing copper ore")

                                    // First, go to mining station to craft copper bars
                                    val miningStations = client.content.getMaps(
                                        contentType = "workshop",
                                        contentCode = "mining",
                                        hideBlockedMaps = true,
                                        size = 50
                                    )

                                    if (miningStations.data.isEmpty()) {
                                        println("  ❌ No mining stations found!")
                                        delay(30.seconds)
                                        continue
                                    }

                                    // Find closest mining station
                                    val nearestStation = miningStations.data.minByOrNull { station ->
                                        abs(station.x - currentChar.x) + abs(station.y - currentChar.y)
                                    }!!

                                    println("  📍 Moving to mining station at (${nearestStation.x}, ${nearestStation.y})")
                                    var moveResult = client.actions.move(currentChar.name, nearestStation.x, nearestStation.y)
                                    println("  ✓ Arrived at mining station")

                                    // Wait for movement cooldown
                                    if (moveResult.cooldown.totalSeconds > 0) {
                                        delay(moveResult.cooldown.totalSeconds.seconds)
                                    }

                                    // Craft copper bars
                                    val charAtStation = client.characters.getCharacter(currentChar.name)
                                    val copperOreCount = charAtStation.inventory.find { it.code == "copper_ore" }?.quantity ?: 0
                                    val barsToCraft = copperOreCount / copperOrePerBar

                                    if (barsToCraft > 0) {
                                        println("  🔨 Crafting ${barsToCraft}x copper_bar from ${copperOreCount}x copper_ore")
                                        val craftResult = client.actions.craft(currentChar.name, "copper_bar", barsToCraft)
                                        craftCount += barsToCraft
                                        println("  ✓ Crafted successfully! XP gained: ${craftResult.details.xp}")

                                        // Wait for crafting cooldown
                                        if (craftResult.cooldown.totalSeconds > 0) {
                                            delay(craftResult.cooldown.totalSeconds.seconds)
                                        }
                                    }

                                    // Now find nearest bank and deposit copper bars
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
                                    val charAfterCraft = client.characters.getCharacter(currentChar.name)
                                    val nearestBank = banks.data.minByOrNull { bank ->
                                        abs(bank.x - charAfterCraft.x) + abs(bank.y - charAfterCraft.y)
                                    }!!

                                    println("  📍 Moving to bank at (${nearestBank.x}, ${nearestBank.y})")
                                    moveResult = client.actions.move(currentChar.name, nearestBank.x, nearestBank.y)
                                    println("  ✓ Arrived at bank")

                                    // Wait for movement cooldown
                                    if (moveResult.cooldown.totalSeconds > 0) {
                                        delay(moveResult.cooldown.totalSeconds.seconds)
                                    }

                                    // Deposit copper_bar and other items
                                    val updatedChar = client.characters.getCharacter(currentChar.name)
                                    val itemsToDeposit = updatedChar.inventory
                                        .filter { it.code in listOf("copper_bar", "copper_ore", "topaz_stone", "emerald_stone", "ruby_stone", "sapphire_stone", "ash_wood", "sap", "apple") && it.quantity > 0 }
                                        .map { SimpleItem(it.code, it.quantity) }

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

                                // ...existing code for finding and moving to copper_rocks...
                                val refreshedChar = client.characters.getCharacter(currentChar.name)
                                val currentLocation = client.content.getMapByPosition(
                                    layer = refreshedChar.layer,
                                    x = refreshedChar.x,
                                    y = refreshedChar.y
                                )

                                // Check if we're already at copper_rocks
                                val isAtCopperRocks = currentLocation.interactions.content?.let {
                                    it.type == "resource" && it.code == "copper_rocks"
                                } ?: false

                                if (!isAtCopperRocks) {
                                    println("${currentChar.name}: 🌲 Looking for nearest copper rocks...")

                                    // Find nearest copper_rocks
                                    val copperRocks = client.content.getMaps(
                                        contentType = "resource",
                                        contentCode = "copper_rocks",
                                        hideBlockedMaps = true,
                                        size = 50
                                    )

                                    if (copperRocks.data.isEmpty()) {
                                        println("  ❌ No copper rocks locations found!")
                                        delay(30.seconds)
                                        continue
                                    }

                                    // Find closest copper_rocks
                                    val nearestCopperRock = copperRocks.data.minByOrNull { copper ->
                                        abs(copper.x - refreshedChar.x) + abs(copper.y - refreshedChar.y)
                                    }!!

                                    println("  📍 Moving to copper rocks at (${nearestCopperRock.x}, ${nearestCopperRock.y})")
                                    val moveResult = client.actions.move(currentChar.name, nearestCopperRock.x, nearestCopperRock.y)
                                    println("  ✓ Arrived at copper rocks")

                                    // Wait for movement cooldown
                                    if (moveResult.cooldown.totalSeconds > 0) {
                                        delay(moveResult.cooldown.totalSeconds.seconds)
                                    }
                                }

                                // Now gather
                                val finalChar = client.characters.getCharacter(currentChar.name)
                                val finalTotalItems = finalChar.inventory.sumOf { it.quantity }

                                println("${currentChar.name}: ⛏️ Gathering... (Inventory: $finalTotalItems/${finalChar.inventoryMaxItems})")
                                val result = client.actions.gather(currentChar.name)
                                gatherCount++

                                println("  ✓ ${currentChar.name} successfully gathered!")
                                println("  Gained: ${result.details.items.joinToString(", ") { "${it.quantity}x ${it.code}" }}")
                                println("  XP Gained: ${result.details.xp}")
                                println("  Cooldown: ${result.cooldown.totalSeconds}s")
                                println("  Stats: Gathers: $gatherCount | Crafts: $craftCount | Deposits: $depositCount")
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

