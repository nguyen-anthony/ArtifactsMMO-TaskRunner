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

            println("=== Getting Ash Plank Crafting Info ===")
            val ashPlankItem = client.content.getItem("ash_plank")
            val ashLogPerPlank = ashPlankItem.craft?.items?.find { it.code == "ash_wood" }?.quantity ?: 10
            println("Ash Plank crafting requires: ${ashLogPerPlank}x ash wood -> 1x ash plank")
            println()

            println("=== Checking Bank for Ash Wood ===")
            val bankItems = client.bank.getBankItems(itemCode = "ash_wood", size = 1)
            val ashWoodInBank = bankItems.data.firstOrNull()?.quantity ?: 0
            println("Found ${ashWoodInBank}x ash_wood in bank")

            if (ashWoodInBank >= ashLogPerPlank) {
                println("Enough ash_wood to craft ${ashWoodInBank / ashLogPerPlank} planks (cleaning up bank first)")
                println()

                // Use all characters to clean up the bank concurrently
                println("Using all ${characters.size} characters to clean up bank ash_wood")

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

                                // Check current bank inventory and withdraw ash_wood
                                var currentBankItems = client.bank.getBankItems(itemCode = "ash_wood", size = 1)
                                var currentAshWood = currentBankItems.data.firstOrNull()?.quantity ?: 0

                                while (currentAshWood >= ashLogPerPlank) {
                                    val maxWithdraw = minOf(currentAshWood, cleanupChar.inventoryMaxItems - 10) // Leave some space
                                    println("  ${cleanupChar.name}: 💰 Withdrawing ${maxWithdraw}x ash_wood from bank")
                                    val withdrawResult = client.bank.withdrawItems(cleanupChar.name, listOf(SimpleItem("ash_wood", maxWithdraw)))
                                    delay(withdrawResult.cooldown.totalSeconds.seconds)

                                    // Find mining station
                                    val miningStations = client.content.getMaps(contentType = "workshop", contentCode = "woodcutting", hideBlockedMaps = true, size = 50)
                                    if (miningStations.data.isEmpty()) {
                                        println("  ${cleanupChar.name}: No woodcutting stations found!")
                                    } else {
                                        val nearestStation = miningStations.data.minByOrNull { station ->
                                            abs(station.x - cleanupChar.x) + abs(station.y - cleanupChar.y)
                                        }!!

                                        // Move to mining station
                                        println("  ${cleanupChar.name}: Moving to mining station at (${nearestStation.x}, ${nearestStation.y})")
                                        result = client.actions.move(cleanupChar.name, nearestStation.x, nearestStation.y)
                                        delay(result.cooldown.totalSeconds.seconds)

                                        // Craft ash planks
                                        val updatedChar = client.characters.getCharacter(cleanupChar.name)
                                        val ashLogCount = updatedChar.inventory.find { it.code == "ash_wood" }?.quantity ?: 0
                                        val planksToCraft = ashLogCount / ashLogPerPlank

                                        if (planksToCraft > 0) {
                                            println("  ${cleanupChar.name}: Crafting ${planksToCraft}x ash_plank from ${ashLogCount}x ash_wood")
                                            val craftResult = client.actions.craft(cleanupChar.name, "ash_plank", planksToCraft)
                                            println("  ${cleanupChar.name}: ✓ Crafted successfully! XP gained: ${craftResult.details.xp}")
                                            delay(craftResult.cooldown.totalSeconds.seconds)

                                            // Move back to bank
                                            println("  ${cleanupChar.name}: Moving back to bank")
                                            result = client.actions.move(cleanupChar.name, nearestBank.x, nearestBank.y)
                                            delay(result.cooldown.totalSeconds.seconds)

                                            // Deposit ash_planks
                                            val charAfterCraft = client.characters.getCharacter(cleanupChar.name)
                                            val ashPlanks = charAfterCraft.inventory.find { it.code == "ash_plank" }?.quantity ?: 0
                                            if (ashPlanks > 0) {
                                                println("  ${cleanupChar.name}: 💰 Depositing ${ashPlanks}x ash_wood to bank")
                                                val depositResult = client.bank.depositItems(cleanupChar.name, listOf(SimpleItem("ash_planks", ashPlanks)))
                                                delay(depositResult.cooldown.totalSeconds.seconds)
                                                println("  ${cleanupChar.name}: ✓ Cleanup complete!")
                                            }
                                        }
                                    }
                                    currentBankItems = client.bank.getBankItems(itemCode = "ash_wood", size = 1)
                                    currentAshWood = currentBankItems.data.firstOrNull()?.quantity ?: 0
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
                println("Not enough ash_wood in bank to craft 5+ bars, skipping cleanup")
                println()
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

