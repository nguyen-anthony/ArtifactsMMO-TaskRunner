package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.Character
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Regression for the duskworm event group: leftover equipped potions leaked into the
 * "gear only" coop simulation, so the optimizer saw 100% and planned no utilities while
 * the real (potion-free, after boss provisioning cleared slots) party won 0%.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class CoopSimulationRequestTest {

    private suspend fun captureCharacters(vararg loadouts: ActionHelper.CoopParticipantLoadout): List<JsonObject> =
        kotlinx.coroutines.coroutineScope {
            var body = ""
            val engine = MockEngine { req ->
                body = (req.body as TextContent).text
                respond(
                    """{"data":{"results":[],"wins":0,"losses":1,"winrate":0}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
            val client = ArtifactsMMOClient(token = "t", baseUrl = "http://test", engine = engine)
            val helper = ActionHelper(client, ContentCache(client.content), BankState(client, this))
            helper.simulateCoopFight("duskworm", loadouts.toList(), iterations = 1)
            Json.parseToJsonElement(body).jsonObject["characters"]!!.jsonArray.map { it.jsonObject }
        }

    @Test
    fun equippedUtilitiesDoNotLeakIntoGearOnlySimulation() = runTest {
        val char = character().copy(
            utility1Slot = "health_splash_potion", utility1SlotQuantity = 35,
            utility2Slot = "enhanced_health_potion", utility2SlotQuantity = 28
        )
        val sent = captureCharacters(ActionHelper.CoopParticipantLoadout(char)).single()
        assertNull(sent["utility1_slot"]?.takeUnless { it is kotlinx.serialization.json.JsonNull })
        assertNull(sent["utility2_slot"]?.takeUnless { it is kotlinx.serialization.json.JsonNull })
    }

    @Test
    fun plannedUtilitiesAreSentExactly() = runTest {
        val char = character().copy(utility2Slot = "enhanced_health_potion", utility2SlotQuantity = 28)
        val sent = captureCharacters(
            ActionHelper.CoopParticipantLoadout(
                char,
                utilityOverrides = mapOf("utility1" to "greater_health_potion"),
                utilityQuantities = mapOf("utility1" to 100)
            )
        ).single()
        assertEquals("greater_health_potion", sent["utility1_slot"]!!.jsonPrimitive.content)
        assertEquals(100, sent["utility1_slot_quantity"]!!.jsonPrimitive.content.toInt())
        assertNull(sent["utility2_slot"]?.takeUnless { it is kotlinx.serialization.json.JsonNull })
    }

    private fun character() = Character(
        name = "Ront", account = "account", skin = "skin", level = 50, xp = 0, maxXp = 100,
        gold = 0, speed = 0,
        miningLevel = 1, miningXp = 0, miningMaxXp = 100,
        woodcuttingLevel = 1, woodcuttingXp = 0, woodcuttingMaxXp = 100,
        fishingLevel = 1, fishingXp = 0, fishingMaxXp = 100,
        weaponcraftingLevel = 1, weaponcraftingXp = 0, weaponcraftingMaxXp = 100,
        gearcraftingLevel = 1, gearcraftingXp = 0, gearcraftingMaxXp = 100,
        jewelrycraftingLevel = 1, jewelrycraftingXp = 0, jewelrycraftingMaxXp = 100,
        cookingLevel = 1, cookingXp = 0, cookingMaxXp = 100,
        alchemyLevel = 1, alchemyXp = 0, alchemyMaxXp = 100,
        hp = 100, maxHp = 100, haste = 0, criticalStrike = 0, wisdom = 0,
        prospecting = 0, initiative = 0, threat = 0,
        attackFire = 0, attackEarth = 0, attackWater = 0, attackAir = 0,
        dmg = 0, dmgFire = 0, dmgEarth = 0, dmgWater = 0, dmgAir = 0,
        resFire = 0, resEarth = 0, resWater = 0, resAir = 0,
        x = 0, y = 0, layer = "overworld", mapId = 1, cooldown = 0,
        cooldownExpiration = Instant.fromEpochMilliseconds(0),
        weaponSlot = "desert_whip", runeSlot = "", shieldSlot = "", helmetSlot = "",
        bodyArmorSlot = "", legArmorSlot = "", bootsSlot = "", ring1Slot = "",
        ring2Slot = "", amuletSlot = "", artifact1Slot = "", artifact2Slot = "",
        artifact3Slot = "", utility1Slot = "", utility1SlotQuantity = 0,
        utility2Slot = "", utility2SlotQuantity = 0, bagSlot = "",
        task = "", taskType = "", taskProgress = 0, taskTotal = 0,
        inventoryMaxItems = 100
    )
}
