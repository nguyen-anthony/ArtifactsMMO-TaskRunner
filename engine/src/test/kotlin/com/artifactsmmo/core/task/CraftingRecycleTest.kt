package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.CraftInfo
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleItem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The web form sends `targetQuantity = 0` for craft & recycle, which used to cap the first
 * batch at 0 — nothing was withdrawn and the task ended with "Not enough materials".
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class CraftingRecycleTest {

    @Test
    fun recycleWithoutQuantityWithdrawsMaterialsFromTheBank() = runTest {
        val legs = Item(
            name = "Copper Legs Armor", code = "copper_legs_armor", level = 1, type = "leg_armor",
            subtype = "", description = "", tradeable = true,
            craft = CraftInfo(skill = "gearcrafting", level = 1, items = listOf(SimpleItem("copper_bar", 5)), quantity = 1)
        )
        val char = character()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/items/copper_legs_armor" ->
                    respond("""{"data":${Json.encodeToString(Item.serializer(), legs)}}""", HttpStatusCode.OK, json)
                "/my/bank/items" ->
                    respond("""{"data":[{"code":"copper_bar","quantity":20}],"pages":1}""", HttpStatusCode.OK, json)
                "/characters/Shuckle" ->
                    respond("""{"data":${Json.encodeToString(Character.serializer(), char)}}""", HttpStatusCode.OK, json)
                else -> respond("""{"error":{"code":404,"message":"not found"}}""", HttpStatusCode.NotFound, json)
            }
        }
        val client = ArtifactsMMOClient(token = "t", baseUrl = "http://test", engine = engine)
        val bank = BankState(client, this)
        bank.refresh()
        val helper = ActionHelper(client, ContentCache(client.content), bank)
        val task = TaskType.Craft("gearcrafting", "copper_legs_armor", "Copper Legs Armor", CraftMode.RECYCLE, targetQuantity = 0)

        // No map tiles in this test, so reaching the bank is where the step stops — proof that
        // it went to withdraw the copper bars instead of heading to the workshop empty-handed.
        val error = assertFailsWith<IllegalStateException> {
            CraftingExecutor(helper).executeStep("Shuckle", task, {}, previousChar = char)
        }
        assertTrue(error.message.orEmpty().contains("bank"), error.message)
    }

    private fun character() = Character(
        name = "Shuckle", account = "account", skin = "skin", level = 5, xp = 0, maxXp = 100,
        gold = 0, speed = 0,
        miningLevel = 1, miningXp = 0, miningMaxXp = 100,
        woodcuttingLevel = 1, woodcuttingXp = 0, woodcuttingMaxXp = 100,
        fishingLevel = 1, fishingXp = 0, fishingMaxXp = 100,
        weaponcraftingLevel = 1, weaponcraftingXp = 0, weaponcraftingMaxXp = 100,
        gearcraftingLevel = 5, gearcraftingXp = 0, gearcraftingMaxXp = 100,
        jewelrycraftingLevel = 1, jewelrycraftingXp = 0, jewelrycraftingMaxXp = 100,
        cookingLevel = 1, cookingXp = 0, cookingMaxXp = 100,
        alchemyLevel = 1, alchemyXp = 0, alchemyMaxXp = 100,
        hp = 150, maxHp = 150, haste = 0, criticalStrike = 0, wisdom = 0,
        prospecting = 0, initiative = 0, threat = 0,
        attackFire = 0, attackEarth = 0, attackWater = 0, attackAir = 0,
        dmg = 0, dmgFire = 0, dmgEarth = 0, dmgWater = 0, dmgAir = 0,
        resFire = 0, resEarth = 0, resWater = 0, resAir = 0,
        x = 0, y = 0, layer = "overworld", mapId = 1, cooldown = 0,
        cooldownExpiration = Instant.fromEpochMilliseconds(0),
        weaponSlot = "", runeSlot = "", shieldSlot = "", helmetSlot = "",
        bodyArmorSlot = "", legArmorSlot = "", bootsSlot = "", ring1Slot = "",
        ring2Slot = "", amuletSlot = "", artifact1Slot = "", artifact2Slot = "",
        artifact3Slot = "", utility1Slot = "", utility1SlotQuantity = 0,
        utility2Slot = "", utility2SlotQuantity = 0, bagSlot = "",
        task = "", taskType = "", taskProgress = 0, taskTotal = 0,
        inventoryMaxItems = 100
    )
}
