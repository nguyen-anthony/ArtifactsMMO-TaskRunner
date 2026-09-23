package com.artifactsmmo.client

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide guard for ArtifactsMMO character actions.
 *
 * Action endpoints share a 10 requests/second bucket per IP across every character.
 * Character runners are concurrent, so per-runner cooldown waits do not protect against
 * bursts during coordinated provisioning, banking, or task transitions. Serializing at
 * 8 requests/second leaves headroom for server-side timing variance and prevents one
 * character's setup from causing another character to receive a 429.
 *
 * Simulation calls have their own stricter limiter and are additionally protected by
 * SimulationRateLimiter in core.
 */
object ActionRateLimiter {
    private const val MIN_INTERVAL_MS = 125L // 8 requests/second, below the 10/sec limit
    private const val MAX_429_RETRIES = 3
    private const val RETRY_DELAY_MS = 1_500L
    private val mutex = Mutex()
    private var lastActionAtMillis = 0L

    suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock {
        val elapsed = System.currentTimeMillis() - lastActionAtMillis
        if (elapsed < MIN_INTERVAL_MS) delay(MIN_INTERVAL_MS - elapsed)
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: ArtifactsApiException) {
                // Requests already in flight before a coordinated dispatch can consume
                // bucket capacity. Retry under this global mutex so characters back off
                // together instead of racing each other after a server 429.
                if (e.errorCode != 429 || attempt++ >= MAX_429_RETRIES) throw e
                delay(RETRY_DELAY_MS * attempt)
            } finally {
                // A failed request can still consume a server-side rate-limit slot.
                lastActionAtMillis = System.currentTimeMillis()
            }
        }
        error("Unreachable")
    }
}
