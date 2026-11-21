package com.example.ads

/**
 * Official Google AdMob Test IDs & Google Play Policy Frequency Constants.
 * NEVER ship production apps with test IDs, and NEVER use real IDs during development/testing.
 */
object AdMobConstants {
    // Google Official Test IDs
    const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
    const val TEST_ADAPTIVE_BANNER_UNIT_ID = "ca-app-pub-3940256099942544/9214589741"
    const val TEST_INTERSTITIAL_UNIT_ID = "ca-app-pub-3940256099942544/1033173712"
    const val TEST_NATIVE_ADVANCED_UNIT_ID = "ca-app-pub-3940256099942544/2247696110"

    // Strict Frequency Capping & Placement Policy Intervals
    const val INTERSTITIAL_COOLDOWN_MS = 200_000L // Strict 200 seconds between interstitials
    const val DETAIL_VIEW_BACK_THRESHOLD = 8 // Condition A: 8 detailed images viewed before Back
    const val GRID_NATIVE_AD_INTERVAL = 6 // Every 6 wallpaper items (6-8 range, clean 2-col row break)
    const val DETAIL_PAGER_NATIVE_AD_INTERVAL = 5 // Every 5 wallpaper images swiped in HorizontalPager

    // Anime 200 Progressive Ad Intervals (6 -> +8 -> +10 -> +12...)
    const val ANIME_200_INITIAL_AD_GAP = 6
    const val ANIME_200_AD_GAP_STEP = 2

    // Native Ad Preload Queue / Buffer (keep at 1 initially to maintain high AdMob Show Rate)
    const val NATIVE_AD_BUFFER_SIZE = 1
    const val NATIVE_AD_MAX_AGE_MS = 50L * 60L * 1000L // 50 minutes (safely under 1-hour AdMob expiry)
}
