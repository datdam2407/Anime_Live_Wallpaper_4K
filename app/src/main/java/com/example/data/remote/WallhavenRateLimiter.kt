package com.example.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sliding-window rate limiter + cooldown gatekeeper for the Wallhaven API.
 * Wallhaven enforces a strict 45 requests/minute policy; we cap outgoing traffic
 * at 38 requests per 60-second window with minimum inter-request pacing and
 * automatic 429 cooldown backoff so callers degrade gracefully to Room cache
 * or Reddit instead of exhausting quota.
 */
class WallhavenRateLimiter(
    private val maxRequestsPerWindow: Int = DEFAULT_MAX_REQUESTS_PER_MINUTE,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val minSpacingMillis: Long = DEFAULT_MIN_SPACING_MILLIS,
    private val defaultCooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val DEFAULT_MAX_REQUESTS_PER_MINUTE = 38
        const val DEFAULT_WINDOW_MILLIS = 60_000L
        const val DEFAULT_MIN_SPACING_MILLIS = 450L
        const val DEFAULT_COOLDOWN_MILLIS = 45_000L
    }

    sealed interface AcquireResult {
        data object Allowed : AcquireResult
        data class RateLimited(
            val retryAfterMs: Long,
            val fromServer429: Boolean,
        ) : AcquireResult
    }

    private val mutex = Mutex()
    private val requestTimestamps = ArrayDeque<Long>()
    private var lastRequestAtMs: Long = 0L
    private var cooldownUntilMs: Long = 0L

    suspend fun tryAcquire(): AcquireResult {
        while (true) {
            var spacingDelayMs = 0L
            mutex.withLock {
                val now = clock()
                pruneExpiredLocked(now)

                if (now < cooldownUntilMs) {
                    return AcquireResult.RateLimited(
                        retryAfterMs = (cooldownUntilMs - now).coerceAtLeast(1_000L),
                        fromServer429 = true,
                    )
                }

                if (requestTimestamps.size >= maxRequestsPerWindow) {
                    val oldest = requestTimestamps.first()
                    val waitMs = (oldest + windowMillis) - now
                    return AcquireResult.RateLimited(
                        retryAfterMs = waitMs.coerceAtLeast(1_000L),
                        fromServer429 = false,
                    )
                }

                val elapsedSinceLast = now - lastRequestAtMs
                if (lastRequestAtMs > 0L && elapsedSinceLast in 0 until minSpacingMillis) {
                    spacingDelayMs = minSpacingMillis - elapsedSinceLast
                } else {
                    requestTimestamps.addLast(now)
                    lastRequestAtMs = now
                    return AcquireResult.Allowed
                }
            }
            if (spacingDelayMs > 0L) {
                delay(spacingDelayMs)
            }
        }
    }

    suspend fun recordServerRateLimit(retryAfterSeconds: Long? = null) {
        mutex.withLock {
            val now = clock()
            val cooldownMs = retryAfterSeconds
                ?.takeIf { it > 0L }
                ?.let { (it * 1_000L).coerceIn(5_000L, 120_000L) }
                ?: defaultCooldownMillis
            cooldownUntilMs = maxOf(cooldownUntilMs, now + cooldownMs)
        }
    }

    suspend fun remainingQuota(): Int = mutex.withLock {
        val now = clock()
        pruneExpiredLocked(now)
        if (now < cooldownUntilMs) {
            0
        } else {
            (maxRequestsPerWindow - requestTimestamps.size).coerceAtLeast(0)
        }
    }

    private fun pruneExpiredLocked(now: Long) {
        val cutoff = now - windowMillis
        while (requestTimestamps.isNotEmpty() && requestTimestamps.first() <= cutoff) {
            requestTimestamps.removeFirst()
        }
    }
}
