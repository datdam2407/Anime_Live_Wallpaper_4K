package com.example.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Policy-compliant AdMob Interstitial Manager:
 * - Pre-loads interstitial ads in the background before user triggers occur
 * - Enforces a strict 200-second cooldown between ad impressions (Ad Serving Limit protection)
 * - Shows ONLY when the user clicks "Back" to leave the Detail screen AND meets:
 *   - Condition A: User has viewed >= 8 distinct detail images, OR
 *   - Condition B: User has successfully set a static or live wallpaper to their screen
 * - NEVER shows on App Open, during swipes, immediately on Set Wallpaper, or on Home exit Back
 */
class InterstitialAdManager(
    private val appContext: Context?,
    private val adUnitId: String = AdMobConstants.TEST_INTERSTITIAL_UNIT_ID,
    private val cooldownMillis: Long = AdMobConstants.INTERSTITIAL_COOLDOWN_MS,
    private val clockMillis: () -> Long = { SystemClock.elapsedRealtime() },
) {
    companion object {
        private const val TAG = "InterstitialAdManager"
    }

    private var interstitialAd: InterstitialAd? = null
    private var isLoading: Boolean = false
    private var lastAdShownAtMs: Long? = null

    var detailViewCount: Int = 0
        private set

    var hasAppliedWallpaper: Boolean = false
        private set

    private var lastViewedWallpaperId: String? = null

    /**
     * Pre-loads an interstitial ad in the background if one is not already loaded or loading.
     */
    fun preload(context: Context? = appContext) {
        if (interstitialAd != null || isLoading) return
        val safeContext = context?.applicationContext ?: return
        isLoading = true

        val adRequest = AdRequest.Builder().build()

        runCatching {
            InterstitialAd.load(
                safeContext,
                adUnitId,
                adRequest,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        isLoading = false
                        interstitialAd = ad
                        logD("Test Interstitial preloaded and ready")
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        isLoading = false
                        interstitialAd = null
                        logW("Interstitial failed to load: ${error.message}")
                    }
                },
            )
        }.onFailure {
            isLoading = false
            interstitialAd = null
        }
    }

    /**
     * Returns true strictly if no ad has been shown yet OR >= 200 seconds have passed since last ad.
     */
    fun canShowAd(nowMs: Long = clockMillis()): Boolean {
        val lastShown = lastAdShownAtMs ?: return true
        return (nowMs - lastShown) >= cooldownMillis
    }

    fun remainingCooldownSeconds(nowMs: Long = clockMillis()): Long {
        val lastShown = lastAdShownAtMs ?: return 0L
        val elapsed = nowMs - lastShown
        return ((cooldownMillis - elapsed).coerceAtLeast(0L) + 999L) / 1000L
    }

    /**
     * Records Condition A: distinct detail image viewed (deduplicated for same consecutive ID).
     * Never triggers an ad on its own; evaluated only when clicking Back to leave Detail.
     */
    fun onDetailImageViewed(wallpaperId: String) {
        if (wallpaperId.isNotBlank() && wallpaperId != lastViewedWallpaperId) {
            lastViewedWallpaperId = wallpaperId
            detailViewCount++
        }
    }

    /**
     * Records Condition B: user successfully applied a static or live wallpaper.
     * Never triggers an ad immediately; evaluated only when clicking Back to leave Detail.
     */
    fun markWallpaperApplied() {
        hasAppliedWallpaper = true
    }

    /**
     * Evaluates Condition A (>= 8 detail views) OR Condition B (wallpaper applied) strictly
     * when the user clicks Back to leave the Detail screen.
     * Note: Never call this on Home/app-exit Back presses.
     */
    fun onDetailBackClicked(
        activity: Activity?,
        onNavigateBack: () -> Unit,
    ) {
        val metViewThreshold = detailViewCount >= AdMobConstants.DETAIL_VIEW_BACK_THRESHOLD
        val metWallpaperApplied = hasAppliedWallpaper

        if (metViewThreshold || metWallpaperApplied) {
            val triggerName = if (metWallpaperApplied) {
                "detail_back_after_wallpaper_set"
            } else {
                "detail_back_after_${AdMobConstants.DETAIL_VIEW_BACK_THRESHOLD}_views"
            }
            detailViewCount = 0
            hasAppliedWallpaper = false
            lastViewedWallpaperId = null
            showIfEligible(
                activity = activity,
                triggerName = triggerName,
                onComplete = onNavigateBack,
            )
        } else {
            // Reset consecutive duplicate guard across separate detail visits while keeping accumulated view count
            lastViewedWallpaperId = null
            onNavigateBack()
        }
    }

    internal fun showIfEligible(
        activity: Activity?,
        triggerName: String,
        onComplete: () -> Unit = {},
    ): Boolean {
        val nowMs = clockMillis()
        if (!canShowAd(nowMs)) {
            logD("Blocked '$triggerName': cooldown active (${remainingCooldownSeconds(nowMs)}s remaining)")
            preload(appContext)
            onComplete()
            return false
        }

        val ad = interstitialAd
        if (ad == null || activity == null || activity.isFinishing || activity.isDestroyed) {
            logD("Skipped '$triggerName': preloaded ad unavailable or activity invalid")
            preload(appContext)
            onComplete()
            return false
        }

        interstitialAd = null
        lastAdShownAtMs = nowMs

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                lastAdShownAtMs = clockMillis()
            }

            override fun onAdDismissedFullScreenContent() {
                ad.fullScreenContentCallback = null
                lastAdShownAtMs = clockMillis()
                preload(appContext)
                onComplete()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                ad.fullScreenContentCallback = null
                logW("Failed to show interstitial: ${adError.message}")
                preload(appContext)
                onComplete()
            }
        }

        ad.show(activity)
        return true
    }

    private fun logD(message: String) {
        runCatching { Log.d(TAG, message) }
    }

    private fun logW(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    internal fun markShownNowForTest() {
        lastAdShownAtMs = clockMillis()
    }
}
