package com.artifactsmmo.core.task

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.Character
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * A monster task the loop didn't accept itself (resumed after a restart, or re-accepted after
 * deaths) used to go straight to fighting with only the best-weapon heuristic — no win-rate
 * gate and no full gear optimization (so nothing reached known_loadouts.json).
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class TaskMasterMonsterResumeTest {

    @Test
    fun resumedMonsterTaskIsGatedAndOptimizedBeforeFighting() = runTest {
        val char = character()
        val requests = mutableListOf<String>()
        val json = """application/json"""
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            requests += "${req.method.value} $path"
            when {
                req.method == HttpMethod.Get && path == "/characters/Ront" ->
                    respond("""{"data":${Json.encodeToString(Character.serializer(), char)}}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, json))
                path.contains("simulation") ->
                    respond("""{"data":{"results":[],"wins":20,"losses":0,"winrate":100}}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, json))
                else ->
                    respond("""{"error":{"code":404,"message":"not found"}}""",
                        HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, json))
            }
        }
        val client = ArtifactsMMOClient(token = "t", baseUrl = "http://test", engine = engine)
        val helper = ActionHelper(client, ContentCache(client.content), BankState(client, this))
        val fighting = FightingExecutor(helper)
        val executor = TaskMasterExecutor(helper, GatheringExecutor(helper), fighting)

        val result = executor.executeStep("Ront", TaskType.TaskMaster(type = "monsters"), {}, previousChar = char)

        assertIs<StepResult.Waiting>(result)
        assertTrue(requests.any { it.contains("simulation") }, "win-rate gate should simulate: $requests")
        assertTrue(requests.none { it.contains("/action/fight") }, "must not fight before optimizing: $requests")
    }

    /**
     * A preempting task (e.g. mining equips copper_pickaxe as the weapon) left the in-memory
     * "prepared for (monster, level)" marker intact, so the resumed task fought with the pickaxe.
     */
    @Test
    fun reclaimedMonsterTaskIsReoptimizedEvenAtSameLevel() = runTest {
        val char = character()
        val requests = mutableListOf<String>()
        val json = """application/json"""
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            requests += "${req.method.value} $path"
            when {
                req.method == HttpMethod.Get && path == "/characters/Ront" ->
                    respond("""{"data":${Json.encodeToString(Character.serializer(), char)}}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, json))
                path.contains("simulation") ->
                    respond("""{"data":{"results":[],"wins":20,"losses":0,"winrate":100}}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, json))
                else ->
                    respond("""{"error":{"code":404,"message":"not found"}}""",
                        HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, json))
            }
        }
        val client = ArtifactsMMOClient(token = "t", baseUrl = "http://test", engine = engine)
        val helper = ActionHelper(client, ContentCache(client.content), BankState(client, this))
        val executor = TaskMasterExecutor(helper, GatheringExecutor(helper), FightingExecutor(helper))
        val task = TaskType.TaskMaster(type = "monsters")

        executor.executeStep("Ront", task, {}, previousChar = char)
        executor.forgetLoadout("Ront")
        val statuses = mutableListOf<String>()
        requests.clear()
        val result = executor.executeStep("Ront", task, { statuses += it }, previousChar = char)

        assertIs<StepResult.Waiting>(result)
        assertTrue(statuses.any { it.startsWith("Optimizing loadout for yellow_slime") }, "should re-optimize: $statuses")
        assertTrue(requests.none { it.contains("/action/fight") }, "must not fight before re-optimizing: $requests")
    }

    private fun character() = Character(
        name = "Ront", account = "account", skin = "skin", level = 12, xp = 0, maxXp = 100,
        gold = 0, speed = 0,
        miningLevel = 1, miningXp = 0, miningMaxXp = 100,
        woodcuttingLevel = 1, woodcuttingXp = 0, woodcuttingMaxXp = 100,
        fishingLevel = 1, fishingXp = 0, fishingMaxXp = 100,
        weaponcraftingLevel = 1, weaponcraftingXp = 0, weaponcraftingMaxXp = 100,
        gearcraftingLevel = 1, gearcraftingXp = 0, gearcraftingMaxXp = 100,
        jewelrycraftingLevel = 1, jewelrycraftingXp = 0, jewelrycraftingMaxXp = 100,
        cookingLevel = 1, cookingXp = 0, cookingMaxXp = 100,
        alchemyLevel = 1, alchemyXp = 0, alchemyMaxXp = 100,
        hp = 185, maxHp = 185, haste = 0, criticalStrike = 0, wisdom = 0,
        prospecting = 0, initiative = 0, threat = 0,
        attackFire = 0, attackEarth = 0, attackWater = 0, attackAir = 0,
        dmg = 0, dmgFire = 0, dmgEarth = 0, dmgWater = 0, dmgAir = 0,
        resFire = 0, resEarth = 0, resWater = 0, resAir = 0,
        x = 0, y = 0, layer = "overworld", mapId = 1, cooldown = 0,
        cooldownExpiration = Instant.fromEpochMilliseconds(0),
        weaponSlot = "copper_dagger", runeSlot = "", shieldSlot = "", helmetSlot = "",
        bodyArmorSlot = "", legArmorSlot = "", bootsSlot = "", ring1Slot = "",
        ring2Slot = "", amuletSlot = "", artifact1Slot = "", artifact2Slot = "",
        artifact3Slot = "", utility1Slot = "", utility1SlotQuantity = 0,
        utility2Slot = "", utility2SlotQuantity = 0, bagSlot = "",
        task = "yellow_slime", taskType = "monsters", taskProgress = 80, taskTotal = 285,
        inventoryMaxItems = 100
    )
}
