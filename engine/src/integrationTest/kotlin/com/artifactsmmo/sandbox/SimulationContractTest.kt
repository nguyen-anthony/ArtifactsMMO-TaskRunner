package com.artifactsmmo.sandbox

import com.artifactsmmo.client.models.CombatSimulationRequest
import com.artifactsmmo.client.models.FakeCharacterRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Pins down simulation response fields the optimizers depend on but the OpenAPI schema leaves loose
 * (`character_results[].final_hp`, win-rate scale). Simulations cost no character cooldown.
 */
class SimulationContractTest {

    @BeforeEach
    fun gate() = SandboxFixture.assumeSandboxAvailable()

    @Test
    fun `solo simulation reports final hp per character and a 0-1 win rate`(): Unit = runBlocking { withTimeout(2.minutes) {
        val c = SandboxFixture.strongestCharacter()
        val sim = SandboxFixture.client.simulation.simulateFight(
            CombatSimulationRequest(listOf(FakeCharacterRequest.fromCharacter(c)), "chicken", iterations = 5)
        )
        assertEquals(5, sim.wins + sim.losses)
        assertTrue(sim.winrate in 0.0..1.0, "winrate should be normalized to 0..1, was ${sim.winrate}")
        val first = sim.results.first()
        assertEquals(1, first.characterResults.size, "one character_results entry per simulated character")
        val hp = assertNotNull(first.finalHp(0), "character_results[0].final_hp missing: ${first.characterResults}")
        assertTrue(hp in 0..c.maxHp, "final_hp $hp outside 0..${c.maxHp}")
    } }

    @Test
    fun `party simulation reports final hp in request order`(): Unit = runBlocking { withTimeout(2.minutes) {
        val chars = SandboxFixture.characters().sortedByDescending { it.level }.take(3)
        assumeTrue(chars.size >= 2, "needs at least two sandbox characters")
        val sim = SandboxFixture.client.simulation.simulateFight(
            // Multi-character fights are boss-only (486 otherwise); the outcome doesn't matter here.
            CombatSimulationRequest(chars.map { FakeCharacterRequest.fromCharacter(it) }, "king_slime", iterations = 3)
        )
        val first = sim.results.first()
        assertEquals(chars.size, first.characterResults.size)
        chars.forEachIndexed { i, c ->
            val name = first.characterResults[i]["character_name"]?.toString()?.trim('"')
            if (name != null) assertEquals(c.name, name, "character_results order should match request order")
            assertNotNull(first.finalHp(i), "final_hp missing for ${c.name}")
        }
    } }
}
