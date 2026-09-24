package com.artifactsmmo.client

import com.artifactsmmo.client.gateway.CooldownTracker
import com.artifactsmmo.client.gateway.RateLimiter
import com.artifactsmmo.client.services.*
import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine

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
    connectTimeoutMillis: Long = 10_000,
    /** Share one limiter per process: ArtifactsMMO rate limits are per IP. */
    val rateLimiter: RateLimiter = RateLimiter(),
    /** Per-character cooldown expirations learned from responses. */
    val cooldowns: CooldownTracker = CooldownTracker(),
    retry: RetryConfig = RetryConfig(),
    /** Override the HTTP engine (tests use Ktor's MockEngine). */
    engine: HttpClientEngine? = null,
) : AutoCloseable {

    private val config = ArtifactsClientConfig(
        baseUrl = baseUrl,
        token = token,
        enableLogging = enableLogging,
        requestTimeoutMillis = requestTimeoutMillis,
        connectTimeoutMillis = connectTimeoutMillis
    )

    private val httpClient: HttpClient = HttpClientFactory(config, engine).create()

    /** The gateway every service call goes through (rate limits, retries, cooldowns). */
    val transport = ApiTransport(httpClient, rateLimiter, cooldowns, retry)

    /**
     * Character management service
     */
    val characters = CharacterService(transport)

    /**
     * Character actions service (move, fight, gather, craft, etc.)
     */
    val actions = ActionService(transport)

    /**
     * Bank operations service
     */
    val bank = BankService(transport)

    /**
     * Grand Exchange trading service
     */
    val grandExchange = GrandExchangeService(transport)

    /**
     * Game content queries (items, monsters, resources, maps)
     */
    val content = ContentService(transport)

    /**
     * NPC trading service
     */
    val npc = NPCService(transport)

    /**
     * Task operations service
     */
    val tasks = TaskService(transport)

    /**
     * Combat simulation service (requires Member or Founder account)
     */
    val simulation = SimulationService(transport)

    /**
     * Account details service
     */
    val account = AccountService(transport)

    /**
     * Game events service
     */
    val events = EventService(transport)

    /** Scheduled raid definitions and active raid state. */
    val raids = RaidService(transport)

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
