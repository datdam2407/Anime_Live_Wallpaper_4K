package com.example

import com.example.ads.AdMobConstants
import com.example.ads.CategoryGridEntry
import com.example.ads.DetailPagerEntry
import com.example.ads.InterstitialAdManager
import com.example.ads.NativeAdBuffer
import com.example.ads.WallpaperGridEntry
import com.example.ads.buildCategoriesWithProgressiveAds
import com.example.ads.buildDetailPagerEntries
import com.example.ads.buildFeedWithNativeAds
import com.example.ads.calculateProgressiveAdPositions
import com.example.ads.mapWallpaperIndexToPagerPosition
import com.example.domain.model.AnimeCatalog
import com.example.domain.model.AppWallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdMobPolicyTest {

    private fun sampleWallpapers(count: Int): List<AppWallpaper> = List(count) { idx ->
        AppWallpaper(
            id = "wp_$idx",
            title = "Anime $idx",
            animeSeries = "Series $idx",
            imageUrl = "https://example.com/$idx.jpg",
            thumbnailUrl = "https://example.com/${idx}_thumb.jpg",
            source = "Wallhaven",
        )
    }

    @Test
    fun `interstitial cooldown strictly blocks ads under 200 seconds`() {
        var fakeClockMs = 1_000_000L
        val manager = InterstitialAdManager(
            appContext = null,
            cooldownMillis = AdMobConstants.INTERSTITIAL_COOLDOWN_MS,
            clockMillis = { fakeClockMs },
        )

        assertTrue(manager.canShowAd())
        manager.markShownNowForTest()

        assertFalse(manager.canShowAd())
        assertEquals(200L, manager.remainingCooldownSeconds())

        fakeClockMs += 199_999L
        assertFalse(manager.canShowAd())
        assertEquals(1L, manager.remainingCooldownSeconds())

        fakeClockMs += 1L
        assertTrue(manager.canShowAd())
        assertEquals(0L, manager.remainingCooldownSeconds())
    }

    @Test
    fun `interstitial triggers on detail back only after 8 distinct views (Condition A)`() {
        val manager = InterstitialAdManager(
            appContext = null,
            clockMillis = { 10_000L },
        )

        // Duplicate consecutive IDs do not inflate detailViewCount
        manager.onDetailImageViewed("wp_1")
        manager.onDetailImageViewed("wp_1")
        assertEquals(1, manager.detailViewCount)

        for (i in 2..7) {
            manager.onDetailImageViewed("wp_$i")
        }
        assertEquals(7, manager.detailViewCount)

        // Under 8 views: Back navigates without consuming accumulated count
        var navigatedUnderThreshold = false
        manager.onDetailBackClicked(activity = null) {
            navigatedUnderThreshold = true
        }
        assertTrue(navigatedUnderThreshold)
        assertEquals(7, manager.detailViewCount)

        // 8th distinct view + Back triggers interstitial evaluation and resets count
        manager.onDetailImageViewed("wp_8")
        assertEquals(8, manager.detailViewCount)

        var navigatedAtThreshold = false
        manager.onDetailBackClicked(activity = null) {
            navigatedAtThreshold = true
        }
        assertTrue(navigatedAtThreshold)
        assertEquals(0, manager.detailViewCount)
    }

    @Test
    fun `interstitial records wallpaper applied without immediate show and consumes on back (Condition B)`() {
        val manager = InterstitialAdManager(
            appContext = null,
            clockMillis = { 10_000L },
        )

        assertFalse(manager.hasAppliedWallpaper)
        manager.markWallpaperApplied()
        assertTrue(manager.hasAppliedWallpaper)

        var navigatedBack = false
        manager.onDetailBackClicked(activity = null) {
            navigatedBack = true
        }
        assertTrue(navigatedBack)
        assertFalse(manager.hasAppliedWallpaper)
    }

    @Test
    fun `grid feed builder inserts native ad after every 6 wallpapers`() {
        val items = sampleWallpapers(14)
        val entries = buildFeedWithNativeAds(items, interval = AdMobConstants.GRID_NATIVE_AD_INTERVAL)

        // 14 wallpapers -> ads after item 6 (index 5) and item 12 (index 11) => 16 total entries
        assertEquals(16, entries.size)
        assertTrue(entries[6] is WallpaperGridEntry.NativeAdSlot)
        assertTrue(entries[13] is WallpaperGridEntry.NativeAdSlot)

        val item7 = entries[7] as WallpaperGridEntry.WallpaperItem
        assertEquals(6, item7.originalIndex)
        assertEquals("wp_6", item7.wallpaper.id)
    }

    @Test
    fun `detail pager builder inserts full page native ad every 5 wallpapers and maps index`() {
        val items = sampleWallpapers(12)
        val entries = buildDetailPagerEntries(items, interval = AdMobConstants.DETAIL_PAGER_NATIVE_AD_INTERVAL)

        // 12 wallpapers -> ads after 5th and 10th => 14 total pages
        assertEquals(14, entries.size)
        assertTrue(entries[5] is DetailPagerEntry.NativeAdPage)
        assertTrue(entries[11] is DetailPagerEntry.NativeAdPage)

        // Wallpaper index 5 (6th wallpaper) should land on pager position 6 (right after 1st ad at index 5)
        assertEquals(6, mapWallpaperIndexToPagerPosition(entries, 5))
        // Wallpaper index 10 (11th wallpaper) should land on pager position 12 (right after 2nd ad at index 11)
        assertEquals(12, mapWallpaperIndexToPagerPosition(entries, 10))
    }

    @Test
    fun `anime 200 progressive ad positions expand 6, 8, 10, 12 without trailing orphan`() {
        val positions = calculateProgressiveAdPositions(totalItems = 200)
        assertEquals(
            listOf(6, 14, 24, 36, 50, 66, 84, 104, 126, 150, 176),
            positions,
        )

        val entries = buildCategoriesWithProgressiveAds(AnimeCatalog.allTwoHundred)
        assertEquals(200 + positions.size, entries.size)
        // First Native Ad after 6 items sits at index 6
        assertTrue(entries[6] is CategoryGridEntry.NativeAdSlot)
        assertEquals(6, (entries[6] as CategoryGridEntry.NativeAdSlot).afterItemCount)
        // Second Native Ad after 8 more items (14 categories + 1 prior ad = index 15)
        assertTrue(entries[15] is CategoryGridEntry.NativeAdSlot)
        assertEquals(14, (entries[15] as CategoryGridEntry.NativeAdSlot).afterItemCount)
        // Third Native Ad after 10 more items (24 categories + 2 prior ads = index 26)
        assertTrue(entries[26] is CategoryGridEntry.NativeAdSlot)
        assertEquals(24, (entries[26] as CategoryGridEntry.NativeAdSlot).afterItemCount)

        // Filtered list of <= 6 categories shows 0 ads
        val shortEntries = buildCategoriesWithProgressiveAds(AnimeCatalog.allTwoHundred.take(6))
        assertEquals(6, shortEntries.size)
        assertTrue(shortEntries.none { it is CategoryGridEntry.NativeAdSlot })
    }

    @Test
    fun `native ad buffer preloads 3 ads, refills on pop, and destroys cleared or expired ads`() {
        var fakeNow = 1_000L
        var nextId = 0
        val destroyedAds = mutableListOf<String>()
        val pendingLoads = ArrayDeque<Pair<(String) -> Unit, () -> Unit>>()

        val buffer = NativeAdBuffer<String>(
            bufferSize = 3,
            maxAgeMs = 50L * 60L * 1000L,
            clockMillis = { fakeNow },
            onDestroy = { ad -> destroyedAds += ad },
            startLoad = { onLoaded, onFailed ->
                pendingLoads.addLast(onLoaded to onFailed)
            },
        )

        // Preload initiates exactly 3 background loads
        buffer.preloadBuffer()
        assertEquals(3, pendingLoads.size)
        assertEquals(0, buffer.bufferedCount)

        repeat(3) {
            pendingLoads.removeFirst().first("ad_${++nextId}")
        }
        assertEquals(3, buffer.bufferedCount)
        assertEquals(0, pendingLoads.size)

        // Instant pop returns ad_1 immediately and triggers 1 background refill
        val popped = buffer.popAd()
        assertEquals("ad_1", popped)
        assertEquals(2, buffer.bufferedCount)
        assertEquals(1, pendingLoads.size)

        // Refill brings buffer back up to 3
        pendingLoads.removeFirst().first("ad_${++nextId}")
        assertEquals(3, buffer.bufferedCount)

        // Advance clock past 50-min expiry -> expired buffered ads are destroyed on next check
        fakeNow += 51L * 60L * 1000L
        assertEquals(0, buffer.bufferedCount)
        assertEquals(listOf("ad_2", "ad_3", "ad_4"), destroyedAds)

        // Load into buffer again, then clear -> buffered + late in-flight ads must both be destroyed
        buffer.preloadBuffer()
        assertEquals(3, pendingLoads.size)
        pendingLoads.removeFirst().first("ad_${++nextId}")
        assertEquals(1, buffer.bufferedCount)

        buffer.clear()
        assertTrue(destroyedAds.contains("ad_5"))
        // Remaining 2 in-flight loads finishing after clear() must also be destroyed cleanly
        while (pendingLoads.isNotEmpty()) {
            pendingLoads.removeFirst().first("ad_${++nextId}")
        }
        assertTrue(destroyedAds.contains("ad_6"))
        assertTrue(destroyedAds.contains("ad_7"))
        assertNull(buffer.popAd()) // pop after clear restarts buffer if needed, currently empty
    }

    @Test
    fun `native ad buffer waiter receives ad when empty and handles cancellation cleanly`() {
        val destroyedAds = mutableListOf<String>()
        val pendingLoads = ArrayDeque<Pair<(String) -> Unit, () -> Unit>>()
        var nextId = 0

        val buffer = NativeAdBuffer<String>(
            bufferSize = 2,
            onDestroy = { ad -> destroyedAds += ad },
            startLoad = { onLoaded, onFailed ->
                pendingLoads.addLast(onLoaded to onFailed)
            },
        )

        var receivedAd: String? = null
        val cancelWaiter = buffer.requestAd(
            onLoaded = { receivedAd = it },
            onFailed = {},
        )
        assertNull(receivedAd)
        assertEquals(2, pendingLoads.size)

        // Cancel waiter before load arrives -> ad goes into buffer instead of leaked
        cancelWaiter()
        pendingLoads.removeFirst().first("ad_${++nextId}")
        assertNull(receivedAd)
        assertEquals(1, buffer.bufferedCount)

        // Next request immediately gets the buffered ad synchronously
        buffer.requestAd(
            onLoaded = { receivedAd = it },
            onFailed = {},
        )
        assertNotNull(receivedAd)
        assertEquals("ad_1", receivedAd)
        assertTrue(destroyedAds.isEmpty())
    }

    @Test
    fun `native ad buffer notifies all active waiters on total load failure without hanging`() {
        val pendingLoads = ArrayDeque<Pair<(String) -> Unit, () -> Unit>>()
        val buffer = NativeAdBuffer<String>(
            bufferSize = 2,
            onDestroy = {},
            startLoad = { onLoaded, onFailed ->
                pendingLoads.addLast(onLoaded to onFailed)
            },
        )

        var failed1 = 0
        var failed2 = 0
        var failed3 = 0
        var canceledFailed = 0

        buffer.requestAd(onLoaded = {}, onFailed = { failed1++ })
        val cancelMiddle = buffer.requestAd(onLoaded = {}, onFailed = { canceledFailed++ })
        buffer.requestAd(onLoaded = {}, onFailed = { failed2++ })
        buffer.requestAd(onLoaded = {}, onFailed = { failed3++ })
        cancelMiddle()

        // First load fails while another is still in-flight -> waiters keep waiting
        assertEquals(2, pendingLoads.size)
        pendingLoads.removeFirst().second()
        assertEquals(0, failed1)
        assertEquals(0, failed2)
        assertEquals(0, failed3)

        // Final in-flight load fails -> ALL non-canceled waiters must receive onFailed()
        pendingLoads.removeFirst().second()
        assertEquals(1, failed1)
        assertEquals(1, failed2)
        assertEquals(1, failed3)
        assertEquals(0, canceledFailed)
    }

    @Test
    fun `admob defaults protect show rate and enforce safe interstitial cooldown`() {
        assertEquals(1, AdMobConstants.NATIVE_AD_BUFFER_SIZE)
        assertTrue(AdMobConstants.INTERSTITIAL_COOLDOWN_MS >= 180_000L)
        assertTrue(AdMobConstants.DETAIL_VIEW_BACK_THRESHOLD >= 5)
    }
}
