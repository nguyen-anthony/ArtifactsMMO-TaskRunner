package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.Monster
import com.artifactsmmo.client.models.SimpleEffect
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The solo beam search used to restart from the current gear whenever one slot had no
 * feasible candidate — e.g. artifact2 when the only artifact owned is already in artifact1 —
 * so a clearly better bank weapon was never even simulated.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class GearOptimizerBeamTest {

    @Test
    fun betterBankWeaponIsFoundWhenAnArtifactSlotCannotBeFilled() = runTest {
        val items = listOf(
            item("copper_dagger", "weapon", 1, SimpleEffect("attack_air", 6, ""), SimpleEffect("critical_strike", 35, "")),
            item("sticky_sword", "weapon", 5, SimpleEffect("attack_earth", 16, ""), SimpleEffect("critical_strike", 5, "")),
            item("novice_guide", "artifact", 1, SimpleEffect("wisdom", 35, "")),
        )
        val monster = Monster(
            name = "Blue Slime", code = "blue_slime", level = 6, type = "normal", hp = 120,
            attackFire = 0, attackEarth = 0, attackWater = 8, attackAir = 0,
            resFire = 0, resEarth = 0, resWater = 25, resAir = 0, criticalStrike = 0, initiative = 0,
            effects = emptyList(), minGold = 0, maxGold = 0, drops = emptyList()
        )
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path == "/items" -> {
                    val type = req.url.parameters["type"]
                    val page = items.filter { type != null && it.type == type }
                    respond("""{"data":${Json.encodeToString(ListSerializer(Item.serializer()), page)},"pages":1}""", HttpStatusCode.OK, json)
                }
                path.startsWith("/items/") -> {
                    val item = items.firstOrNull { it.code == path.removePrefix("/items/") }
                    if (item == null) respond("""{"error":{"code":404,"message":"nf"}}""", HttpStatusCode.NotFound, json)
                    else respond("""{"data":${Json.encodeToString(Item.serializer(), item)}}""", HttpStatusCode.OK, json)
                }
                path == "/monsters/blue_slime" ->
                    respond("""{"data":${Json.encodeToString(Monster.serializer(), monster)}}""", HttpStatusCode.OK, json)
                path == "/my/bank/items" ->
                    respond("""{"data":[{"code":"sticky_sword","quantity":1},{"code":"novice_guide","quantity":1}],"pages":1}""", HttpStatusCode.OK, json)
                path == "/simulation/fight" -> {
                    // The sword kills in half the turns of the dagger.
                    val turns = if (String(req.body.toByteArray()).contains("sticky_sword")) 14 else 28
                    val results = List(20) { """{"result":"win","turns":$turns}""" }.joinToString(",")
                    respond("""{"data":{"results":[$results],"wins":20,"losses":0,"winrate":100}}""", HttpStatusCode.OK, json)
                }
                else -> respond("""{"data":[],"pages":1}""", HttpStatusCode.OK, json)
            }
        }
        val client = ArtifactsMMOClient(token = "t", baseUrl = "http://test", engine = engine)
        val bank = BankState(client, this)
        bank.refresh()
        val helper = ActionHelper(client, ContentCache(client.content), bank)
        val store = KnownLoadoutStore(File.createTempFile("gear-beam", ".json").also { it.delete(); it.deleteOnExit() })

        val result = GearOptimizer(helper, store).optimize(character(), "blue_slime")

        assertEquals("sticky_sword", result.targetLoadout["weapon"])
        assertEquals(listOf("weapon" to "sticky_sword"), result.equipActions.map { it.slot to it.itemCode })
    }

    private fun item(code: String, type: String, level: Int, vararg effects: SimpleEffect) = Item(
        name = code, code = code, level = level, type = type, subtype = "", description = "",
        effects = effects.toList(), tradeable = true
    )

    private fun character() = Character(
        name = "Ront", account = "account", skin = "skin", level = 11, xp = 0, maxXp = 100,
        gold = 0, speed = 0,
        miningLevel = 1, miningXp = 0, miningMaxXp = 100,
        woodcuttingLevel = 1, woodcuttingXp = 0, woodcuttingMaxXp = 100,
        fishingLevel = 1, fishingXp = 0, fishingMaxXp = 100,
        weaponcraftingLevel = 1, weaponcraftingXp = 0, weaponcraftingMaxXp = 100,
        gearcraftingLevel = 1, gearcraftingXp = 0, gearcraftingMaxXp = 100,
        jewelrycraftingLevel = 1, jewelrycraftingXp = 0, jewelrycraftingMaxXp = 100,
        cookingLevel = 1, cookingXp = 0, cookingMaxXp = 100,
        alchemyLevel = 1, alchemyXp = 0, alchemyMaxXp = 100,
        hp = 205, maxHp = 205, haste = 0, criticalStrike = 35, wisdom = 35,
        prospecting = 0, initiative = 0, threat = 0,
        attackFire = 0, attackEarth = 0, attackWater = 0, attackAir = 6,
        dmg = 0, dmgFire = 0, dmgEarth = 0, dmgWater = 0, dmgAir = 0,
        resFire = 0, resEarth = 0, resWater = 0, resAir = 0,
        x = 0, y = 0, layer = "overworld", mapId = 1, cooldown = 0,
        cooldownExpiration = Instant.fromEpochMilliseconds(0),
        weaponSlot = "copper_dagger", runeSlot = "", shieldSlot = "", helmetSlot = "",
        bodyArmorSlot = "", legArmorSlot = "", bootsSlot = "", ring1Slot = "",
        ring2Slot = "", amuletSlot = "", artifact1Slot = "novice_guide", artifact2Slot = "",
        artifact3Slot = "", utility1Slot = "", utility1SlotQuantity = 0,
        utility2Slot = "", utility2SlotQuantity = 0, bagSlot = "",
        task = "blue_slime", taskType = "monsters", taskProgress = 0, taskTotal = 100,
        inventoryMaxItems = 100
    )
}
