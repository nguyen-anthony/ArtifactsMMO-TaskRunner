package com.artifactsmmo.client.services

import com.artifactsmmo.client.ApiTransport
import com.artifactsmmo.client.models.ActiveEvent
import com.artifactsmmo.client.models.ApiResponse
import com.artifactsmmo.client.models.Character
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sandbox-only endpoints (`api.sandbox.artifactsmmo.com`, membership required).
 *
 * Every call except [clearCooldown] puts the character on a ~3s cooldown and counts toward the
 * action rate limit, so calls are gated on the character's cooldown like normal actions.
 * `reset_account` is deliberately not exposed.
 */
class SandboxService(private val transport: ApiTransport, private val enabled: Boolean) {

    @Serializable
    private data class CharacterBody(val character: String)

    @Serializable
    private data class GiveItemBody(val character: String, val code: String, val quantity: Int)

    @Serializable
    private data class GiveXpBody(val character: String, val type: String, val amount: Int)

    @Serializable
    private data class GiveGoldBody(val character: String, val quantity: Int)

    @Serializable
    private data class TeleportBody(val character: String, @SerialName("map_id") val mapId: Int)

    @Serializable
    private data class CodeBody(val code: String)

    @Serializable
    data class CharacterData(val character: Character)

    /** Gives [quantity] of one item code to the character's inventory. */
    suspend fun giveItem(character: String, code: String, quantity: Int): Character =
        post(character, "/sandbox/give_item", GiveItemBody(character, code, quantity))

    /** Gives XP of [type] (`combat` or a skill code). The server caps [amount] at [MAX_XP_PER_CALL]. */
    suspend fun giveXp(character: String, type: String, amount: Int): Character {
        require(amount in 1..MAX_XP_PER_CALL) { "amount must be 1..$MAX_XP_PER_CALL" }
        return post(character, "/sandbox/give_xp", GiveXpBody(character, type, amount))
    }

    suspend fun giveGold(character: String, quantity: Int): Character =
        post(character, "/sandbox/give_gold", GiveGoldBody(character, quantity))

    suspend fun teleport(character: String, mapId: Int): Character =
        post(character, "/sandbox/teleport", TeleportBody(character, mapId))

    /** Clears the character's cooldown. Works while on cooldown, so it bypasses the cooldown gate. */
    suspend fun clearCooldown(character: String): Character {
        val c = call<CharacterData>(null, "/sandbox/clear_cooldown", CharacterBody(character)).character
        transport.cooldowns.update(character, 0)
        return c
    }

    /** Spawns an event. 564 = already active or max active reached; 566 = event on cooldown. */
    suspend fun spawnEvent(code: String): ActiveEvent {
        check(enabled) { NOT_SANDBOX }
        val text = transport.execute(HttpMethod.Post, "/sandbox/spawn_event") {
            contentType(ContentType.Application.Json)
            setBody(CodeBody(code))
        }
        return transport.json.decodeFromString<ApiResponse<ActiveEvent>>(text).data
    }

    private suspend fun post(character: String, path: String, body: Any): Character =
        call<CharacterData>(character, path, body).character

    private suspend inline fun <reified T> call(actor: String?, path: String, body: Any): T {
        check(enabled) { NOT_SANDBOX }
        val text = transport.execute(HttpMethod.Post, path, actor) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return transport.json.decodeFromString<ApiResponse<T>>(text).data
    }

    companion object {
        const val MAX_XP_PER_CALL = 100_000
        private const val NOT_SANDBOX = "Sandbox endpoints are only allowed against the sandbox server"
    }
}
