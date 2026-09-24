package com.artifactsmmo.client

import com.artifactsmmo.client.gateway.Clock
import com.artifactsmmo.client.gateway.CooldownTracker
import com.artifactsmmo.client.gateway.ErrorAction
import com.artifactsmmo.client.gateway.ErrorPolicy
import com.artifactsmmo.client.gateway.RateCategory
import com.artifactsmmo.client.gateway.RateLimiter
import com.artifactsmmo.client.gateway.SystemClock
import io.ktor.client.*
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

/**
 * Configuration for the ArtifactsMMO API client
 */
data class ArtifactsClientConfig(
    val baseUrl: String = "https://api.artifactsmmo.com",
    val token: String? = null,
    val enableLogging: Boolean = false,
    val requestTimeoutMillis: Long = 30_000,
    val connectTimeoutMillis: Long = 10_000
)

/** JSON settings shared by request encoding and response decoding. */
val ArtifactsJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = false
}

/**
 * Base HTTP client for the ArtifactsMMO API
 */
internal class HttpClientFactory(
    private val config: ArtifactsClientConfig,
    private val engine: HttpClientEngine? = null,
) {

    fun create(): HttpClient {
        val configure: HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) { json(ArtifactsJson) }

            install(HttpTimeout) {
                requestTimeoutMillis = config.requestTimeoutMillis
                connectTimeoutMillis = config.connectTimeoutMillis
            }

            config.token?.let { token ->
                install(Auth) {
                    bearer {
                        loadTokens { BearerTokens(token, "") }
                        // Send the token on the first request instead of waiting for a 401.
                        sendWithoutRequest { true }
                    }
                }
            }

            if (config.enableLogging) {
                install(Logging) {
                    logger = Logger.DEFAULT
                    level = LogLevel.INFO
                }
            }

            defaultRequest {
                url(config.baseUrl)
                contentType(ContentType.Application.Json)
            }
        }
        return if (engine != null) HttpClient(engine, configure) else HttpClient(CIO, configure)
    }
}

/**
 * Exception thrown when an API error occurs.
 *
 * [errorCode] is the ArtifactsMMO code (usually equal to the HTTP status), or -1 for
 * network/transport failures. [data] is the optional `error.data` object from the body.
 */
class ArtifactsApiException(
    val errorCode: Int,
    override val message: String,
    val data: JsonObject? = null,
    cause: Throwable? = null
) : Exception("API Error $errorCode: $message", cause) {
    /** What to do about it, per the central [ErrorPolicy] table. */
    val action: ErrorAction get() = ErrorPolicy.classify(errorCode)
}

/** Retry budgets used by [ApiTransport]. */
data class RetryConfig(
    /** 429: the request was rejected before execution, so any method may be retried. */
    val maxRateLimitRetries: Int = 4,
    val rateLimitBackoffMillis: Long = 1_000,
    /** 486 (locked) / 499 (cooldown): the action did not run; wait and resend. */
    val maxBusyRetries: Int = 3,
    /** 5xx / network: only retried for idempotent requests (GET, simulation). */
    val maxTransientRetries: Int = 3,
    val transientBaseBackoffMillis: Long = 500,
)

/**
 * The single choke point for every ArtifactsMMO HTTP call ("gateway").
 *
 * For each request it:
 *  1. waits for the character's known cooldown (action endpoints only),
 *  2. takes a slot from the matching rate-limit bucket,
 *  3. sends the request and parses errors from JSON,
 *  4. retries what is safe to retry (429 always; 486/499 for actions; 5xx/network for idempotent calls),
 *  5. records cooldowns from any character objects in the response.
 *
 * Share one [RateLimiter] across everything that runs from the same IP.
 */
class ApiTransport(
    val http: HttpClient,
    val limiter: RateLimiter = RateLimiter(),
    val cooldowns: CooldownTracker = CooldownTracker(),
    val retry: RetryConfig = RetryConfig(),
    val json: Json = ArtifactsJson,
    private val clock: Clock = SystemClock,
    private val random: Random = Random.Default,
) {
    /** Executes the request and returns the raw successful response body. */
    suspend fun execute(method: HttpMethod, path: String, block: HttpRequestBuilder.() -> Unit): String {
        val category = RateCategory.of(method.value, path)
        val bucket = limiter.bucket(category)
        val actingCharacter = ACTION_PATH.find(path.substringBefore('?'))?.groupValues?.get(1)
        val idempotent = method == HttpMethod.Get || category == RateCategory.SIMULATION

        var rateLimitRetries = 0
        var busyRetries = 0
        var transientRetries = 0
        while (true) {
            actingCharacter?.let { cooldowns.awaitReady(it) }
            bucket.acquire()

            val error: ArtifactsApiException = try {
                val response = http.request {
                    this.method = method
                    url(path)
                    block()
                }
                val text = response.bodyAsText()
                if (response.status.isSuccess()) {
                    observeCooldowns(text)
                    return text
                }
                parseError(response.status.value, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ArtifactsApiException(-1, "Request failed: ${e.message}", cause = e)
            }

            when {
                error.errorCode == 429 && rateLimitRetries < retry.maxRateLimitRetries -> {
                    rateLimitRetries++
                    // Pause the whole bucket so every caller backs off together.
                    bucket.pauseFor(retry.rateLimitBackoffMillis * rateLimitRetries)
                }
                error.errorCode == 499 && actingCharacter != null && busyRetries < retry.maxBusyRetries -> {
                    busyRetries++
                    val seconds = cooldownSecondsFrom(error)
                    cooldowns.update(actingCharacter, clock.nowMillis() + ((seconds ?: 1.0) * 1000).toLong() + 50)
                }
                error.errorCode == 486 && actingCharacter != null && busyRetries < retry.maxBusyRetries -> {
                    busyRetries++
                    delay(1_000L * busyRetries)
                }
                (error.errorCode == -1 || error.errorCode in 500..599) && idempotent &&
                    transientRetries < retry.maxTransientRetries -> {
                    transientRetries++
                    val base = retry.transientBaseBackoffMillis shl (transientRetries - 1)
                    delay(base + random.nextLong(base / 2 + 1)) // exponential + jitter
                }
                else -> throw error
            }
        }
    }

    private fun observeCooldowns(text: String) {
        if (!text.contains("cooldown_expiration")) return
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return
        cooldowns.observe(root["data"])
    }

    /** Parses `{"error":{"code":..,"message":..,"data":{..}}}`; falls back to the raw body. */
    internal fun parseError(status: Int, body: String): ArtifactsApiException {
        val err = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?.get("error") as? JsonObject
        val code = err?.get("code")?.jsonPrimitive?.intOrNull ?: status
        val msg = err?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(500)
        val data = err?.get("data") as? JsonObject
        val detail = if (data != null) "$msg | details: $data" else msg
        return ArtifactsApiException(code, "HTTP $status: $detail", data)
    }

    private fun cooldownSecondsFrom(e: ArtifactsApiException): Double? =
        e.data?.get("remaining_seconds")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            ?: SECONDS.find(e.message)?.groupValues?.get(1)?.toDoubleOrNull()

    companion object {
        private val ACTION_PATH = Regex("^/my/([^/]+)/action/")
        private val SECONDS = Regex("""(\d+(?:\.\d+)?)\s*sec""")
    }
}

/**
 * Base class for API services. All calls go through [ApiTransport].
 */
abstract class BaseApiService(protected val transport: ApiTransport) {

    protected suspend inline fun <reified T> get(
        path: String,
        noinline block: HttpRequestBuilder.() -> Unit = {}
    ): T = transport.json.decodeFromString(transport.execute(HttpMethod.Get, path, block))

    protected suspend inline fun <reified T> post(
        path: String,
        body: Any? = null,
        noinline block: HttpRequestBuilder.() -> Unit = {}
    ): T = transport.json.decodeFromString(
        transport.execute(HttpMethod.Post, path) {
            body?.let { setBody(it) }
            block()
        }
    )
}
