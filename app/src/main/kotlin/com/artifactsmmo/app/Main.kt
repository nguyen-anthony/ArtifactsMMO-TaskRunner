package com.artifactsmmo.app

import com.artifactsmmo.app.task.TaskManager
import com.artifactsmmo.app.ui.TerminalUI
import com.artifactsmmo.client.ArtifactsMMOClient
import io.github.cdimascio.dotenv.dotenv
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("=== ArtifactsMMO Task Runner ===")
    println()

    // Load .env file (looks in project root and working directory)
    val dotenv = dotenv {
        ignoreIfMissing = true
    }

    // Get API token from .env first, then fall back to system env
    val apiToken = dotenv["ARTIFACTS_API_TOKEN"] ?: System.getenv("ARTIFACTS_API_TOKEN")
    if (apiToken.isNullOrBlank()) {
        System.err.println("Error: ARTIFACTS_API_TOKEN not set")
        System.err.println()
        System.err.println("To use this bot, either:")
        System.err.println("  1. Create a .env file in the project root with:")
        System.err.println("     ARTIFACTS_API_TOKEN=your-token-here")
        System.err.println()
        System.err.println("  2. Or set the environment variable:")
        System.err.println("     Windows: \$env:ARTIFACTS_API_TOKEN=\"your-token-here\"")
        System.err.println("     Linux/Mac: export ARTIFACTS_API_TOKEN=\"your-token-here\"")
        return@runBlocking
    }

    ArtifactsMMOClient.withToken(apiToken).use { client ->
        println("Connected to ArtifactsMMO API")

        // Initialize task manager
        val taskManager = TaskManager(client, this)

        try {
            val characterNames = taskManager.initialize()
            println("Loaded ${characterNames.size} character(s): ${characterNames.joinToString(", ")}")
            println()

            // Run the terminal UI
            val ui = TerminalUI(taskManager, this)
            ui.run()

        } catch (e: Exception) {
            System.err.println("Fatal error: ${e.message}")
            e.printStackTrace()
        } finally {
            taskManager.stopAll()
        }
    }
}
