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
                println("No characters found. Please create a character first.")
                return@use
            }

            println("Found ${characters.size} character(s):")
            for (char in characters) {
                println("  - ${char.name} (Level ${char.level}) at (${char.x}, ${char.y})")
            }
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
                                val healAmountPerChicken = 80
                                val chickensNeeded = if (hpMissing <= healAmountPerChicken) 1 else (hpMissing.toDouble() / healAmountPerChicken).toInt().coerceAtLeast(1)

                                if (hpMissing >= healAmountPerChicken) {
                                    val cookedChickenCount = currentChar.inventory.find { it.code == "cooked_chicken" }?.quantity ?: 0
                                    val cookedChickenInBank = client.bank.getBankItems("cooked_chicken").data.first().quantity

                                    if (cookedChickenCount > 0) {
                                        // Use cooked chicken to heal
                                        val chickensToUse = minOf(chickensNeeded, cookedChickenCount)

                                        println("${currentChar.name}: 🍗 HP low (${currentChar.hp}/${currentChar.maxHp}), eating cooked_chicken...")
                                        val useResult = client.actions.use(currentChar.name, "cooked_chicken", chickensToUse)
                                        println("  ✓ Healed! HP: ${useResult.character.hp}/${useResult.character.maxHp}")
                                        delay(useResult.cooldown.totalSeconds.seconds)
                                    } else if (cookedChickenInBank > 0) {
                                        // No cooked chicken but have raw chicken - go cook it!
                                        println("${currentChar.name}: 🍳 HP low (${currentChar.hp}/${currentChar.maxHp}), grabbing chicken from bank.")

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

                                        if (moveResult.cooldown.totalSeconds > 0) {
                                            delay(moveResult.cooldown.totalSeconds.seconds)
                                        }

                                        // Withdraw cooked chicken from bank
                                        val withdrawAmount = client.bank.withdrawItems(
                                            characterName = currentChar.name,
                                            items = listOf(SimpleItem("cooked_chicken", 25)
                                        ))

                                        if (withdrawAmount.cooldown.totalSeconds > 0) {
                                            delay(withdrawAmount.cooldown.totalSeconds.seconds)
                                        }

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

                                    // Build list of items to deposit
                                    val itemsToDeposit = mutableListOf<SimpleItem>()

                                    // Always deposit eggs, feathers, and golden_eggs
                                    updatedChar.inventory
                                        .filter { it.code in listOf("apple", "sheepball", "wool") && it.quantity > 0 }
                                        .forEach { itemsToDeposit.add(SimpleItem(it.code, it.quantity)) }

                                    if (itemsToDeposit.isNotEmpty()) {
                                        println("  💰 Depositing items: ${itemsToDeposit.joinToString(", ") { "${it.quantity}x ${it.code}" }}")
                                        val depositResult = client.bank.depositItems(currentChar.name, itemsToDeposit)

                                        // Wait for bank cooldown
                                        if (depositResult.cooldown.totalSeconds > 0) {
                                            delay(depositResult.cooldown.totalSeconds.seconds)
                                        }
                                    }
                                }

                                // Now find nearest sheep and move there if not already there
                                val refreshedChar = client.characters.getCharacter(currentChar.name)
                                val currentLocation = client.content.getMapByPosition(
                                    layer = refreshedChar.layer,
                                    x = refreshedChar.x,
                                    y = refreshedChar.y
                                )

                                // Check if we're already at sheep
                                val isAtMonster = currentLocation.interactions.content?.let {
                                    it.type == "monster" && it.code == "sheep"
                                } ?: false

                                if (!isAtMonster) {
                                    println("${currentChar.name}: Looking for monster to fight ...")

                                    // Find sheep closest to character
                                    val mob = client.content.getMaps(
                                        contentType = "monster",
                                        contentCode = "sheep",
                                        hideBlockedMaps = true,
                                        size = 50
                                    )

                                    if (mob.data.isEmpty()) {
                                        println("No yellowSlime locations found!")
                                        delay(30.seconds)
                                        continue
                                    }

                                    // Find sheep closest to cooking station for optimal gathering loop
                                    val optimalMob = mob.data.minByOrNull { m ->
                                        abs(m.x - refreshedChar.x) + abs(m.y - refreshedChar.y)
                                    }!!

                                    println("Moving to optimal sheep at (${optimalMob.x}, ${optimalMob.y})")
                                    val moveResult = client.actions.move(currentChar.name, optimalMob.x, optimalMob.y)
                                    println("Arrived at sheep")

                                    // Wait for movement cooldown
                                    if (moveResult.cooldown.totalSeconds > 0) {
                                        delay(moveResult.cooldown.totalSeconds.seconds)
                                    }
                                }

                                // Now gather
                                val finalChar = client.characters.getCharacter(currentChar.name)
                                val finalTotalItems = finalChar.inventory.sumOf { it.quantity }

                                println("${currentChar.name}: Fighting sheeps... (Inventory: $finalTotalItems/${finalChar.inventoryMaxItems})")
                                val result = client.actions.fight(currentChar.name)

                                println("  ✓ ${currentChar.name} successfully fought!")
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

