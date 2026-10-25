package com.artifactsmmo.client

import com.artifactsmmo.client.models.AccountLogEntry
import com.artifactsmmo.client.models.ActiveEvent
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** No frame arrived for [idle]; the connection is presumed dead. */
class RealtimeIdleException(val idle: Duration) : Exception("no realtime frames for $idle")

sealed class RealtimeMessage {
    @OptIn(kotlin.time.ExperimentalTime::class)
    data class EventSpawn(val event: ActiveEvent) : RealtimeMessage()
    @OptIn(kotlin.time.ExperimentalTime::class)
    data class EventRemoved(val event: ActiveEvent) : RealtimeMessage()
    @OptIn(kotlin.time.ExperimentalTime::class)
    data class AccountLog(val entry: AccountLogEntry) : RealtimeMessage()
    /** Minimal raid lifecycle notification. Only the raid code is required for scheduling. */
    data class RaidStarted(val raidCode: String) : RealtimeMessage()
    /** Minimal raid lifecycle notification. Only the raid code is required for cleanup. */
    data class RaidEnded(val raidCode: String) : RealtimeMessage()
    data object Unknown : RealtimeMessage()
}

/**
 * Thin Ktor WebSocket wrapper for the ArtifactsMMO realtime endpoint.
 *
 * Connects to wss://realtime.artifactsmmo.com, sends an auth frame on connect,
 * then delivers every raw JSON text frame to [onMessage].
 *
 * All parsing is handled by the caller ([WebSocketManager]) so it can both
 * log the raw frame and extract typed messages from a single pass.
 */
class RealtimeClient(
    private val token: String,
    private val realtimeUrl: String = PRODUCTION_URL,
    /** Pings detect a dead peer: no pong in time closes the session. */
    private val pingInterval: Duration = 30.seconds,
    /**
     * A half-open socket can stay "connected" forever without delivering frames. With
     * characters running, account_log frames arrive constantly, so this long a silence
     * means the stream is dead: drop it so the caller reconnects (and replays events).
     */
    private val idleTimeout: Duration = 5.minutes,
) {
    companion object {
        const val PRODUCTION_URL = "wss://realtime.artifactsmmo.com"
        const val SANDBOX_URL = "wss://realtime.sandbox.artifactsmmo.com"
    }

    private var wsClient: HttpClient? = null

    /**
     * Open a WebSocket connection and call [onMessage] with the raw JSON text
     * of every received frame. Suspends until closed or an exception is thrown.
     */
    suspend fun connect(onMessage: (String) -> Unit) {
        val client = HttpClient(CIO) {
            install(WebSockets) {
                pingInterval = this@RealtimeClient.pingInterval
            }
        }
        wsClient = client
        try {
            client.webSocket(realtimeUrl) {
                // Send auth frame — no subscription filter, receive all notifications
                send("""{"token":"$token"}""")

                while (isActive) {
                    val result = withTimeoutOrNull(idleTimeout) { incoming.receiveCatching() }
                        ?: throw RealtimeIdleException(idleTimeout)
                    val frame = result.getOrNull() ?: break
                    if (frame !is Frame.Text) continue
                    onMessage(frame.readText())
                }
            }
        } finally {
            client.close()
            wsClient = null
        }
    }

    fun disconnect() {
        wsClient?.close()
        wsClient = null
    }
}
