package com.artifactsmmo.sandbox

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.MyDetails
import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.BankState
import com.artifactsmmo.core.task.ContentCache
import com.artifactsmmo.core.task.GearOptimizer
import com.artifactsmmo.core.task.KnownLoadoutStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File

/**
 * Shared, process-wide access to the ArtifactsMMO **sandbox** server for integration tests.
 *
 * Rules (see .github/skills/sandbox-integration-tests/SKILL.md):
 *  - Only ever talks to [ArtifactsMMOClient.SANDBOX_URL]; the client refuses sandbox cheats elsewhere.
 *  - Never resets the account or deletes characters.
 *  - Every sandbox cheat costs a ~3s character cooldown and counts toward the action rate limit,
 *    so tests should give only what they need and reuse state left by earlier runs.
 *  - Tests are skipped (not failed) when no token is configured or the account isn't a member.
 */
object SandboxFixture {

    val token: String? by lazy {
        System.getenv("ARTIFACTS_TOKEN")?.takeIf { it.isNotBlank() } ?: readDotEnv("ARTIFACTS_TOKEN")
    }

    val client: ArtifactsMMOClient by lazy {
        ArtifactsMMOClient(token = token, baseUrl = ArtifactsMMOClient.SANDBOX_URL).also {
            check(it.isSandbox) { "Integration tests must target the sandbox server" }
        }
    }

    private val details: Result<MyDetails>? by lazy {
        token?.let { runCatching { runBlocking { client.account.getMyDetails() } } }
    }

    /** Skips the calling test unless a token is configured and the account has an active membership. */
    fun assumeSandboxAvailable() {
        assumeTrue(token != null, "ARTIFACTS_TOKEN not set (env or .env); skipping sandbox tests")
        val d = details!!.getOrElse { e ->
            assumeTrue(false, "Could not reach sandbox /my/details: ${e.message}")
            error("unreachable")
        }
        assumeTrue(d.member, "Account ${d.username} has no active membership; the sandbox requires one")
    }

    val username: String get() = details?.getOrNull()?.username ?: error("call assumeSandboxAvailable() first")

    suspend fun characters(): List<Character> = client.characters.getMyCharacters()

    suspend fun character(name: String): Character = client.characters.getCharacter(name)

    /** Highest-level character; most tests want the one most likely to win fights. */
    suspend fun strongestCharacter(): Character = characters().maxBy { it.level }

    /** Tops up [code] in the character's inventory to at least [quantity] (one sandbox call, only if short). */
    suspend fun ensureInventory(character: String, code: String, quantity: Int): Character {
        val c = character(character)
        val have = c.inventory.filter { it.code == code }.sumOf { it.quantity }
        return if (have >= quantity) c else client.sandbox.giveItem(character, code, quantity - have)
    }

    /**
     * Sandbox-backed [ActionHelper] with a fresh bank snapshot (no WebSocket, no periodic refresh)
     * and the map graph loaded like `Engine.start`, so navigation (e.g. bank trips) works.
     */
    suspend fun actionHelper(): ActionHelper {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val bank = BankState(client, scope)
        bank.refresh()
        val achievements = runCatching { client.account.getCompletedAchievementCodes(username) }.getOrDefault(emptySet())
        val content = ContentCache(client.content).apply { preWarmMaps(achievements) }
        return ActionHelper(client, content, bank).also { it.completedAchievements = achievements }
    }

    /** Optimizer with a throwaway known-loadout file so tests never touch the real `known_loadouts.json`. */
    fun gearOptimizer(helper: ActionHelper): GearOptimizer {
        val store = File.createTempFile("known_loadouts", ".json").apply { delete(); deleteOnExit() }
        return GearOptimizer(helper, KnownLoadoutStore(store))
    }

    private fun readDotEnv(key: String): String? {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, ".env") }.firstOrNull { it.isFile } ?: return null
        return file.readLines().asSequence()
            .map { it.trim() }
            .filter { it.startsWith("$key=") }
            .map { it.substringAfter('=').trim().trim('"', '\'') }
            .firstOrNull { it.isNotEmpty() }
    }
}
