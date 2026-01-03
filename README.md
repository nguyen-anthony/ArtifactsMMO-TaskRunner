# ArtifactsMMO Bot Project

A Kotlin-based bot/automation framework for the [ArtifactsMMO](https://artifactsmmo.com/) game, featuring a complete type-safe API client library.

## 📦 Project Structure

This is a multi-module Gradle project with the following modules:

- **`client`** - Complete ArtifactsMMO API client library
- **`app`** - Your bot application (uses the client)
- **`utils`** - Shared utility functions

## 🚀 Quick Start

### 1. Get Your API Token

1. Visit [ArtifactsMMO](https://artifactsmmo.com/)
2. Create an account and character
3. Generate your API token from the website

### 2. Set Environment Variable

**Windows (PowerShell):**
```powershell
$env:ARTIFACTS_API_TOKEN="your-token-here"
```

**Linux/Mac:**
```bash
export ARTIFACTS_API_TOKEN="your-token-here"
```

### 3. Run the Example Bot

```bash
./gradlew :app:run
```

## 🎮 Using the Client Library

The `client` module provides a complete, type-safe Kotlin client for the ArtifactsMMO API.

### Basic Usage

```kotlin
import com.artifactsmmo.client.ArtifactsMMOClient

suspend fun main() {
    ArtifactsMMOClient.withToken("your-token").use { client ->
        // Get your characters
        val characters = client.characters.getMyCharacters()
        
        // Move a character
        client.actions.move("MyCharacter", x = 5, y = 10)
        
        // Fight a monster
        client.actions.fight("MyCharacter")
        
        // Gather resources
        client.actions.gather("MyCharacter")
        
        // Query game content
        val items = client.content.getItems(type = "weapon")
        val monsters = client.content.getMonsters(minLevel = 1, maxLevel = 10)
    }
}
```

### Client Services

The client is organized into logical service groups:

- **`client.characters`** - Character management (create, delete, get)
- **`client.actions`** - Character actions (move, fight, gather, craft, rest, equip, etc.)
- **`client.bank`** - Bank operations (deposit/withdraw gold and items)
- **`client.grandExchange`** - Trading (buy, sell, cancel orders)
- **`client.content`** - Game content queries (items, monsters, resources, maps, NPCs)
- **`client.npc`** - NPC trading
- **`client.tasks`** - Task operations

### Utility Functions

The library includes helpful utilities:

```kotlin
import com.artifactsmmo.client.utils.*

// Cooldown management
if (CooldownUtils.isInCooldown(character)) {
    CooldownUtils.waitForCharacterCooldown(character)
}

// Inventory checks
if (InventoryUtils.hasSpace(character, requiredSlots = 5)) {
    client.actions.gather(character.name)
}

// HP checks
if (!CharacterUtils.hasEnoughHP(character, minPercentage = 0.5)) {
    client.actions.rest(character.name)
}
```

## 📚 Documentation

- **[Client Library README](client/README.md)** - Complete API documentation
- **[Client Summary](CLIENT_SUMMARY.md)** - Implementation details and examples
- **[Example Code](client/src/main/kotlin/com/artifactsmmo/examples/ClientExample.kt)** - Comprehensive usage examples

## 🛠️ Gradle Commands

This project uses [Gradle](https://gradle.org/).

### Running

* `./gradlew :app:run` - Build and run the bot application
* `./gradlew run` - Same as above (default)

### Building

* `./gradlew build` - Build all modules
* `./gradlew :client:build` - Build only the client library
* `./gradlew check` - Run all checks, including tests
* `./gradlew clean` - Clean all build outputs

Note: Use the Gradle Wrapper (`./gradlew`) - this is the suggested way to use Gradle.

[Learn more about the Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html).

## 🏗️ Creating Your Bot

1. Edit `app/src/main/kotlin/BotExample.kt` or create new files
2. Use the client library to interact with the API
3. Implement your bot logic (auto-fighting, auto-gathering, trading, etc.)

Example bot loop:

```kotlin
suspend fun botLoop(client: ArtifactsMMOClient, characterName: String) {
    while (true) {
        val char = client.characters.getCharacter(characterName)
        
        // Wait for cooldown
        CooldownUtils.waitForCharacterCooldown(char)
        
        // Check HP and rest if needed
        if (!CharacterUtils.hasEnoughHP(char, 0.7)) {
            client.actions.rest(char.name)
            continue
        }
        
        // Perform your bot action
        client.actions.gather(char.name)
        
        delay(1000) // Rate limiting
    }
}
```

## 📋 Features

✅ Complete ArtifactsMMO API coverage  
✅ Type-safe Kotlin models  
✅ Coroutine-based async operations  
✅ Built-in authentication  
✅ Utility functions for common tasks  
✅ Comprehensive error handling  
✅ Resource management with auto-close  
✅ Organized service interfaces  

## 🔧 Technology Stack

- **Kotlin** - Programming language
- **Ktor Client** - HTTP client with coroutine support
- **Kotlinx Serialization** - JSON serialization
- **Kotlinx Coroutines** - Async programming
- **Kotlinx DateTime** - Date/time handling
- **Gradle** - Build system

## 📝 Project Configuration

This project uses:
- Version catalog (`gradle/libs.versions.toml`) for dependency management
- Build cache and configuration cache (`gradle.properties`)
- Convention plugins (`buildSrc`) for shared build logic

This project follows the suggested multi-module setup and consists of the `app`, `client`, and `utils` subprojects.
The shared build logic was extracted to a convention plugin located in `buildSrc`.

This project uses a version catalog (see `gradle/libs.versions.toml`) to declare and version dependencies
and both a build cache and a configuration cache (see `gradle.properties`).