package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.MapAccess
import com.artifactsmmo.client.models.MapContent
import com.artifactsmmo.client.models.MapInfo
import com.artifactsmmo.client.models.MapInteraction
import com.artifactsmmo.client.models.SimpleItem
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/** Up-front validation of the permanent-NPC buy task (no actions are spent when it can't work). */
@OptIn(kotlin.time.ExperimentalTime::class)
class NpcBuyExecutorTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private fun helper(scope: kotlinx.coroutines.CoroutineScope, tiles: List<MapInfo>): ActionHelper {
        val char = character()
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/characters/Shuckle" ->
                    respond("""{"data":${Json.encodeToString(Character.serializer(), char)}}""", HttpStatusCode.OK, json)
                "/npcs/items/rune_vendor" -> respond(
                    """{"data":[{"code":"small_rune","npc":"rune_vendor","currency":"gold","buy_price":100,"sell_price":null}],"pages":1}""",
                    HttpStatusCode.OK, json)
                else -> respond("""{"error":{"code":404,"message":"not found"}}""", HttpStatusCode.NotFound, json)
            }
        }
        val client = ArtifactsMMOClient(token = "t", baseUrl = "http://test", engine = engine)
        val cache = ContentCache(client.content).apply { setMaps(tiles) }
        return ActionHelper(client, cache, BankState(client, scope))
    }

    private fun npcTile(code: String) = MapInfo(
        mapId = 958, name = "Rune Vendor", skin = "", x = 0, y = 0, layer = "overworld",
        access = MapAccess(type = "standard"), interactions = MapInteraction(content = MapContent("npc", code)),
    )

    @Test
    fun failsWhenTheNpcTileIsNotAccessible() = runTest {
        val task = TaskType.NpcBuy("tasks_trader", "Tasks Trader", listOf(SimpleItem("jasper_crystal", 1)))
        val result = EventExecutor(helper(this, emptyList()), FightingExecutor(helper(this, emptyList())))
            .executeNpcBuyStep("Shuckle", task) {}
        assertEquals(StepResult.Failed("Tasks Trader is not reachable (its map tile may require an achievement)"), result)
    }

    @Test
    fun failsWhenTheNpcDoesNotSellAnItem() = runTest {
        val h = helper(this, listOf(npcTile("rune_vendor")))
        val task = TaskType.NpcBuy("rune_vendor", "Rune Vendor", listOf(SimpleItem("small_rune", 1), SimpleItem("diamond", 2)))
        val result = EventExecutor(h, FightingExecutor(h)).executeNpcBuyStep("Shuckle", task) {}
        assertEquals(StepResult.Failed("Rune Vendor doesn't sell diamond"), result)
    }

    private fun character() = Character(
        name = "Shuckle", account = "account", skin = "skin", level = 5, xp = 0, maxXp = 100,
        gold = 0, speed = 0,
        miningLevel = 1, miningXp = 0, miningMaxXp = 100,
        woodcuttingLevel = 1, woodcuttingXp = 0, woodcuttingMaxXp = 100,
        fishingLevel = 1, fishingXp = 0, fishingMaxXp = 100,
        weaponcraftingLevel = 1, weaponcraftingXp = 0, weaponcraftingMaxXp = 100,
        gearcraftingLevel = 1, gearcraftingXp = 0, gearcraftingMaxXp = 100,
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
