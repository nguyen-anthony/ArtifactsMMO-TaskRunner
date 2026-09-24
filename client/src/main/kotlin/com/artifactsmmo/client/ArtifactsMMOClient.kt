package com.artifactsmmo.client

import com.artifactsmmo.client.services.*
import io.ktor.client.*

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
     * Combat simulation service (requires Member or Founder account)
     */
    val simulation = SimulationService(httpClient)

    /**
     * Account details service
     */
    val account = AccountService(httpClient)

    /**
     * Game events service
     */
    val events = EventService(httpClient)

    /** Scheduled raid definitions and active raid state. */
    val raids = RaidService(httpClient)

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
