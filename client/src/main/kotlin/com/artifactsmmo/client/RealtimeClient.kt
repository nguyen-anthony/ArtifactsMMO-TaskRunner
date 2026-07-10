package com.artifactsmmo.client

import com.artifactsmmo.client.models.AccountLogEntry
import com.artifactsmmo.client.models.ActiveEvent
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.isActive

sealed class RealtimeMessage {
    @OptIn(kotlin.time.ExperimentalTime::class)
    data class EventSpawn(val event: ActiveEvent) : RealtimeMessage()
    @OptIn(kotlin.time.ExperimentalTime::class)
    data class EventRemoved(val event: ActiveEvent) : RealtimeMessage()
    @OptIn(kotlin.time.ExperimentalTime::class)
    data class AccountLog(val entry: AccountLogEntry) : RealtimeMessage()
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
class RealtimeClient(private val token: String) {

    private val realtimeUrl = "wss://realtime.artifactsmmo.com"

    private var wsClient: HttpClient? = null

    /**
     * Open a WebSocket connection and call [onMessage] with the raw JSON text
     * of every received frame. Suspends until closed or an exception is thrown.
     */
    suspend fun connect(onMessage: (String) -> Unit) {
        val client = HttpClient(CIO) {
            install(WebSockets)
        }
        wsClient = client
        try {
            client.webSocket(realtimeUrl) {
                // Send auth frame — no subscription filter, receive all notifications
                send("""{"token":"$token"}""")

                for (frame in incoming) {
                    if (!isActive) break
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
