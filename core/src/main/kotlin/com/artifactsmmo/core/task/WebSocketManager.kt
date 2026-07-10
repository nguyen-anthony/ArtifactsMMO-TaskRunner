package com.artifactsmmo.core.task

import com.artifactsmmo.client.RealtimeClient
import com.artifactsmmo.client.RealtimeMessage
import com.artifactsmmo.client.services.EventService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages the WebSocket connection to the ArtifactsMMO realtime endpoint.
 *
 * Reconnect loop:
 *  1. Call [RealtimeClient.connect], emitting each received message to [messages].
 *  2. On disconnect or exception: delay 10 s, retry.
 *  3. On each (re)connect: fetch active events via [EventService.getActiveEvents] and
 *     emit a synthetic [RealtimeMessage.EventSpawn] for each — recovering missed events.
 *
 * Every raw frame is also pretty-printed into [rawLog] / ring-buffer for the WebSocket
 * log panel in the GUI.
 */
@OptIn(ExperimentalTime::class)
class WebSocketManager(
    private val realtimeClient: RealtimeClient,
    private val eventService: EventService,
    private val scope: CoroutineScope,
    private val bankState: BankState? = null
) {
    // ── Typed message flow ────────────────────────────────────────────────────

    private val _messages = MutableSharedFlow<RealtimeMessage>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<RealtimeMessage> = _messages

    // ── Raw log ───────────────────────────────────────────────────────────────

    /** A single pretty-printed WebSocket notification entry. */
    data class WebSocketLogEntry(
        val timestamp: Instant,
        val type: String,
        val summary: String,
        val rawJson: String
    )

    private val _rawLog = MutableSharedFlow<WebSocketLogEntry>(replay = 0, extraBufferCapacity = 256)
    val rawLog: SharedFlow<WebSocketLogEntry> = _rawLog

    /** Ring-buffer holding the last 500 entries. Thread-safe via CopyOnWriteArrayList. */
    private val ringBuffer = CopyOnWriteArrayList<WebSocketLogEntry>()
    private val RING_BUFFER_MAX = 500

    fun getRecentLogs(limit: Int = 500): List<WebSocketLogEntry> {
        val buf = ringBuffer
        return if (buf.size <= limit) buf.toList() else buf.subList(buf.size - limit, buf.size)
    }

    // ── JSON parser (shared, ignores unknown keys) ────────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    // ── Connect job ───────────────────────────────────────────────────────────

    private var connectJob: Job? = null

    fun start() {
        connectJob?.cancel()
        connectJob = scope.launch {
            while (isActive) {
                try {
                    replayActiveEvents()
                    realtimeClient.connect { rawText ->
                        val message = parseAndLog(rawText)
                        _messages.tryEmit(message)
                    }                } catch (_: Exception) {
                    // Ignore — reconnect below
                }
                if (isActive) delay(10.seconds)
            }
        }
    }

    fun stop() {
        connectJob?.cancel()
        connectJob = null
        realtimeClient.disconnect()
    }

    // ── Active-event replay on (re)connect ────────────────────────────────────

    private suspend fun replayActiveEvents() {
        try {
            var page = 1
            while (true) {
                val result = eventService.getActiveEvents(page = page, size = 100)
                for (event in result.data) {
                    _messages.tryEmit(RealtimeMessage.EventSpawn(event))
                }
                if (page >= (result.pages ?: Int.MAX_VALUE) || result.data.size < 100) break
                page++
            }
        } catch (_: Exception) { }
    }

    // ── Frame parsing + logging ───────────────────────────────────────────────

    /**
     * Parse a raw JSON text frame into a [RealtimeMessage] AND emit a [WebSocketLogEntry].
     * Never throws — all parse errors fall back to [RealtimeMessage.Unknown].
     */
    private fun parseAndLog(rawText: String): RealtimeMessage {
        // Extract type field for logging regardless of full parse success
        val typeField = extractTypeField(rawText) ?: "unknown"
        val dataObj = extractDataObject(rawText)

        val summary = buildSummary(typeField, dataObj, rawText)
        val entry = WebSocketLogEntry(
            timestamp = Clock.System.now(),
            type = typeField,
            summary = summary,
            rawJson = rawText
        )
        emitLogEntry(entry)

        // Parse into typed RealtimeMessage
        // For known event/merchant types, always log the full raw payload so we can
        // diagnose schema mismatches without needing to reproduce the event.
        val knownEventCodes = setOf(
            "magic_apparition", "strange_apparition",
            "fish_merchant", "gemstone_merchant", "herbal_merchant",
            "nomadic_merchant", "timber_merchant"
        )
        val dataCode = dataObj?.get("code")?.jsonPrimitive?.content
        if (typeField == "event_spawn" && dataCode in knownEventCodes) {
            emitLogEntry(WebSocketLogEntry(
                timestamp = Clock.System.now(),
                type = typeField,
                summary = "[raw payload for $dataCode]: $rawText",
                rawJson = rawText
            ))
        }

        return try {
            when (typeField) {
                "event_spawn" -> {
                    val event = json.decodeFromString(
                        com.artifactsmmo.client.models.ActiveEvent.serializer(),
                        dataObj?.toString() ?: "{}"
                    )
                    RealtimeMessage.EventSpawn(event)
                }
                "event_removed" -> {
                    val event = json.decodeFromString(
                        com.artifactsmmo.client.models.ActiveEvent.serializer(),
                        dataObj?.toString() ?: "{}"
                    )
                    RealtimeMessage.EventRemoved(event)
                }
                "account_log" -> {
                    val entry = json.decodeFromString(
                        com.artifactsmmo.client.models.AccountLogEntry.serializer(),
                        dataObj?.toString() ?: "{}"
                    )
                    bankState?.applyLogEntry(entry)
                    RealtimeMessage.AccountLog(entry)
                }
                else -> RealtimeMessage.Unknown
            }
        } catch (e: Exception) {
            // Log parse failures so they appear in the WebSocket log panel
            val errorEntry = WebSocketLogEntry(
                timestamp = Clock.System.now(),
                type = typeField,
                summary = "[parse error for '$typeField': ${e.message?.take(120)}]",
                rawJson = rawText
            )
            emitLogEntry(errorEntry)
            RealtimeMessage.Unknown
        }
    }

    private fun extractTypeField(rawText: String): String? {
        return try {
            json.parseToJsonElement(rawText).jsonObject["type"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }
    }

    private fun extractDataObject(rawText: String): JsonObject? {
        return try {
            json.parseToJsonElement(rawText).jsonObject["data"]?.jsonObject
        } catch (_: Exception) { null }
    }

    private fun buildSummary(type: String, data: JsonObject?, rawText: String): String {
        return try {
            when (type) {
                "event_spawn" -> {
                    val name = data?.get("name")?.jsonPrimitive?.content ?: "?"
                    val map = data?.get("map")?.jsonObject
                    val x = map?.get("x")?.jsonPrimitive?.intOrNull ?: 0
                    val y = map?.get("y")?.jsonPrimitive?.intOrNull ?: 0
                    "Event spawned: $name at ($x, $y)"
                }
                "event_removed" -> {
                    val name = data?.get("name")?.jsonPrimitive?.content ?: "?"
                    "Event ended: $name"
                }
                "account_log" -> {
                    val char = data?.get("character")?.jsonPrimitive?.content ?: "?"
                    val logType = data?.get("type")?.jsonPrimitive?.content ?: "?"
                    val desc = data?.get("description")?.jsonPrimitive?.content ?: ""
                    "$char — $logType: $desc"
                }
                "online_characters" -> {
                    val count = data?.get("characters")?.jsonArray?.size
                        ?: data?.get("count")?.jsonPrimitive?.intOrNull
                        ?: 0
                    "Online characters updated ($count active)"
                }
                "version" -> {
                    val v = data?.get("version")?.jsonPrimitive?.content ?: "?"
                    "Server version: $v"
                }
                "announcement" -> {
                    val msg = data?.get("message")?.jsonPrimitive?.content ?: "?"
                    "Announcement: $msg"
                }
                "achievement_unlocked" -> {
                    val name = data?.get("name")?.jsonPrimitive?.content ?: "?"
                    "Achievement unlocked: $name"
                }
                "raid_started" -> {
                    val name = data?.get("raid")?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "?"
                    "Raid started: $name"
                }
                "raid_ended" -> {
                    val name = data?.get("raid")?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "?"
                    val result = data?.get("result")?.jsonPrimitive?.content ?: "?"
                    "Raid ended: $name — $result"
                }
                "season_reward_unlocked" -> {
                    val name = data?.get("name")?.jsonPrimitive?.content ?: "?"
                    "Season reward: $name"
                }
                "test" -> "WebSocket test ping"
                else -> if (type.startsWith("grandexchange")) {
                    val code = data?.get("code")?.jsonPrimitive?.content ?: "?"
                    val qty = data?.get("quantity")?.jsonPrimitive?.intOrNull ?: 0
                    "GE: $type — $code x$qty"
                } else {
                    "[$type]: ${rawText.take(120)}"
                }
            }
        } catch (_: Exception) {
            "[$type]: ${rawText.take(120)}"
        }
    }

    private fun emitLogEntry(entry: WebSocketLogEntry) {
        _rawLog.tryEmit(entry)
        ringBuffer.add(entry)
        // Trim ring buffer to max size
        while (ringBuffer.size > RING_BUFFER_MAX) {
            ringBuffer.removeAt(0)
        }
    }
}
