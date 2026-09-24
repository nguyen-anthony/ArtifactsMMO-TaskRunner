package com.artifactsmmo.core.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Global rate limiter for the /simulation/fight API endpoint.
 *
 * The Artifacts MMO API allows 1 simulation request per second per IP. This limiter
 * serializes all simulation API calls across the entire process with a small buffer
 * to avoid 429 responses when multiple characters optimize concurrently.
 *
 * Usage:
 *   SimulationRateLimiter.execute {
 *       client.simulation.simulateFight(request)
 *   }
 *
 * The mutex ensures only one call is in flight at any given time; the delay ensures
 * subsequent calls wait until the interval has elapsed. lastCallTime is updated in a
 * finally block so failed calls (which still consumed rate budget on the server) don't
 * accidentally allow the next call to fire early.
 */
object SimulationRateLimiter {
    private val mutex = Mutex()
    private var lastCallTime = 0L
    private const val MIN_INTERVAL_MS = 1050L

    suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock {
        val elapsed = System.currentTimeMillis() - lastCallTime
        if (elapsed < MIN_INTERVAL_MS) delay(MIN_INTERVAL_MS - elapsed)
        try {
            block()
        } finally {
            lastCallTime = System.currentTimeMillis()
        }
    }
}
