package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Condition
import com.artifactsmmo.client.models.CraftInfo
import com.artifactsmmo.client.models.Item
import com.artifactsmmo.client.models.SimpleEffect
import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.config.ConfigStore
import com.artifactsmmo.engine.config.TypedConfigs
import com.artifactsmmo.engine.scheduler.CleanupProducer
import com.artifactsmmo.engine.worker.FakeTaskQueue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GearCleanupTest {
    private fun item(
        code: String, level: Int, vararg effects: Pair<String, Int>, type: String = "weapon",
        craft: CraftInfo? = CraftInfo(skill = "weaponcrafting", level = level),
    ) = Item(
        name = code, code = code, level = level, type = type, subtype = "", description = "",
        conditions = listOf(Condition("level", "gt", level - 1)),
        effects = effects.map { SimpleEffect(it.first, it.second, "") }, craft = craft, tradeable = true,
    )

    private val ironSword = item("iron_sword", 10, "attack_earth" to 24, "critical_strike" to 5)
    private val steelAxe = item("steel_battleaxe", 20, "attack_earth" to 40, "critical_strike" to 5)
    private val skullStaff = item("skull_staff", 20, "attack_fire" to 40, "critical_strike" to 5)
    private val goldSword = item("gold_sword", 30, "attack_earth" to 60, "attack_air" to 20, "critical_strike" to 5)
    private val obsidianAxe = item("obsidian_battleaxe", 30, "attack_earth" to 80, "inventory_space" to -25, "critical_strike" to 5)
    private val woodenClub = item("wooden_club", 25, "attack_earth" to 36, "critical_strike" to 35)

    private fun holder(name: String, level: Int, equipped: Map<String, Int> = emptyMap()) =
        CleanupHolder(name, level, equipped = equipped)

    @Test
    fun dominanceComparesEveryStatWithMissingAsZero() {
        assertTrue(GearCleanup.dominates(steelAxe, ironSword))
        assertTrue(GearCleanup.dominates(goldSword, steelAxe), "multi-element covers single element")
        assertFalse(GearCleanup.dominates(skullStaff, ironSword), "different elements never compare")
        assertFalse(GearCleanup.dominates(obsidianAxe, steelAxe), "inventory penalty blocks dominance")
        assertFalse(GearCleanup.dominates(steelAxe, woodenClub), "club's crit gives more expected damage")
        assertTrue(GearCleanup.dominates(woodenClub, ironSword))
        assertFalse(GearCleanup.dominates(ironSword, ironSword))
    }

    @Test
    fun weaponCritCountsAsExpectedDamage() {
        val fireBow = item("fire_bow", 10, "attack_fire" to 17, "critical_strike" to 35)
        assertTrue(GearCleanup.dominates(skullStaff, fireBow))
        assertFalse(GearCleanup.dominates(fireBow, skullStaff))
        // Close call: behind with no other crit (41 vs 43.7), ahead once other gear pushes crit to 100%.
        val highCrit = item("high_crit", 20, "attack_fire" to 38, "critical_strike" to 30)
        assertFalse(GearCleanup.dominates(skullStaff, highCrit))
        // Crit outside of weapons is still compared stat by stat.
        val ringA = item("ring_a", 10, "dmg" to 5, "critical_strike" to 10, type = "ring")
        val ringB = item("ring_b", 20, "dmg" to 10, "critical_strike" to 5, type = "ring")
        assertFalse(GearCleanup.dominates(ringB, ringA))
    }

    @Test
    fun cooldownEffectsAreBetterLowerAndThreatMustMatch() {
        val doll = item("altar_doll", 1, "mining_gathering" to -10, type = "artifact")
        val better = item("better_doll", 1, "mining_gathering" to -20, type = "artifact")
        assertTrue(GearCleanup.dominates(better, doll))
        assertFalse(GearCleanup.dominates(doll, better))
        val tank = item("tank_helm", 10, "hp" to 100, "threat" to 10, type = "helmet")
        val dps = item("dps_helm", 10, "hp" to 100, type = "helmet")
        assertFalse(GearCleanup.dominates(tank, dps))
        assertFalse(GearCleanup.dominates(dps, tank))
    }

    @Test
    fun keepsObsoleteCopiesForCharactersThatCannotUseTheBetterItem() {
        val holders = listOf(
            holder("A", 30, mapOf("steel_battleaxe" to 1)),
            holder("B", 25, mapOf("steel_battleaxe" to 1)),
            holder("C", 12),
            holder("D", 15),
        )
        val report = GearCleanup.analyze(
            listOf(ironSword, steelAxe), holders, mapOf("iron_sword" to 5, "steel_battleaxe" to 3),
            CleanupConfig(lookaheadLevels = 0),
        )
        val row = report.rows.single()
        assertEquals("iron_sword", row.code)
        assertEquals(2, row.kept)
        assertEquals(listOf("D", "C"), row.keptFor)
        assertEquals(3, row.surplus)
        assertEquals(3, row.recyclable)
        assertEquals(listOf("steel_battleaxe"), row.dominatedBy)
        assertEquals("weaponcrafting", row.craftSkill)
    }

    @Test
    fun lookaheadKeepsItemsForCharactersAboutToLevelIntoThem() {
        val holders = listOf(holder("A", 30), holder("Low", 8))
        val bank = mapOf("iron_sword" to 3, "steel_battleaxe" to 1)
        val soon = GearCleanup.analyze(listOf(ironSword, steelAxe), holders, bank, CleanupConfig(lookaheadLevels = 5)).rows.single()
        assertEquals(1, soon.kept)
        assertEquals(2, soon.surplus)
        val none = GearCleanup.analyze(listOf(ironSword, steelAxe), holders, bank, CleanupConfig(lookaheadLevels = 0)).rows.single()
        assertEquals(3, none.surplus)
    }

    @Test
    fun scarceBetterCopiesFallBackToTheObsoleteItem() {
        // Two level-30 characters, one steel axe: the second one still needs an iron sword.
        val holders = listOf(holder("A", 30), holder("B", 30))
        val row = GearCleanup.analyze(
            listOf(ironSword, steelAxe), holders, mapOf("iron_sword" to 4, "steel_battleaxe" to 1),
            CleanupConfig(lookaheadLevels = 0),
        ).rows.single()
        assertEquals(1, row.kept)
        assertEquals(3, row.surplus)
    }

    @Test
    fun ringsNeedTwoCopiesPerCharacter() {
        val copper = item("copper_ring", 1, "dmg" to 2, type = "ring", craft = CraftInfo(skill = "jewelrycrafting", level = 1))
        val iron = item("iron_ring", 10, "dmg" to 5, type = "ring", craft = CraftInfo(skill = "jewelrycrafting", level = 10))
        val holders = listOf(holder("A", 20), holder("B", 5))
        val row = GearCleanup.analyze(
            listOf(copper, iron), holders, mapOf("copper_ring" to 5, "iron_ring" to 1), CleanupConfig(lookaheadLevels = 0),
        ).rows.single()
        // A takes the only iron ring plus one copper; B takes two copper.
        assertEquals(3, row.kept)
        assertEquals(2, row.surplus)
    }

    @Test
    fun protectionsAndSparesHoldBackSurplus() {
        val holders = listOf(holder("A", 30))
        val bank = mapOf("iron_sword" to 5, "steel_battleaxe" to 1)
        val items = listOf(ironSword, steelAxe)
        fun row(cfg: CleanupConfig, protected: Map<String, String> = emptyMap(), available: Map<String, Int> = bank) =
            GearCleanup.analyze(items, holders, bank, cfg, protected, available).rows.single()

        assertEquals("keep list", row(CleanupConfig(keep = listOf("iron_sword"))).protectedBy)
        assertEquals(0, row(CleanupConfig(keep = listOf("iron_sword"))).surplus)
        assertEquals("loadout", row(CleanupConfig(), mapOf("iron_sword" to "loadout")).protectedBy)
        assertEquals(3, row(CleanupConfig(spares = 2)).surplus)
        assertEquals(1, row(CleanupConfig(), available = mapOf("iron_sword" to 1)).recyclable)

        val upgrade = item("greater_sword", 40, "attack_earth" to 100, craft = CraftInfo(
            skill = "weaponcrafting", level = 40, items = listOf(SimpleItem("iron_sword", 1)),
        ))
        val ingredient = GearCleanup.analyze(items + upgrade, holders, bank, CleanupConfig()).rows.first { it.code == "iron_sword" }
        assertEquals("recipe ingredient", ingredient.protectedBy)
    }

    @Test
    fun nobodyAbleToUseItemKeepsIt() {
        val row = GearCleanup.analyze(
            listOf(ironSword, steelAxe), listOf(holder("A", 1)), mapOf("iron_sword" to 2, "steel_battleaxe" to 1),
            CleanupConfig(lookaheadLevels = 0),
        ).rows.single()
        assertEquals(0, row.surplus)
        assertEquals("no character can use it yet", row.protectedBy)
    }

    private class MemConfigs : ConfigStore {
        val data = mutableMapOf<String, MutableMap<String, JsonElement>>()
        override suspend fun list(kind: String) = data[kind].orEmpty().toMap()
        override suspend fun put(kind: String, key: String, value: JsonElement) { data.getOrPut(kind) { mutableMapOf() }[key] = value }
        override suspend fun delete(kind: String, key: String) { data[kind]?.remove(key) }
    }

    @Test
    fun producerQueuesRecyclesOnlyWhenAutoIsOn() = runTest {
        val queue = FakeTaskQueue { 0L }
        val configs = TypedConfigs(MemConfigs())
        val producer = CleanupProducer(
            queue, configs, holders = { listOf(holder("A", 30)) },
            items = { listOf(ironSword, steelAxe) },
            bank = { mapOf("iron_sword" to 5, "steel_battleaxe" to 1) },
        )
        producer.tick()
        assertTrue(queue.list().isEmpty())
        assertEquals(5, producer.last?.rows?.single()?.surplus)

        configs.putCleanup(CleanupConfig(autoRecycle = true))
        producer.tick()
        producer.tick()
        val task = queue.list().single()
        assertEquals(TaskSpec.BankRecycle("iron_sword", 5, "weaponcrafting", "iron_sword"), TaskSpec.fromJson(task.spec))
        assertEquals(CleanupProducer.recycleKey("iron_sword"), task.dedupeKey)
        assertNull(task.assignedCharacter)
    }
}
