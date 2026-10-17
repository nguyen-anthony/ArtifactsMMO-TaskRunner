package com.artifactsmmo.sandbox

import com.artifactsmmo.client.ArtifactsMMOClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Cheap checks that the sandbox is reachable, gated on membership, and isolated from production. */
class SandboxSmokeTest {

    @BeforeEach
    fun gate() = SandboxFixture.assumeSandboxAvailable()

    @Test
    fun `client targets sandbox and account has characters`(): Unit = runBlocking { withTimeout(2.minutes) {
        assertTrue(SandboxFixture.client.isSandbox)
        assertTrue(!ArtifactsMMOClient.isSandboxUrl(ArtifactsMMOClient.PRODUCTION_URL))
        val chars = SandboxFixture.characters()
        assertTrue(chars.isNotEmpty(), "Sandbox account ${SandboxFixture.username} has no characters")
        println("Sandbox characters: " + chars.joinToString { "${it.name} (lv ${it.level})" })
    } }
}
