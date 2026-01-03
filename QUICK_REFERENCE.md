# ArtifactsMMO Client - Quick Reference

## Installation

```kotlin
// In your build.gradle.kts
dependencies {
    implementation(project(":client"))
}
```

## Basic Setup

```kotlin
import com.artifactsmmo.client.ArtifactsMMOClient

// Create client
val client = ArtifactsMMOClient.withToken("your-api-token")

// Or use with auto-close
ArtifactsMMOClient.withToken("token").use { client ->
    // Your code
}
```

## Character Operations

```kotlin
// Get all your characters
val characters = client.characters.getMyCharacters()

// Get specific character
val char = client.characters.getCharacter("CharName")

// Create character
val newChar = client.characters.createCharacter("Name", "men1")

// Delete character
client.characters.deleteCharacter("Name")
```

## Movement & Combat

```kotlin
// Move by coordinates
client.actions.move("CharName", x = 5, y = 10)

// Move by map ID
client.actions.moveToMap("CharName", mapId = 123)

// Fight monster at current location
client.actions.fight("CharName")

// Multi-character fight (boss)
client.actions.fight("MainChar", participants = listOf("Char2", "Char3"))

// Rest to heal
client.actions.rest("CharName")
```

## Resource Gathering & Crafting

```kotlin
// Gather at current location
client.actions.gather("CharName")

// Craft item
client.actions.craft("CharName", itemCode = "wooden_staff", quantity = 1)

// Recycle item
client.actions.recycle("CharName", itemCode = "old_sword", quantity = 1)
```

## Equipment

```kotlin
// Equip item
client.actions.equip("CharName", itemCode = "iron_sword", slot = "weapon")

// Equip utilities (can stack up to 100)
client.actions.equip("CharName", itemCode = "health_potion", slot = "utility1", quantity = 10)

// Unequip item
client.actions.unequip("CharName", slot = "weapon")

// Use consumable
client.actions.use("CharName", itemCode = "health_potion")

// Delete item
client.actions.deleteItem("CharName", itemCode = "junk_item", quantity = 5)
```

## Bank Operations

```kotlin
// Get bank info
val bank = client.bank.getBankDetails()

// Get bank items
val items = client.bank.getBankItems()

// Deposit gold
client.bank.depositGold("CharName", quantity = 1000)

// Withdraw gold
client.bank.withdrawGold("CharName", quantity = 500)

// Deposit items
val items = listOf(
    SimpleItem("copper_ore", 10),
    SimpleItem("iron_ore", 5)
)
client.bank.depositItems("CharName", items)

// Withdraw items
client.bank.withdrawItems("CharName", items)

// Buy bank expansion
client.bank.buyExpansion("CharName")
```

## Grand Exchange

```kotlin
// Search orders
val orders = client.grandExchange.getOrders(itemCode = "iron_ore")

// Get your orders
val myOrders = client.grandExchange.getMyOrders()

// Create sell order
client.grandExchange.createSellOrder(
    characterName = "CharName",
    itemCode = "copper_ore",
    quantity = 10,
    price = 100  // per unit
)

// Buy from order
client.grandExchange.buyOrder(
    characterName = "CharName",
    orderId = "order-id-here",
    quantity = 5
)

// Cancel your order
client.grandExchange.cancelOrder("CharName", orderId = "order-id")
```

## NPC Trading

```kotlin
// Buy from NPC
client.npc.buyItem("CharName", itemCode = "wooden_pickaxe", quantity = 1)

// Sell to NPC
client.npc.sellItem("CharName", itemCode = "copper_ore", quantity = 10)
```

## Tasks

```kotlin
// Accept new task
val task = client.tasks.acceptNewTask("CharName")

// Trade items for task progress
client.tasks.tradeTask("CharName", itemCode = "wolf_fur", quantity = 10)

// Complete task
val reward = client.tasks.completeTask("CharName")

// Exchange 6 task coins for reward
val reward = client.tasks.exchangeTaskCoins("CharName")

// Cancel task (costs 1 task coin)
client.tasks.cancelTask("CharName")
```

## Content Queries

```kotlin
// Items
val items = client.content.getItems(type = "weapon", minLevel = 10, maxLevel = 20)
val item = client.content.getItem("iron_sword")

// Monsters
val monsters = client.content.getMonsters(minLevel = 1, maxLevel = 10)
val monster = client.content.getMonster("chicken")

// Resources
val resources = client.content.getResources(skill = "mining")
val resource = client.content.getResource("copper_ore")

// Maps
val maps = client.content.getMaps(contentType = "monster", layer = "overworld")
val map = client.content.getMapByPosition("overworld", x = 0, y = 0)
val mapById = client.content.getMapById(mapId = 123)

// NPCs
val npcs = client.content.getNPCs(type = "merchant")
val npc = client.content.getNPC("merchant_code")
val npcItems = client.content.getNPCItems("merchant_code")
```

## Utility Functions

```kotlin
import com.artifactsmmo.client.utils.*

// Cooldown
if (CooldownUtils.isInCooldown(character)) {
    val seconds = CooldownUtils.getRemainingCooldown(character)
    println("Wait $seconds seconds")
}
await CooldownUtils.waitForCharacterCooldown(character)

// Inventory
val hasSpace = InventoryUtils.hasSpace(character, requiredSlots = 5)
val quantity = InventoryUtils.getItemQuantity(character, "copper_ore")
val hasItem = InventoryUtils.hasItem(character, "health_potion", minQuantity = 3)
val availableSlots = InventoryUtils.getAvailableSpace(character)

// Character
val skillLevel = CharacterUtils.getSkillLevel(character, "mining")
val hasHP = CharacterUtils.hasEnoughHP(character, minPercentage = 0.5)
val hpPercent = CharacterUtils.getHPPercentage(character)

// Map
val distance = MapUtils.manhattanDistance(x1, y1, x2, y2)
```

## Common Patterns

### Auto-Gather Loop
```kotlin
while (true) {
    val char = client.characters.getCharacter("Gatherer")
    CooldownUtils.waitForCharacterCooldown(char)
    
    if (!InventoryUtils.hasSpace(char)) {
        // Bank items or stop
        break
    }
    
    client.actions.gather(char.name)
}
```

### Auto-Fight Loop
```kotlin
while (true) {
    val char = client.characters.getCharacter("Fighter")
    CooldownUtils.waitForCharacterCooldown(char)
    
    if (!CharacterUtils.hasEnoughHP(char, 0.7)) {
        client.actions.rest(char.name)
        continue
    }
    
    client.actions.fight(char.name)
}
```

### Bank Depositing
```kotlin
val char = client.characters.getCharacter("Miner")

// Move to bank
client.actions.move(char.name, x = BANK_X, y = BANK_Y)

// Deposit all copper ore
val copperQty = InventoryUtils.getItemQuantity(char, "copper_ore")
if (copperQty > 0) {
    client.bank.depositItems(char.name, listOf(
        SimpleItem("copper_ore", copperQty)
    ))
}
```

## Error Handling

```kotlin
import com.artifactsmmo.client.ArtifactsApiException

try {
    client.actions.fight("CharName")
} catch (e: ArtifactsApiException) {
    when (e.errorCode) {
        498 -> println("Character not found")
        499 -> println("Character in cooldown")
        486 -> println("Action in progress")
        else -> println("Error ${e.errorCode}: ${e.message}")
    }
}
```

## Common Error Codes

- `486` - Action already in progress
- `492` - Insufficient gold
- `493` - Skill level too low
- `497` - Inventory full
- `498` - Character not found
- `499` - Character in cooldown

## Item Slots

Valid equipment slots:
- `weapon`, `shield`, `helmet`
- `body_armor`, `leg_armor`, `boots`
- `ring1`, `ring2`, `amulet`
- `artifact1`, `artifact2`, `artifact3`
- `utility1`, `utility2`
- `rune`, `bag`

## Character Skins

Default skins (everyone has):
- `men1`, `men2`, `men3`
- `women1`, `women2`, `women3`

Special skins (unlock required):
- `corrupted1`, `zombie1`, `marauder1`

