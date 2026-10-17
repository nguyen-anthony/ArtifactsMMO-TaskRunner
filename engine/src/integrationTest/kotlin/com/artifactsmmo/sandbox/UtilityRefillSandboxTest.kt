package com.artifactsmmo.sandbox

import com.artifactsmmo.client.models.SimpleItem
import com.artifactsmmo.core.task.FightingExecutor
import com.artifactsmmo.core.task.GearOptimizer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** A fight task tops its planned utility potions back up from the bank once a slot runs low. */
class UtilityRefillSandboxTest {

    private val potion = "small_health_potion"

    @BeforeEach
    fun gate() = SandboxFixture.assumeSandboxAvailable()

    @Test
    fun `low utility slot is refilled from the bank`(): Unit = runBlocking { withTimeout(5.minutes) {
        val name = SandboxFixture.strongestCharacter().name
        SandboxFixture.client.sandbox.clearCooldown(name)
        val setup = SandboxFixture.actionHelper()

        // Start from both utility slots empty, then put just 5 potions in utility1 and the rest in the bank.
        setup.retrieveAndEquipUtilities(name, emptyList(), clearUnplanned = true)
        SandboxFixture.ensureInventory(name, potion, 105)
        setup.equip(name, potion, "utility1", 5)
        var char = SandboxFixture.character(name)
        val leftover = setup.getItemQuantity(char, potion)
        if (leftover > 0) setup.bankDepositItems(name, listOf(SimpleItem(potion, leftover)))

        val helper = SandboxFixture.actionHelper() // fresh bank snapshot
        char = helper.refreshCharacter(name)
        assertEquals(5, helper.getEquippedUtilityQuantity(char, "utility1"))

        val plan = listOf(GearOptimizer.UtilityEquipAction("utility1", potion, 100, "bank"))
        char = FightingExecutor(helper).refillPlannedUtilities(name, char, plan) { println("[$name] $it") }

        val slot = GearOptimizer.UTILITY_SLOT_NAMES.first { helper.getEquippedInSlot(char, it) == potion }
        assertEquals(100, helper.getEquippedUtilityQuantity(char, slot))
        assertEquals(0, helper.getItemQuantity(char, potion), "withdrawn potions should all be equipped")
    } }
}
