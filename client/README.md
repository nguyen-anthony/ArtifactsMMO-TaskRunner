# ArtifactsMMO Client Library

A Kotlin client library for interacting with the ArtifactsMMO game API.

## Features

- 🎮 Complete API coverage for ArtifactsMMO
- ⚡ Coroutine-based async operations
- 🔐 Built-in authentication support
- 📦 Type-safe models with Kotlinx Serialization
- 🛠️ Utility functions for common operations
- 🎯 Organized service interfaces

## Installation

Add the client module to your project:

```kotlin
dependencies {
    implementation(project(":client"))
}
```

## Quick Start

### Basic Usage

```kotlin
import com.artifactsmmo.client.ArtifactsMMOClient

// Create a client with your API token
val client = ArtifactsMMOClient.withToken("your-api-token")

// Get your characters
val characters = client.characters.getMyCharacters()
println("You have ${characters.size} characters")

// Move a character
val moveResult = client.actions.move("MyCharacter", x = 5, y = 10)
println("Moved to ${moveResult.destination.name}")

// Close the client when done
client.close()
```

### Using Resource Management

```kotlin
import com.artifactsmmo.client.useArtifactsClient

useArtifactsClient(token = "your-api-token") {
    val characters = characters.getMyCharacters()
    
    for (character in characters) {
        println("${character.name} - Level ${character.level}")
    }
}
```

## API Services

The client is organized into several service interfaces:

### Character Service

```kotlin
// Get character details
val character = client.characters.getCharacter("MyCharacter")

// Get all your characters
val myCharacters = client.characters.getMyCharacters()

// Create a new character
val newCharacter = client.characters.createCharacter(
    name = "NewHero",
    skin = "men1"
)
```

### Action Service

```kotlin
// Move character
val moveResult = client.actions.move("MyCharacter", x = 5, y = 10)

// Fight a monster
val fightResult = client.actions.fight("MyCharacter")

// Gather resources
val gatherResult = client.actions.gather("MyCharacter")

// Craft an item
val craftResult = client.actions.craft("MyCharacter", itemCode = "wooden_staff", quantity = 1)

// Rest to restore HP
val restResult = client.actions.rest("MyCharacter")

// Equip an item
val equipResult = client.actions.equip("MyCharacter", itemCode = "iron_sword", slot = "weapon")

// Use a consumable
val useResult = client.actions.use("MyCharacter", itemCode = "health_potion")
```

### Bank Service

```kotlin
// Get bank details
val bank = client.bank.getBankDetails()

// Get items in bank
val bankItems = client.bank.getBankItems()

// Deposit gold
val depositResult = client.bank.depositGold("MyCharacter", quantity = 1000)

// Withdraw gold
val withdrawResult = client.bank.withdrawGold("MyCharacter", quantity = 500)

// Deposit items
val items = listOf(SimpleItem("copper_ore", 10))
val depositItemsResult = client.bank.depositItems("MyCharacter", items)

// Buy bank expansion
val expansionResult = client.bank.buyExpansion("MyCharacter")
```

### Grand Exchange Service

```kotlin
// Get all sell orders
val orders = client.grandExchange.getOrders(itemCode = "iron_ore")

// Get your orders
val myOrders = client.grandExchange.getMyOrders()

// Create a sell order
val sellOrder = client.grandExchange.createSellOrder(
    characterName = "MyCharacter",
    itemCode = "copper_ore",
    quantity = 10,
    price = 100
)

// Buy from an order
val buyResult = client.grandExchange.buyOrder(
    characterName = "MyCharacter",
    orderId = "order-id",
    quantity = 5
)

// Cancel an order
val cancelResult = client.grandExchange.cancelOrder("MyCharacter", "order-id")
```

### Content Service

```kotlin
// Get items
val weapons = client.content.getItems(type = "weapon")

// Get a specific item
val item = client.content.getItem("iron_sword")

// Get monsters
val monsters = client.content.getMonsters(minLevel = 1, maxLevel = 10)

// Get a specific monster
val monster = client.content.getMonster("chicken")

// Get resources
val resources = client.content.getResources(skill = "mining")

// Get maps
val maps = client.content.getMaps(contentType = "monster")

// Get map by position
val map = client.content.getMapByPosition(layer = "overworld", x = 0, y = 0)

// Get NPCs
val npcs = client.content.getNPCs()
```

### NPC Service

```kotlin
// Buy from NPC
val buyResult = client.npc.buyItem("MyCharacter", itemCode = "wooden_pickaxe", quantity = 1)

// Sell to NPC
val sellResult = client.npc.sellItem("MyCharacter", itemCode = "copper_ore", quantity = 10)
```

### Task Service

```kotlin
// Accept a new task
val task = client.tasks.acceptNewTask("MyCharacter")

// Complete a task
val reward = client.tasks.completeTask("MyCharacter")

// Exchange task coins
val exchangeReward = client.tasks.exchangeTaskCoins("MyCharacter")

// Cancel a task
client.tasks.cancelTask("MyCharacter")
```

## Utility Functions

The library includes utility functions to help with common operations:

### Cooldown Management

```kotlin
import com.artifactsmmo.client.utils.CooldownUtils

// Check if character is in cooldown
if (CooldownUtils.isInCooldown(character)) {
    val remaining = CooldownUtils.getRemainingCooldown(character)
    println("Character is in cooldown for $remaining seconds")
}

// Wait for cooldown to expire
CooldownUtils.waitForCharacterCooldown(character)
```

### Inventory Management

```kotlin
import com.artifactsmmo.client.utils.InventoryUtils

// Check if inventory has space
if (InventoryUtils.hasSpace(character, requiredSlots = 5)) {
    println("Inventory has space for 5 more items")
}

// Get item quantity
val oreQuantity = InventoryUtils.getItemQuantity(character, "copper_ore")

// Check if character has an item
if (InventoryUtils.hasItem(character, "health_potion", minQuantity = 3)) {
    println("Character has at least 3 health potions")
}
```

### Character Stats

```kotlin
import com.artifactsmmo.client.utils.CharacterUtils

// Get skill level
val miningLevel = CharacterUtils.getSkillLevel(character, "mining")

// Check HP
if (!CharacterUtils.hasEnoughHP(character, minPercentage = 0.5)) {
    // Character has less than 50% HP
    client.actions.rest(character.name)
}

// Get HP percentage
val hpPercent = CharacterUtils.getHPPercentage(character)
println("HP: ${(hpPercent * 100).toInt()}%")
```

## Error Handling

```kotlin
import com.artifactsmmo.client.ArtifactsApiException

try {
    val character = client.characters.getCharacter("NonExistent")
} catch (e: ArtifactsApiException) {
    println("API Error ${e.errorCode}: ${e.message}")
    e.errorData?.let { data ->
        println("Validation errors: $data")
    }
}
```

## Configuration

```kotlin
val client = ArtifactsMMOClient(
    token = "your-api-token",
    baseUrl = "https://api.artifactsmmo.com",
    enableLogging = true,
    requestTimeoutMillis = 30_000,
    connectTimeoutMillis = 10_000
)
```

## License

This client library is provided as-is for use with the ArtifactsMMO game.

