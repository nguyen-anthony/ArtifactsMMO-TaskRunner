package com.artifactsmmo.client

import com.artifactsmmo.client.services.*
import io.ktor.client.*
import kotlinx.coroutines.runBlocking

/**
 * Main client for interacting with the ArtifactsMMO API
 *
 * Example usage:
 * ```kotlin
 * val client = ArtifactsMMOClient(token = "your-api-token")
 *
 * // Get your characters
 * val characters = client.characters.getMyCharacters()
 *
 * // Move a character
 * val result = client.actions.move("MyCharacter", x = 5, y = 10)
 *
 * // Get items
 * val items = client.content.getItems(type = "weapon")
 * ```
 */
class ArtifactsMMOClient(
    token: String? = null,
    baseUrl: String = "https://api.artifactsmmo.com",
    enableLogging: Boolean = false,
    requestTimeoutMillis: Long = 30_000,
    connectTimeoutMillis: Long = 10_000
) : AutoCloseable {

    private val config = ArtifactsClientConfig(
        baseUrl = baseUrl,
        token = token,
        enableLogging = enableLogging,
        requestTimeoutMillis = requestTimeoutMillis,
        connectTimeoutMillis = connectTimeoutMillis
    )

    private val httpClient: HttpClient = HttpClientFactory(config).create()

    /**
     * Character management service
     */
    val characters = CharacterService(httpClient)

    /**
     * Character actions service (move, fight, gather, craft, etc.)
     */
    val actions = ActionService(httpClient)

    /**
     * Bank operations service
     */
    val bank = BankService(httpClient)

    /**
     * Grand Exchange trading service
     */
    val grandExchange = GrandExchangeService(httpClient)

    /**
     * Game content queries (items, monsters, resources, maps)
     */
    val content = ContentService(httpClient)

    /**
     * NPC trading service
     */
    val npc = NPCService(httpClient)

    /**
     * Task operations service
     */
    val tasks = TaskService(httpClient)

    /**
     * Close the HTTP client and release resources
     */
    override fun close() {
        httpClient.close()
    }

    companion object {
        /**
         * Create a client with a token
         */
        fun withToken(token: String, enableLogging: Boolean = false): ArtifactsMMOClient {
            return ArtifactsMMOClient(token = token, enableLogging = enableLogging)
        }

        /**
         * Create a client without authentication (for public endpoints only)
         */
        fun anonymous(enableLogging: Boolean = false): ArtifactsMMOClient {
            return ArtifactsMMOClient(token = null, enableLogging = enableLogging)
        }
    }
}

/**
 * Extension function to use the client in a managed scope
 */
inline fun <T> useArtifactsClient(
    token: String,
    enableLogging: Boolean = false,
    block: ArtifactsMMOClient.() -> T
): T {
    return ArtifactsMMOClient.withToken(token, enableLogging).use(block)
}

/**
 * Blocking version of the client for non-coroutine contexts
 */
class BlockingArtifactsMMOClient(
    token: String? = null,
    baseUrl: String = "https://api.artifactsmmo.com",
    enableLogging: Boolean = false
) : AutoCloseable {

    private val client = ArtifactsMMOClient(
        token = token,
        baseUrl = baseUrl,
        enableLogging = enableLogging
    )

    /**
     * Character management service
     */
    val characters = BlockingCharacterService(client.characters)

    /**
     * Character actions service
     */
    val actions = BlockingActionService(client.actions)

    override fun close() {
        client.close()
    }
}

/**
 * Blocking wrapper for CharacterService
 */
class BlockingCharacterService(private val service: CharacterService) {
    fun getCharacter(name: String) = runBlocking { service.getCharacter(name) }
    fun getMyCharacters() = runBlocking { service.getMyCharacters() }
    fun createCharacter(name: String, skin: String) = runBlocking { service.createCharacter(name, skin) }
    fun deleteCharacter(name: String) = runBlocking { service.deleteCharacter(name) }
}

/**
 * Blocking wrapper for ActionService
 */
class BlockingActionService(private val service: ActionService) {
    fun move(characterName: String, x: Int, y: Int) = runBlocking { service.move(characterName, x, y) }
    fun fight(characterName: String, participants: List<String> = emptyList()) = runBlocking { service.fight(characterName, participants) }
    fun gather(characterName: String) = runBlocking { service.gather(characterName) }
    fun craft(characterName: String, itemCode: String, quantity: Int = 1) = runBlocking { service.craft(characterName, itemCode, quantity) }
    fun rest(characterName: String) = runBlocking { service.rest(characterName) }
}

