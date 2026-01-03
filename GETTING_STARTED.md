# Getting Started with ArtifactsMMO Client

This guide will help you get up and running with the ArtifactsMMO client library.

## Step 1: Set Up Your API Token

### Get Your Token

1. Go to [ArtifactsMMO](https://artifactsmmo.com/)
2. Create an account if you don't have one
3. Create at least one character
4. Navigate to your account settings
5. Generate an API token

### Set Environment Variable

**Windows (PowerShell):**
```powershell
$env:ARTIFACTS_API_TOKEN="your-actual-token-here"
```

**Windows (Command Prompt):**
```cmd
set ARTIFACTS_API_TOKEN=your-actual-token-here
```

**Linux/Mac (Bash):**
```bash
export ARTIFACTS_API_TOKEN="your-actual-token-here"
```

**Permanent Setup (Optional):**

Add to your shell profile (`~/.bashrc`, `~/.zshrc`, or PowerShell profile):
```bash
export ARTIFACTS_API_TOKEN="your-actual-token-here"
```

## Step 2: Run the Example

```bash
./gradlew :app:run
```

This will:
- Connect to the ArtifactsMMO API
- Display your character information
- Show current location and stats
- Display inventory and skills

## Step 3: Try the Client in Code

Create a new file in `app/src/main/kotlin/`:

```kotlin
package com.nguyen_anthony.app

import com.artifactsmmo.client.ArtifactsMMOClient
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val token = System.getenv("ARTIFACTS_API_TOKEN") ?: error("Set ARTIFACTS_API_TOKEN")
    
    ArtifactsMMOClient.withToken(token).use { client ->
        // Get your characters
        val characters = client.characters.getMyCharacters()
        println("You have ${characters.size} character(s)")
        
        // Use the first character
        val char = characters.first()
        println("Using ${char.name}")
        
        // Get current location info
        val map = client.content.getMapByPosition(char.layer, char.x, char.y)
        println("At: ${map.name}")
        
        // Check what's here
        map.interactions.content?.let { content ->
            println("Content: ${content.type} - ${content.code}")
        }
    }
}
```

## Step 4: Simple Bot Examples

### Example 1: Auto-Gatherer

```kotlin
import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.utils.CooldownUtils
import com.artifactsmmo.client.utils.InventoryUtils
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val token = System.getenv("ARTIFACTS_API_TOKEN") ?: error("Set token")
    
    ArtifactsMMOClient.withToken(token).use { client ->
        val charName = "YourCharacterName"
        
        println("Starting auto-gatherer for $charName")
        
        repeat(10) { iteration ->
            println("\n=== Iteration ${iteration + 1} ===")
            
            // Get fresh character data
            val char = client.characters.getCharacter(charName)
            
            // Wait if in cooldown
            CooldownUtils.waitForCharacterCooldown(char)
            
            // Check inventory space
            if (!InventoryUtils.hasSpace(char)) {
                println("Inventory full! Stopping.")
                return@use
            }
            
            // Gather
            println("Gathering...")
            val result = client.actions.gather(charName)
            
            println("XP gained: ${result.details.xp}")
            println("Items received:")
            result.details.items.forEach { item ->
                println("  - ${item.code} x${item.quantity}")
            }
        }
        
        println("\nDone!")
    }
}
```

### Example 2: Auto-Fighter

```kotlin
import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.utils.CooldownUtils
import com.artifactsmmo.client.utils.CharacterUtils
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val token = System.getenv("ARTIFACTS_API_TOKEN") ?: error("Set token")
    
    ArtifactsMMOClient.withToken(token).use { client ->
        val charName = "YourCharacterName"
        
        println("Starting auto-fighter for $charName")
        
        repeat(10) { iteration ->
            println("\n=== Fight ${iteration + 1} ===")
            
            val char = client.characters.getCharacter(charName)
            CooldownUtils.waitForCharacterCooldown(char)
            
            // Check HP - rest if below 50%
            if (!CharacterUtils.hasEnoughHP(char, 0.5)) {
                println("Low HP! Resting...")
                client.actions.rest(charName)
                continue
            }
            
            println("HP: ${char.hp}/${char.maxHp}")
            println("Fighting...")
            
            val result = client.actions.fight(charName)
            val myResult = result.fight.characters.first()
            
            println("Result: ${result.fight.result}")
            println("XP gained: ${myResult.xp}")
            println("Gold gained: ${myResult.gold}")
            if (myResult.drops.isNotEmpty()) {
                println("Drops:")
                myResult.drops.forEach { drop ->
                    println("  - ${drop.code} x${drop.quantity}")
                }
            }
        }
        
        println("\nDone!")
    }
}
```

### Example 3: Resource Farmer with Banking

```kotlin
import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.client.utils.CooldownUtils
import com.artifactsmmo.client.utils.InventoryUtils
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val token = System.getenv("ARTIFACTS_API_TOKEN") ?: error("Set token")
    
    ArtifactsMMOClient.withToken(token).use { client ->
        val charName = "YourCharacterName"
        
        // Resource location coordinates
        val resourceX = 2
        val resourceY = 0
        
        // Bank location coordinates
        val bankX = 4
        val bankY = 1
        
        println("Starting resource farmer for $charName")
        
        for (cycle in 1..5) {
            println("\n=== Cycle $cycle ===")
            
            // Go to resource location
            println("Moving to resource...")
            var char = client.characters.getCharacter(charName)
            CooldownUtils.waitForCharacterCooldown(char)
            client.actions.move(charName, x = resourceX, y = resourceY)
            
            // Gather until inventory is full
            while (true) {
                char = client.characters.getCharacter(charName)
                CooldownUtils.waitForCharacterCooldown(char)
                
                if (!InventoryUtils.hasSpace(char)) {
                    println("Inventory full!")
                    break
                }
                
                println("Gathering...")
                client.actions.gather(charName)
            }
            
            // Go to bank
            println("Moving to bank...")
            char = client.characters.getCharacter(charName)
            CooldownUtils.waitForCharacterCooldown(char)
            client.actions.move(charName, x = bankX, y = bankY)
            
            // Deposit everything
            char = client.characters.getCharacter(charName)
            CooldownUtils.waitForCharacterCooldown(char)
            
            val itemsToDeposit = char.inventory.map { 
                SimpleItem(it.code, it.quantity) 
            }
            
            if (itemsToDeposit.isNotEmpty()) {
                println("Depositing ${itemsToDeposit.size} item types...")
                client.bank.depositItems(charName, itemsToDeposit)
            }
        }
        
        println("\nFarming complete!")
    }
}
```

## Step 5: Understanding the API Structure

The client is organized into services:

```
client.characters    → Character management
client.actions       → Character actions (move, fight, gather, etc.)
client.bank          → Banking operations
client.grandExchange → Trading
client.content       → Query items, monsters, maps, etc.
client.npc           → NPC trading
client.tasks         → Task system
```

## Step 6: Common Patterns

### Always Wait for Cooldown
```kotlin
val char = client.characters.getCharacter(charName)
CooldownUtils.waitForCharacterCooldown(char)
// Now safe to perform action
```

### Check Inventory Before Actions
```kotlin
if (InventoryUtils.hasSpace(char, requiredSlots = 1)) {
    client.actions.gather(charName)
}
```

### Monitor HP in Combat
```kotlin
if (!CharacterUtils.hasEnoughHP(char, minPercentage = 0.5)) {
    client.actions.rest(charName)
}
```

### Refresh Character Data
```kotlin
// Character data becomes stale after actions
val char = client.characters.getCharacter(charName)
// Use fresh data
```

## Step 7: Explore the API

Check out:
- **[Quick Reference](QUICK_REFERENCE.md)** - API methods cheat sheet
- **[Client README](client/README.md)** - Full documentation
- **[Examples](client/src/main/kotlin/com/artifactsmmo/examples/ClientExample.kt)** - More examples

## Common Issues

### "Character in cooldown" (Error 499)
Wait for the cooldown to expire:
```kotlin
CooldownUtils.waitForCharacterCooldown(character)
```

### "Inventory full" (Error 497)
Check space before gathering:
```kotlin
if (!InventoryUtils.hasSpace(character)) {
    // Go to bank first
}
```

### "Action in progress" (Error 486)
Only one action at a time per character. Wait for the current action to complete.

### Token Issues
Make sure your `ARTIFACTS_API_TOKEN` environment variable is set correctly and the token is valid.

## Next Steps

1. Experiment with the examples above
2. Check what's on your character's current map
3. Plan a bot strategy (farming, trading, leveling, etc.)
4. Build your bot step by step
5. Test thoroughly with small iterations first

Happy botting! 🎮

