package com.artifactsmmo.client

import io.ktor.client.*
import io.ktor.client.call.*
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
import kotlinx.serialization.json.Json

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

/**
 * Base HTTP client for the ArtifactsMMO API
 */
internal class HttpClientFactory(private val config: ArtifactsClientConfig) {

    fun create(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                    prettyPrint = false
                })
            }

            install(HttpTimeout) {
                requestTimeoutMillis = config.requestTimeoutMillis
                connectTimeoutMillis = config.connectTimeoutMillis
            }

            config.token?.let { token ->
                install(Auth) {
                    bearer {
                        loadTokens {
                            BearerTokens(token, "")
                        }
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
    }
}

/**
 * Exception thrown when an API error occurs
 */
class ArtifactsApiException(
    val errorCode: Int,
    override val message: String,
    val errorData: Map<String, List<String>>? = null,
    cause: Throwable? = null
) : Exception("API Error $errorCode: $message", cause)

/**
 * Base class for API services
 */
abstract class BaseApiService(protected val client: HttpClient) {

    protected suspend inline fun <reified T> get(
        path: String,
        block: HttpRequestBuilder.() -> Unit = {}
    ): T {
        return request {
            method = HttpMethod.Get
            url(path)
            block()
        }
    }

    protected suspend inline fun <reified T> post(
        path: String,
        body: Any? = null,
        block: HttpRequestBuilder.() -> Unit = {}
    ): T {
        return request {
            method = HttpMethod.Post
            url(path)
            body?.let { setBody(it) }
            block()
        }
    }

    protected suspend inline fun <reified T> request(
        block: HttpRequestBuilder.() -> Unit
    ): T {
        try {
            val response: HttpResponse = client.request(block)

            if (response.status.isSuccess()) {
                return response.body()
            } else {
                val errorBody = response.bodyAsText()
                // Try to extract the error message from the JSON response
                val errorMessage = try {
                    // Look for "message" field in JSON
                    val messagePattern = """"message"\s*:\s*"([^"]*)"""".toRegex()
                    messagePattern.find(errorBody)?.groupValues?.get(1) ?: errorBody
                } catch (e: Exception) {
                    errorBody
                }

                throw ArtifactsApiException(
                    errorCode = response.status.value,
                    message = "HTTP ${response.status.value}: $errorMessage"
                )
            }
        } catch (e: ArtifactsApiException) {
            throw e
        } catch (e: Exception) {
            throw ArtifactsApiException(
                errorCode = -1,
                message = "Request failed: ${e.message}",
                cause = e
            )
        }
    }
}

