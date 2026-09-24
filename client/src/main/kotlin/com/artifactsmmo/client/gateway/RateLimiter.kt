package com.artifactsmmo.client.gateway

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ArtifactsMMO rate-limit buckets (see artifacts-docs `api_guide/rate_limits.mdx`).
 * Every request belongs to exactly one bucket; each bucket has several time windows.
 */
enum class RateCategory { ACTION, DATA, SIMULATION, ACCOUNT;

    companion object {
        /** Maps an HTTP call to its bucket. */
        fun of(method: String, path: String): RateCategory {
            val p = path.substringBefore('?')
            return when {
                p.startsWith("/simulation") -> SIMULATION
                p.matches(Regex("^/my/[^/]+/action/.*")) || p.startsWith("/sandbox") -> ACTION
                p == "/token" || p == "/my/rates" || p.startsWith("/accounts/create") ||
                    p.startsWith("/characters/create") || p.startsWith("/characters/delete") ||
                    p.startsWith("/my/change_password") || p.startsWith("/gems") -> ACCOUNT
                method.equals("GET", ignoreCase = true) -> DATA
                // Remaining POSTs outside /action (e.g. account settings) use the account bucket.
                else -> ACCOUNT
            }
        }
    }
}

/** One sliding window: at most [limit] requests in any [durationMillis] span. */
data class RateWindow(val limit: Int, val durationMillis: Long)

/**
 * Documented server limits with ~5–20% headroom for clock skew and in-flight requests.
 * Server: action 10/s·100/min·5000/h, data 10/s·200/min·2000/h, simulation 1/s, account 10/s·300/h.
 */
object DefaultRateLimits {
    val ACTION = listOf(RateWindow(8, 1_000), RateWindow(95, 60_000), RateWindow(4_800, 3_600_000))
    val DATA = listOf(RateWindow(8, 1_000), RateWindow(190, 60_000), RateWindow(1_950, 3_600_000))
    val SIMULATION = listOf(RateWindow(1, 1_050))
    val ACCOUNT = listOf(RateWindow(8, 1_000), RateWindow(290, 3_600_000))

    fun all(): Map<RateCategory, List<RateWindow>> = mapOf(
        RateCategory.ACTION to ACTION,
        RateCategory.DATA to DATA,
        RateCategory.SIMULATION to SIMULATION,
        RateCategory.ACCOUNT to ACCOUNT,
    )
}

/** Wall clock abstraction so tests can use virtual time. */
fun interface Clock { fun nowMillis(): Long }
val SystemClock = Clock { System.currentTimeMillis() }

/**
 * Multi-window sliding-log limiter.
 *
 * Unlike the old ActionRateLimiter, the mutex is only held while *computing* the wait,
 * never while sleeping or while the HTTP request is in flight, so one character's backoff
 * doesn't serialize every other request.
 */
class RateBucket(private val windows: List<RateWindow>, private val clock: Clock = SystemClock) {
    private val mutex = Mutex()
    /** Timestamps of granted requests, oldest first. Trimmed to the longest window. */
    private val granted = ArrayDeque<Long>()
    /** Set after a 429: nobody may acquire before this instant. */
    private var pausedUntil = 0L
    private val longest = windows.maxOf { it.durationMillis }

    /** Suspends until a slot is available, then records it. */
    suspend fun acquire() {
        while (true) {
            val wait = mutex.withLock {
                val now = clock.nowMillis()
                trim(now)
                val w = waitMillis(now)
                if (w <= 0) granted.addLast(now)
                w
            }
            if (wait <= 0) return
            delay(wait)
        }
    }

    /** Block the whole bucket (called on HTTP 429). */
    suspend fun pauseFor(millis: Long) = mutex.withLock {
        pausedUntil = maxOf(pausedUntil, clock.nowMillis() + millis)
    }

    /** Request counts per window, for the UI. */
    suspend fun usage(): List<Pair<RateWindow, Int>> = mutex.withLock {
        val now = clock.nowMillis()
        trim(now)
        windows.map { w -> w to granted.count { it > now - w.durationMillis } }
    }

    private fun trim(now: Long) {
        while (granted.isNotEmpty() && granted.first() <= now - longest) granted.removeFirst()
    }

    private fun waitMillis(now: Long): Long {
        var wait = pausedUntil - now
        for (w in windows) {
            // Count requests inside this window; if full, wait until the oldest one ages out.
            val inWindow = granted.filter { it > now - w.durationMillis }
            if (inWindow.size >= w.limit) {
                val oldestThatMustExpire = inWindow[inWindow.size - w.limit]
                wait = maxOf(wait, oldestThatMustExpire + w.durationMillis - now + 1)
            }
        }
        return wait
    }
}

/** One bucket per category. Share a single instance per IP (i.e. per process). */
class RateLimiter(
    limits: Map<RateCategory, List<RateWindow>> = DefaultRateLimits.all(),
    clock: Clock = SystemClock,
) {
    private val buckets = limits.mapValues { (_, w) -> RateBucket(w, clock) }

    fun bucket(category: RateCategory): RateBucket = buckets.getValue(category)

    suspend fun snapshot(): Map<RateCategory, List<Pair<RateWindow, Int>>> =
        buckets.mapValues { it.value.usage() }
}
