package com.example.ads

import android.content.Context
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions

/**
 * Pure, framework-agnostic FIFO buffer/queue engine for preloaded ads.
 * - Maintains up to [bufferSize] (default 3) preloaded ads in memory.
 * - [popAd] removes and returns a preloaded ad synchronously (0ms delay) and immediately
 *   triggers background refill loads to restore the buffer.
 * - [requestAd] pops synchronously if available or queues a cancellable waiter for the next load.
 * - Expired ads (> [maxAgeMs]), orphaned loads, and cleared buffer entries always invoke [onDestroy].
 */
class NativeAdBuffer<T>(
    val bufferSize: Int = AdMobConstants.NATIVE_AD_BUFFER_SIZE,
    private val maxAgeMs: Long = AdMobConstants.NATIVE_AD_MAX_AGE_MS,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val onDestroy: (T) -> Unit,
    private val startLoad: (onLoaded: (T) -> Unit, onFailed: () -> Unit) -> Unit,
) {
    private data class BufferedItem<T>(
        val ad: T,
        val loadedAtMs: Long,
    )

    private class PendingWaiter<T>(
        val onLoaded: (T) -> Unit,
        val onFailed: () -> Unit,
        var canceled: Boolean = false,
    )

    private val lock = Any()
    private val queue = ArrayDeque<BufferedItem<T>>()
    private val waiters = ArrayDeque<PendingWaiter<T>>()
    private var inFlightCount: Int = 0
    private var isCleared: Boolean = false

    val bufferedCount: Int
        get() = synchronized(lock) {
            pruneExpiredLocked()
            queue.size
        }

    val activeInFlightCount: Int
        get() = synchronized(lock) { inFlightCount }

    /**
     * Triggers background loads until buffered + in-flight count reaches [bufferSize].
     */
    fun preloadBuffer() {
        synchronized(lock) {
            isCleared = false
        }
        ensureBufferFilled()
    }

    /**
     * Synchronously pulls a preloaded ad from the queue if available, and immediately
     * refills the queue in the background. Returns null if the buffer is empty.
     */
    fun popAd(): T? {
        val popped: T?
        synchronized(lock) {
            isCleared = false
            pruneExpiredLocked()
            popped = queue.removeFirstOrNull()?.ad
        }
        ensureBufferFilled()
        return popped
    }

    /**
     * Requests an ad: delivers synchronously from the buffer if non-empty, otherwise queues
     * a waiter for the next background load. Returns a cancellation callback to invoke when
     * the caller leaves composition before delivery.
     */
    fun requestAd(
        onLoaded: (T) -> Unit,
        onFailed: () -> Unit,
    ): () -> Unit {
        val immediate = popAd()
        if (immediate != null) {
            onLoaded(immediate)
            return {}
        }

        val waiter = PendingWaiter(onLoaded = onLoaded, onFailed = onFailed)
        synchronized(lock) {
            isCleared = false
            waiters.addLast(waiter)
        }
        ensureBufferFilled()
        return {
            synchronized(lock) {
                waiter.canceled = true
                waiters.remove(waiter)
            }
        }
    }

    /**
     * Destroys all buffered ads and cancels all pending UI waiters.
     */
    fun clear() {
        val toDestroy = mutableListOf<T>()
        synchronized(lock) {
            isCleared = true
            while (queue.isNotEmpty()) {
                toDestroy += queue.removeFirst().ad
            }
            while (waiters.isNotEmpty()) {
                waiters.removeFirst().canceled = true
            }
        }
        toDestroy.forEach(onDestroy)
    }

    private fun ensureBufferFilled() {
        val needed = synchronized(lock) {
            if (isCleared) return
            pruneExpiredLocked()
            val desired = maxOf(bufferSize, waiters.size.coerceAtMost(bufferSize))
            (desired - (queue.size + inFlightCount)).coerceAtLeast(0)
        }
        repeat(needed) {
            launchSingleLoad()
        }
    }

    private fun launchSingleLoad() {
        synchronized(lock) {
            if (isCleared) return
            inFlightCount += 1
        }
        startLoad(
            { loadedAd -> handleLoadSuccess(loadedAd) },
            { handleLoadFailure() },
        )
    }

    private fun handleLoadSuccess(loadedAd: T) {
        var deliverTo: PendingWaiter<T>? = null
        var shouldDestroyExcess = false

        synchronized(lock) {
            inFlightCount = (inFlightCount - 1).coerceAtLeast(0)
            if (isCleared) {
                shouldDestroyExcess = true
            } else {
                while (waiters.isNotEmpty()) {
                    val candidate = waiters.removeFirst()
                    if (!candidate.canceled) {
                        deliverTo = candidate
                        break
                    }
                }
                if (deliverTo == null) {
                    pruneExpiredLocked()
                    if (queue.size < bufferSize) {
                        queue.addLast(BufferedItem(ad = loadedAd, loadedAtMs = clockMillis()))
                    } else {
                        shouldDestroyExcess = true
                    }
                }
            }
        }

        when {
            shouldDestroyExcess -> onDestroy(loadedAd)
            deliverTo != null -> {
                deliverTo?.onLoaded?.invoke(loadedAd)
                ensureBufferFilled()
            }
            else -> ensureBufferFilled()
        }
    }

    private fun handleLoadFailure() {
        var failWaiter: PendingWaiter<T>? = null
        synchronized(lock) {
            inFlightCount = (inFlightCount - 1).coerceAtLeast(0)
            if (waiters.isNotEmpty() && queue.isEmpty() && inFlightCount == 0) {
                while (waiters.isNotEmpty()) {
                    val candidate = waiters.removeFirst()
                    if (!candidate.canceled) {
                        failWaiter = candidate
                        break
                    }
                }
            }
        }
        failWaiter?.onFailed?.invoke()
    }

    private fun pruneExpiredLocked() {
        val now = clockMillis()
        while (queue.isNotEmpty()) {
            val head = queue.first()
            if (now - head.loadedAtMs >= maxAgeMs) {
                queue.removeFirst()
                onDestroy(head.ad)
            } else {
                break
            }
        }
    }
}

/**
 * Application-scoped Native Ad Manager maintaining a 2-3 [NativeAd] preload queue/buffer
 * for instant display in grids & pagers without layout shifts or blank spaces.
 *
 * Callers MUST still call `nativeAd.destroy()` inside Compose `DisposableEffect` when the
 * displayed ad item leaves composition or scrolls off-screen.
 */
class NativeAdManager(
    appContext: Context,
    val adUnitId: String = AdMobConstants.TEST_NATIVE_ADVANCED_UNIT_ID,
    val bufferSize: Int = AdMobConstants.NATIVE_AD_BUFFER_SIZE,
) {
    private val context = appContext.applicationContext

    private val buffer = NativeAdBuffer<NativeAd>(
        bufferSize = bufferSize.coerceIn(2, 3),
        onDestroy = { ad -> ad.destroy() },
        startLoad = { onLoaded, onFailed -> loadNativeAd(onLoaded, onFailed) },
    )

    val bufferedCount: Int
        get() = buffer.bufferedCount

    fun preloadBuffer() {
        buffer.preloadBuffer()
    }

    fun popAd(): NativeAd? = buffer.popAd()

    fun requestAd(
        onLoaded: (NativeAd) -> Unit,
        onFailed: () -> Unit,
    ): () -> Unit = buffer.requestAd(onLoaded = onLoaded, onFailed = onFailed)

    fun clearBuffer() {
        buffer.clear()
    }

    private fun loadNativeAd(
        onLoaded: (NativeAd) -> Unit,
        onFailed: () -> Unit,
    ) {
        val videoOptions = VideoOptions.Builder()
            .setStartMuted(true)
            .build()
        val adOptions = NativeAdOptions.Builder()
            .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
            .setVideoOptions(videoOptions)
            .build()

        runCatching {
            val loader = AdLoader.Builder(context, adUnitId)
                .forNativeAd { nativeAd ->
                    onLoaded(nativeAd)
                }
                .withAdListener(
                    object : AdListener() {
                        override fun onAdFailedToLoad(error: LoadAdError) {
                            onFailed()
                        }
                    },
                )
                .withNativeAdOptions(adOptions)
                .build()

            loader.loadAd(AdRequest.Builder().build())
        }.onFailure {
            onFailed()
        }
    }
}
