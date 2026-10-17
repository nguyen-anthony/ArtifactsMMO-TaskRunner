package com.artifactsmmo.sandbox

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Regression for "the optimizer always fills utility slots": a character that already wins an easy
 * fight must not be told to equip potions, even when potions are sitting in its inventory.
 */
class GearOptimizerSandboxTest {

    @BeforeEach
    fun gate() = SandboxFixture.assumeSandboxAvailable()

    @Test
    fun `easy fight uses no utility potions`(): Unit = runBlocking { withTimeout(10.minutes) {
        val strongest = SandboxFixture.strongestCharacter()
        assumeTrue(strongest.level >= 10, "needs a character of level 10+ (strongest is ${strongest.level})")
        val c = SandboxFixture.ensureInventory(strongest.name, "small_health_potion", 5)

        val helper = SandboxFixture.actionHelper()
        val result = SandboxFixture.gearOptimizer(helper).optimize(c, "chicken")

        println("${c.name} vs chicken: winRate=${result.optimizedScore.winRate} " +
            "cycle=${result.optimizedScore.cycleSeconds}s utilities=${result.utilityActions}")
        assertTrue(result.optimizedScore.winRate >= 0.95, "expected an easy win, got ${result.optimizedScore}")
        // quantity 0 = "unequip what's there" (potions would otherwise be consumed); anything else is an equip.
        val equips = result.utilityActions.filter { it.quantity > 0 }
        assertTrue(equips.isEmpty(), "no potions needed vs chicken, got $equips")
    } }
}
