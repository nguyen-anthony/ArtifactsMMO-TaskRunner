# ArtifactsMMO Client Library - Implementation Summary

## Overview

A complete Kotlin client library has been created for the ArtifactsMMO game API. The library provides a type-safe, coroutine-based interface for all API operations.

## Project Structure

```
client/
├── build.gradle.kts                    # Build configuration with dependencies
├── README.md                           # Complete documentation
└── src/main/kotlin/com/artifactsmmo/
    ├── client/
    │   ├── ArtifactsMMOClient.kt      # Main client class
    │   ├── ApiClient.kt                # HTTP client factory and base service
    │   ├── models/                     # Data models
    │   │   ├── Common.kt              # Common models (ApiResponse, DataPage, etc.)
    │   │   ├── Character.kt           # Character-related models
    │   │   ├── GameContent.kt         # Items, monsters, resources, maps
    │   │   └── Actions.kt             # Bank, GE, tasks, etc.
    │   ├── services/                   # API service interfaces
    │   │   ├── CharacterService.kt    # Character management
    │   │   ├── ActionService.kt       # Character actions
    │   │   ├── BankService.kt         # Bank operations
    │   │   ├── GrandExchangeService.kt # Trading
    │   │   ├── ContentService.kt      # Game content queries
    │   │   └── NPCAndTaskService.kt   # NPC & task operations
    │   └── utils/
    │       └── Utils.kt               # Helper utilities
    └── examples/
        └── ClientExample.kt           # Usage examples

```

## Key Features

### 1. **Complete API Coverage**
   - Character management (create, delete, get)
   - Character actions (move, fight, gather, craft, rest, equip, etc.)
   - Bank operations (deposit/withdraw gold and items)
   - Grand Exchange trading (buy, sell, cancel orders)
   - Game content queries (items, monsters, resources, maps, NPCs)
   - Task system (accept, complete, trade, cancel)
   - NPC trading

### 2. **Type-Safe Models**
   - Fully typed data classes with Kotlinx Serialization
   - Proper null handling
   - DateTime support with kotlinx-datetime

### 3. **Service Organization**
   The client is organized into logical service groups:
   
   ```kotlin
   val client = ArtifactsMMOClient.withToken("token")
   
   client.characters    // Character management
   client.actions       // Character actions
   client.bank          // Bank operations
   client.grandExchange // Trading
   client.content       // Game content queries
   client.npc           // NPC trading
   client.tasks         // Task operations
   ```

### 4. **Utility Functions**
   
   **CooldownUtils**
   - `isInCooldown()` - Check if character is in cooldown
   - `getRemainingCooldown()` - Get remaining seconds
   - `waitForCooldown()` - Suspend until cooldown expires
   
   **InventoryUtils**
   - `hasSpace()` - Check inventory space
   - `getItemQuantity()` - Get quantity of specific item
   - `hasItem()` - Check if item exists with min quantity
   - `getAvailableSpace()` - Get available slots
   
   **CharacterUtils**
   - `getSkillLevel()` - Get skill level by name
   - `hasEnoughHP()` - Check HP threshold
   - `getHPPercentage()` - Calculate HP percentage
   
   **MapUtils**
   - `manhattanDistance()` - Calculate grid distance
   - `euclideanDistance()` - Calculate direct distance

### 5. **Error Handling**
   ```kotlin
   try {
       val character = client.characters.getCharacter("name")
   } catch (e: ArtifactsApiException) {
       println("Error ${e.errorCode}: ${e.message}")
       e.errorData?.let { println("Details: $it") }
   }
   ```

### 6. **Resource Management**
   ```kotlin
   // Auto-close pattern
   ArtifactsMMOClient.withToken("token").use { client ->
       // Use client
   }
   
   // Or with helper function
   useArtifactsClient(token = "token") {
       val chars = characters.getMyCharacters()
   }
   ```

## Usage Examples

### Basic Character Operations

```kotlin
val client = ArtifactsMMOClient.withToken("your-token")

// Get all characters
val characters = client.characters.getMyCharacters()

// Create character
val newChar = client.characters.createCharacter("Hero", "men1")

// Get specific character
val char = client.characters.getCharacter("Hero")
```

### Character Actions

```kotlin
// Move character
val moveResult = client.actions.move("Hero", x = 5, y = 10)

// Fight monster
val fightResult = client.actions.fight("Hero")

// Gather resources
val gatherResult = client.actions.gather("Hero")

// Craft item
val craftResult = client.actions.craft("Hero", "wooden_staff", quantity = 1)

// Rest to heal
val restResult = client.actions.rest("Hero")

// Equip item
val equipResult = client.actions.equip("Hero", "iron_sword", "weapon")

// Use consumable
val useResult = client.actions.use("Hero", "health_potion")
```

### Bank Operations

```kotlin
// Get bank info
val bank = client.bank.getBankDetails()

// Get bank items
val items = client.bank.getBankItems()

// Deposit gold
client.bank.depositGold("Hero", 1000)

// Withdraw gold
client.bank.withdrawGold("Hero", 500)

// Deposit items
val items = listOf(SimpleItem("copper_ore", 10))
client.bank.depositItems("Hero", items)

// Withdraw items
client.bank.withdrawItems("Hero", items)

// Buy expansion
client.bank.buyExpansion("Hero")
```

### Grand Exchange

```kotlin
// Get all orders
val orders = client.grandExchange.getOrders(itemCode = "iron_ore")

// Get your orders
val myOrders = client.grandExchange.getMyOrders()

// Create sell order
val order = client.grandExchange.createSellOrder(
    "Hero", "copper_ore", quantity = 10, price = 100
)

// Buy from order
client.grandExchange.buyOrder("Hero", orderId = "id", quantity = 5)

// Cancel order
client.grandExchange.cancelOrder("Hero", orderId = "id")
```

### Content Queries

```kotlin
// Search items
val weapons = client.content.getItems(type = "weapon")
val item = client.content.getItem("iron_sword")

// Search monsters
val monsters = client.content.getMonsters(minLevel = 1, maxLevel = 10)
val monster = client.content.getMonster("chicken")

// Search resources
val resources = client.content.getResources(skill = "mining")
val resource = client.content.getResource("copper_ore")

// Search maps
val maps = client.content.getMaps(contentType = "monster")
val map = client.content.getMapByPosition("overworld", x = 0, y = 0)
val mapById = client.content.getMapById(mapId = 1)

// Get NPCs
val npcs = client.content.getNPCs()
val npc = client.content.getNPC("merchant")
val npcItems = client.content.getNPCItems("merchant")
```

### Tasks & NPCs

```kotlin
// NPC trading
client.npc.buyItem("Hero", "wooden_pickaxe", quantity = 1)
client.npc.sellItem("Hero", "copper_ore", quantity = 10)

// Task operations
val task = client.tasks.acceptNewTask("Hero")
val reward = client.tasks.completeTask("Hero")
val exchangeReward = client.tasks.exchangeTaskCoins("Hero")
client.tasks.cancelTask("Hero")
```

### With Utilities

```kotlin
val character = client.characters.getCharacter("Hero")

// Check cooldown
if (CooldownUtils.isInCooldown(character)) {
    val remaining = CooldownUtils.getRemainingCooldown(character)
    println("Cooldown: $remaining seconds")
    CooldownUtils.waitForCharacterCooldown(character)
}

// Check inventory
if (InventoryUtils.hasSpace(character, requiredSlots = 5)) {
    // Gather more items
    client.actions.gather(character.name)
}

val oreQty = InventoryUtils.getItemQuantity(character, "copper_ore")
println("You have $oreQty copper ore")

// Check HP
if (!CharacterUtils.hasEnoughHP(character, 0.5)) {
    client.actions.rest(character.name)
}
```

## Dependencies

The client uses the following libraries:
- **Ktor Client** - HTTP client with coroutine support
- **Kotlinx Serialization** - JSON serialization
- **Kotlinx Coroutines** - Async operations
- **Kotlinx DateTime** - Date/time handling
- **Kotlin Logging** - Logging support

## Integration

To use the client in your project:

1. Add to `settings.gradle.kts`:
   ```kotlin
   include(":client")
   ```

2. Add dependency in your module:
   ```kotlin
   dependencies {
       implementation(project(":client"))
   }
   ```

3. Use in your code:
   ```kotlin
   import com.artifactsmmo.client.ArtifactsMMOClient
   
   suspend fun main() {
       ArtifactsMMOClient.withToken("your-token").use { client ->
           val characters = client.characters.getMyCharacters()
           // ...
       }
   }
   ```

## Configuration Options

```kotlin
val client = ArtifactsMMOClient(
    token = "your-token",              // API token
    baseUrl = "https://api.artifactsmmo.com", // Base API URL
    enableLogging = true,              // Enable HTTP logging
    requestTimeoutMillis = 30_000,     // Request timeout
    connectTimeoutMillis = 10_000      // Connection timeout
)
```

## Error Handling

All API errors throw `ArtifactsApiException` with:
- `errorCode` - HTTP status or custom error code
- `message` - Error description
- `errorData` - Additional validation error details (if any)

## Thread Safety

The client is thread-safe and can be used from multiple coroutines concurrently. However, be mindful of API rate limits.

## Notes

- The library uses Kotlin coroutines - all operations are `suspend` functions
- For blocking contexts, use `BlockingArtifactsMMOClient` or `runBlocking`
- Always close the client when done or use `.use { }` for automatic cleanup
- The client respects API cooldowns - check character status before actions
- See `ClientExample.kt` for comprehensive usage examples
- See `README.md` in the client module for full documentation

## Next Steps

You can now:
1. Import the client in your app module
2. Set your API token (get it from the ArtifactsMMO website)
3. Start writing your game automation/bot logic
4. Use the utility functions to manage cooldowns, inventory, etc.

Example bot structure:
```kotlin
suspend fun main() {
    val token = System.getenv("ARTIFACTS_TOKEN") ?: error("Set ARTIFACTS_TOKEN")
    
    useArtifactsClient(token) {
        while (true) {
            val char = characters.getMyCharacters().first()
            
            // Wait for cooldown
            CooldownUtils.waitForCharacterCooldown(char)
            
            // Check HP and rest if needed
            if (!CharacterUtils.hasEnoughHP(char, 0.7)) {
                actions.rest(char.name)
                continue
            }
            
            // Perform action (gather, fight, craft, etc.)
            actions.gather(char.name)
            
            delay(1000) // Rate limiting
        }
    }
}
```

