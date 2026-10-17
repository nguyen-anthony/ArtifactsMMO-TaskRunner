package com.artifactsmmo.sandbox

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.services.SandboxService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One-off provisioning after a sandbox wipe: makes sure the account has [TARGET_CHARACTERS] characters
 * at [TARGET_LEVEL] in combat and every skill. Opt-in only (each give_xp call is a 3s cooldown, capped
 * at 100k XP), so it can take a long time:
 *
 *   ./gradlew :engine:integrationTest -Psandbox.provision=true --tests "*SandboxProvisioning*"
 *
 * Never deletes characters or resets the account; existing characters are only topped up.
 */
class SandboxProvisioningTest {

    @BeforeEach
    fun gate() {
        assumeTrue(System.getProperty("sandbox.provision") == "true", "provisioning is opt-in (-Psandbox.provision=true)")
        SandboxFixture.assumeSandboxAvailable()
    }

    @Test
    fun `account has five max-level characters`(): Unit = runBlocking {
        val sandbox = SandboxFixture.client.sandbox
        var chars = SandboxFixture.characters()
        val prefix = SandboxFixture.username.filter { it.isLetterOrDigit() }.take(6).ifEmpty { "it" }
        var n = 1
        while (chars.size < TARGET_CHARACTERS) {
            val name = "${prefix}_it$n".take(12)
            n++
            if (chars.any { it.name == name }) continue
            println("Creating sandbox character $name")
            runCatching { SandboxFixture.client.characters.createCharacter(name, "men1") }
                .onFailure { println("  create $name failed: ${it.message}") }
            chars = SandboxFixture.characters()
            check(n < 50) { "could not create enough characters" }
        }

        for (c in chars.take(TARGET_CHARACTERS)) {
            for ((type, level) in XP_TYPES) {
                var current = SandboxFixture.character(c.name)
                var calls = 0
                while (level(current) < TARGET_LEVEL) {
                    check(calls++ < MAX_CALLS_PER_SKILL) { "${c.name} $type stuck at ${level(current)}" }
                    current = sandbox.giveXp(c.name, type, SandboxService.MAX_XP_PER_CALL)
                }
                if (calls > 0) println("${c.name}: $type -> ${level(current)} ($calls give_xp calls)")
            }
        }
        val final = SandboxFixture.characters().take(TARGET_CHARACTERS)
        assertTrue(final.all { c -> XP_TYPES.values.all { it(c) >= TARGET_LEVEL } })
    }

    private companion object {
        const val TARGET_CHARACTERS = 5
        const val TARGET_LEVEL = 50
        const val MAX_CALLS_PER_SKILL = 400
        val XP_TYPES: Map<String, (Character) -> Int> = linkedMapOf(
            "combat" to { c -> c.level },
            "weaponcrafting" to { c -> c.weaponcraftingLevel },
            "gearcrafting" to { c -> c.gearcraftingLevel },
            "jewelrycrafting" to { c -> c.jewelrycraftingLevel },
            "cooking" to { c -> c.cookingLevel },
            "woodcutting" to { c -> c.woodcuttingLevel },
            "mining" to { c -> c.miningLevel },
            "alchemy" to { c -> c.alchemyLevel },
            "fishing" to { c -> c.fishingLevel },
        )
    }
}
