package com.artifactsmmo.client.gateway

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers when each character's cooldown ends, learned passively from every API response
 * that contains a character object (actions, GET /characters/{name}, GET /my/characters).
 *
 * The transport calls [awaitReady] before sending an action, so characters never trigger 499.
 */
class CooldownTracker(private val clock: Clock = SystemClock) {
    private val expirations = ConcurrentHashMap<String, Long>()

    fun update(character: String, expirationMillis: Long) {
        expirations.merge(character, expirationMillis) { _, new -> new }
    }

    fun expirationMillis(character: String): Long? = expirations[character]

    fun remainingMillis(character: String): Long =
        ((expirations[character] ?: 0L) - clock.nowMillis()).coerceAtLeast(0)

    suspend fun awaitReady(character: String) {
        val wait = remainingMillis(character)
        if (wait > 0) delay(wait)
    }

    /**
     * Scans a response's `data` field. Handles: data is a character, data is a list of
     * characters, data.character, and data.characters[] (group fights: every participant).
     */
    fun observe(data: JsonElement?) {
        when (data) {
            is JsonArray -> data.forEach { observeCharacter(it) }
            is JsonObject -> {
                observeCharacter(data)
                observeCharacter(data["character"])
                (data["characters"] as? JsonArray)?.forEach { observeCharacter(it) }
            }
            else -> Unit
        }
    }

    private fun observeCharacter(el: JsonElement?) {
        val obj = el as? JsonObject ?: return
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return
        val exp = (obj["cooldown_expiration"] as? JsonPrimitive)?.contentOrNull ?: return
        runCatching { Instant.parse(exp).toEpochMilli() }.onSuccess { update(name, it) }
    }
}
