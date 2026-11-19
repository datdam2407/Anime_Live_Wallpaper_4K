package com.example.data.remote


import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


data class RateLimitDistractionPrompt(
    val id: Long,
    val requestCount: Int,
    val headline: String,
    val funFact: String,
    val breatherSeconds: Int = WallhavenRateLimiter.DEFAULT_BREATHER_SECONDS,
)


/**
 * Sliding-window rate limiter + 40th-request distraction gatekeeper for the Wallhaven API.
 * Wallhaven enforces a strict 45 requests/minute policy:
 * - At the 40th request within a 60-second window, emits a [RateLimitDistractionPrompt]
 *   so the UI can display a fun anime trivia breather dialog and naturally stall the user.
 * - Caps outgoing traffic at 43 requests per 60-second window with minimum inter-request pacing
 *   and automatic 429 cooldown backoff.
 */
class WallhavenRateLimiter(
    private val maxRequestsPerWindow: Int = DEFAULT_MAX_REQUESTS_PER_MINUTE,
    private val distractionThreshold: Int = DISTRACTION_REQUEST_THRESHOLD,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    private val minSpacingMillis: Long = DEFAULT_MIN_SPACING_MILLIS,
    private val defaultCooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    private val clock: () -> Long = ::defaultMonotonicClock,
) {
    companion object {
        const val DISTRACTION_REQUEST_THRESHOLD = 40
        const val DEFAULT_MAX_REQUESTS_PER_MINUTE = 43
        const val DEFAULT_WINDOW_MILLIS = 60_000L
        const val DEFAULT_MIN_SPACING_MILLIS = 450L
        const val DEFAULT_COOLDOWN_MILLIS = 45_000L
        const val DEFAULT_BREATHER_SECONDS = 4


        /**
         * Monotonic clock immune to NTP sync or user wall-clock changes.
         * Falls back to System.nanoTime() when running in pure JVM unit tests without Robolectric.
         */
        fun defaultMonotonicClock(): Long =
            runCatching { SystemClock.elapsedRealtime() }
                .getOrElse { System.nanoTime() / 1_000_000L }


        private val ANIME_BREATHER_FACTS: List<Pair<String, String>> = listOf(
            "Did you know?" to
                    "In Jujutsu Kaisen, Gojo's Six Eyes frames were animated at ultra-high keyframe density—giving our 4K servers a quick breather keeps your feed silky smooth!",
            "Taking a quick breather…" to
                    "Makoto Shinkai's background artists paint cloud layers at 4K+ resolution before compositing light rays. Pausing a few seconds keeps Wallhaven's 45 req/min gate happy!",
            "Did you know?" to
                    "Frieren: Beyond Journey's End recorded real acoustic folk instruments in reverberant halls for its soundtrack. Your next batch of 4K artworks is lining up!",
            "Katana cooldown…" to
                    "Ufotable blends hand-drawn 2D sword arcs with custom 3D camera shaders in Demon Slayer. Catching our breath for a moment prevents server rate-limiting!",
        )
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
    private var lastRequestAtMs: Long = Long.MIN_VALUE
    private var cooldownUntilMs: Long = Long.MIN_VALUE
    private var distractionShownInCurrentWindow: Boolean = false
    private var distractionSequence: Int = 0


    private val _distractionPrompt = MutableStateFlow<RateLimitDistractionPrompt?>(null)
    val distractionPrompt: StateFlow<RateLimitDistractionPrompt?> = _distractionPrompt.asStateFlow()


    fun dismissDistractionPrompt() {
        _distractionPrompt.value = null
    }


    suspend fun tryAcquire(): AcquireResult {
        while (true) {
            var spacingDelayMs = 0L
            mutex.withLock {
                val now = clock()
                pruneExpiredLocked(now)


                if (cooldownUntilMs != Long.MIN_VALUE && now < cooldownUntilMs) {
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


                val elapsedSinceLast = if (lastRequestAtMs == Long.MIN_VALUE) Long.MAX_VALUE else now - lastRequestAtMs
                if (lastRequestAtMs != Long.MIN_VALUE && elapsedSinceLast in 0 until minSpacingMillis) {
                    spacingDelayMs = minSpacingMillis - elapsedSinceLast
                } else {
                    requestTimestamps.addLast(now)
                    lastRequestAtMs = now
                    val activeCount = requestTimestamps.size
                    if (distractionThreshold > 0 &&
                        activeCount >= distractionThreshold &&
                        !distractionShownInCurrentWindow
                    ) {
                        distractionShownInCurrentWindow = true
                        val factIndex = distractionSequence % ANIME_BREATHER_FACTS.size
                        distractionSequence++
                        val (headline, funFact) = ANIME_BREATHER_FACTS[factIndex]
                        _distractionPrompt.value = RateLimitDistractionPrompt(
                            id = now + distractionSequence,
                            requestCount = activeCount,
                            headline = headline,
                            funFact = funFact,
                            breatherSeconds = DEFAULT_BREATHER_SECONDS,
                        )
                    }
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
            val targetUntil = now + cooldownMs
            cooldownUntilMs = if (cooldownUntilMs == Long.MIN_VALUE) {
                targetUntil
            } else {
                maxOf(cooldownUntilMs, targetUntil)
            }
        }
    }


    suspend fun remainingQuota(): Int = mutex.withLock {
        val now = clock()
        pruneExpiredLocked(now)
        if (cooldownUntilMs != Long.MIN_VALUE && now < cooldownUntilMs) {
            0
        } else {
            (maxRequestsPerWindow - requestTimestamps.size).coerceAtLeast(0)
        }
    }


    suspend fun activeRequestsInWindow(): Int = mutex.withLock {
        val now = clock()
        pruneExpiredLocked(now)
        requestTimestamps.size
    }


    private fun pruneExpiredLocked(now: Long) {
        val cutoff = now - windowMillis
        while (requestTimestamps.isNotEmpty() && requestTimestamps.first() <= cutoff) {
            requestTimestamps.removeFirst()
        }
        if (distractionThreshold > 0 && requestTimestamps.size < distractionThreshold) {
            distractionShownInCurrentWindow = false
        }
    }
}
