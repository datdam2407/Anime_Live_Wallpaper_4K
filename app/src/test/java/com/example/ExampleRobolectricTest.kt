package com.example


import android.app.WallpaperInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.service.wallpaper.WallpaperService
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.SettingsDataStore
import com.example.data.remote.WallhavenRateLimiter
import com.example.domain.model.AnimeCatalog
import com.example.service.AnimeLiveWallpaperService
import com.example.service.DelogoEffect
import com.example.util.BitmapCropper
import com.example.util.LiveDownloadState
import com.example.util.LiveWallpaperHelper
import com.example.util.MediaDownloadManager
import com.kuroanimelivewallpapers.app.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowWallpaperManager
import org.robolectric.util.ReflectionHelpers


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {


    @Test
    fun `read app name string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Kuro: Anime Live Wallpaper 4K", appName)
    }


    @Test
    fun `catalog contains all 200 curated anime series`() {
        assertEquals(200, AnimeCatalog.allTwoHundred.size)
        assertEquals(200, AnimeCatalog.allTwoHundred.map { it.id }.distinct().size)
        assertEquals(200, AnimeCatalog.allTwoHundred.map { it.title }.distinct().size)
    }


    @Test
    fun `bitmap cropper can crop same source bitmap repeatedly without recycling it`() {
        val wide4k = Bitmap.createBitmap(3840, 2160, Bitmap.Config.ARGB_8888)


        val firstCrop = BitmapCropper.centerCropToScreen(wide4k, 1080, 2400)
        assertEquals(1080, firstCrop.width)
        assertEquals(2400, firstCrop.height)
        assertFalse(wide4k.isRecycled)


        // Simulates second tap seconds later (e.g. Home & Lock Screen) on the same cached bitmap
        val secondCrop = BitmapCropper.centerCropToScreen(wide4k, 1080, 2400)
        assertEquals(1080, secondCrop.width)
        assertEquals(2400, secondCrop.height)
        assertFalse(wide4k.isRecycled)
    }


    @Test
    fun `live mp4 download alternates A and B files with progress and deletes previous slot`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeMp4Bytes = ByteArray(24 * 1024) { (it % 127).toByte() }
        val mockClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(fakeMp4Bytes.toResponseBody("video/mp4".toMediaType()))
                    .build()
            }
            .build()


        val manager = MediaDownloadManager(context, mockClient)


        val firstStates = manager.downloadLiveWallpaper("https://example.com/video1.mp4").toList()
        val firstSuccess = firstStates.filterIsInstance<LiveDownloadState.Success>().single()
        val firstProgressValues = firstStates.filterIsInstance<LiveDownloadState.Downloading>().map { it.progress }
        assertTrue(firstProgressValues.contains(0))
        assertTrue(firstProgressValues.contains(100))
        assertEquals(MediaDownloadManager.FILE_SLOT_B, firstSuccess.file.name)
        assertTrue(firstSuccess.file.exists())
        assertEquals(firstSuccess.file.name, MediaDownloadManager.getActiveLiveVideoFile(context).name)


        // Second download must switch to slot A and delete slot B so ExoPlayer never reads a truncated file
        val secondStates = manager.downloadLiveWallpaper("https://example.com/video2.mp4").toList()
        val secondSuccess = secondStates.filterIsInstance<LiveDownloadState.Success>().single()
        assertEquals(MediaDownloadManager.FILE_SLOT_A, secondSuccess.file.name)
        assertTrue(secondSuccess.file.exists())
        assertFalse(firstSuccess.file.exists())
        assertEquals(secondSuccess.file.name, MediaDownloadManager.getActiveLiveVideoFile(context).name)
    }


    @Test
    fun `delogo effect defaults cover bottom watermark band validly`() {
        val effect = DelogoEffect()
        assertTrue(effect.boxLeft < effect.boxRight)
        assertTrue(effect.boxBottom < effect.boxTop)
        assertEquals(0.20f, effect.boxLeft, 0.0001f)
        assertEquals(0.80f, effect.boxRight, 0.0001f)
        assertEquals(0.015f, effect.boxBottom, 0.0001f)
        assertEquals(0.055f, effect.boxTop, 0.0001f)


        assertTrue(
            DelogoEffect.isEmulatorDevice(
                fingerprint = "google/sdk_gphone64_x86_64/emu64xa:15/AP31/123:userdebug/dev-keys",
                model = "sdk_gphone64_x86_64",
                hardware = "ranchu",
            ),
        )
        assertFalse(
            DelogoEffect.isEmulatorDevice(
                fingerprint = "google/husky/husky:15/AP3A/123:user/release-keys",
                model = "Pixel 8 Pro",
                manufacturer = "Google",
                brand = "google",
                device = "husky",
                product = "husky",
                hardware = "husky",
            ),
        )
        // Must execute safely against runtime Build props under Robolectric without throwing
        DelogoEffect.isSupportedOnDevice()
    }


    @Test
    fun `live wallpaper helper detects when anime service is already active`() {
        ReflectionHelpers.setStaticField(ShadowWallpaperManager::class.java, "wallpaperInfo", null)
        val context = ApplicationProvider.getApplicationContext<Context>()


        // Initially no live wallpaper set
        assertFalse(LiveWallpaperHelper.isAnimeLiveWallpaperActive(context))


        val resolveInfo = context.packageManager.queryIntentServices(
            Intent(WallpaperService.SERVICE_INTERFACE),
            PackageManager.GET_META_DATA,
        ).first { it.serviceInfo.name == AnimeLiveWallpaperService::class.java.name }


        val wallpaperInfo = WallpaperInfo(context, resolveInfo)
        ReflectionHelpers.setStaticField(ShadowWallpaperManager::class.java, "wallpaperInfo", wallpaperInfo)


        assertTrue(LiveWallpaperHelper.isAnimeLiveWallpaperActive(context))


        assertTrue(
            LiveWallpaperHelper.matchesComponent(
                infoPackage = context.packageName,
                infoService = AnimeLiveWallpaperService::class.java.name,
                expectedPackage = context.packageName,
                expectedService = AnimeLiveWallpaperService::class.java.name,
            ),
        )
        assertFalse(
            LiveWallpaperHelper.matchesComponent(
                infoPackage = "com.other.wallpaper",
                infoService = "com.other.wallpaper.OtherService",
                expectedPackage = context.packageName,
                expectedService = AnimeLiveWallpaperService::class.java.name,
            ),
        )
        ReflectionHelpers.setStaticField(ShadowWallpaperManager::class.java, "wallpaperInfo", null)
    }


    @Test
    fun `settings datastore updates active live timestamp on every wallpaper swap`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = SettingsDataStore(context)


        store.setActiveLiveWallpaper("/data/user/0/app/files/wallpaper_b.mp4", "Luffy")
        val first = store.settingsFlow.first()
        assertEquals("/data/user/0/app/files/wallpaper_b.mp4", first.activeLiveWallpaperPath)
        assertEquals("Luffy", first.activeLiveWallpaperTitle)
        assertTrue(first.activeLiveUpdatedAt > 0L)


        store.setActiveLiveWallpaper("/data/user/0/app/files/wallpaper_a.mp4", "Zoro")
        val second = store.settingsFlow.first()
        assertEquals("/data/user/0/app/files/wallpaper_a.mp4", second.activeLiveWallpaperPath)
        assertEquals("Zoro", second.activeLiveWallpaperTitle)
        assertTrue(second.activeLiveUpdatedAt >= first.activeLiveUpdatedAt)
    }


    @Test
    fun `hasSelectedLiveWallpaper returns false until non-empty live mp4 exists`() {
        val context = ApplicationProvider.getApplicationContext<Context>()


        assertFalse(LiveWallpaperHelper.hasSelectedLiveWallpaper(context, null))
        assertFalse(LiveWallpaperHelper.hasSelectedLiveWallpaper(context, "   "))


        val emptyFile = java.io.File(context.filesDir, "empty_live.mp4").apply {
            writeBytes(ByteArray(0))
        }
        assertFalse(LiveWallpaperHelper.hasSelectedLiveWallpaper(context, emptyFile.absolutePath))


        val missingPath = java.io.File(context.filesDir, "missing_live.mp4").absolutePath
        assertFalse(LiveWallpaperHelper.hasSelectedLiveWallpaper(context, missingPath))


        val validFile = java.io.File(context.filesDir, "valid_live.mp4").apply {
            writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        }
        assertTrue(LiveWallpaperHelper.hasSelectedLiveWallpaper(context, validFile.absolutePath))
    }


    @Test
    fun `getNextLiveWallpaperComponent alternates between primary and secondary services`() {
        ReflectionHelpers.setStaticField(ShadowWallpaperManager::class.java, "wallpaperInfo", null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val primary = android.content.ComponentName(context, AnimeLiveWallpaperService::class.java)
        val alternate = android.content.ComponentName(
            context,
            com.example.service.AnimeLiveWallpaperAlternateService::class.java,
        )


        // Initially no live wallpaper set -> primary
        assertEquals(primary, LiveWallpaperHelper.getNextLiveWallpaperComponent(context))


        // When primary is active -> must switch to alternate so system destination popup shows again
        assertEquals(
            alternate,
            LiveWallpaperHelper.chooseAlternateComponent(
                homeServiceName = primary.className,
                lockServiceName = primary.className,
                primaryComponent = primary,
                alternateComponent = alternate,
            ),
        )


        // When alternate is active -> must switch back to primary
        assertEquals(
            primary,
            LiveWallpaperHelper.chooseAlternateComponent(
                homeServiceName = alternate.className,
                lockServiceName = alternate.className,
                primaryComponent = primary,
                alternateComponent = alternate,
            ),
        )


        // Live ShadowWallpaperManager check when primary is active
        val resolveInfo = context.packageManager.queryIntentServices(
            Intent(WallpaperService.SERVICE_INTERFACE),
            PackageManager.GET_META_DATA,
        ).first { it.serviceInfo.name == AnimeLiveWallpaperService::class.java.name }
        val wallpaperInfo = WallpaperInfo(context, resolveInfo)
        ReflectionHelpers.setStaticField(ShadowWallpaperManager::class.java, "wallpaperInfo", wallpaperInfo)


        assertEquals(alternate, LiveWallpaperHelper.getNextLiveWallpaperComponent(context))
        assertTrue(LiveWallpaperHelper.isAnimeLiveWallpaperActive(context))
        ReflectionHelpers.setStaticField(ShadowWallpaperManager::class.java, "wallpaperInfo", null)
    }


    @Test
    fun `wasLiveWallpaperApplied returns false on cancel and true on confirmation`() {
        ReflectionHelpers.setStaticField(ShadowWallpaperManager::class.java, "wallpaperInfo", null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val alternate = android.content.ComponentName(
            context,
            com.example.service.AnimeLiveWallpaperAlternateService::class.java,
        )


        // Canceled without switching active wallpaper -> false (no success notification)
        assertFalse(
            LiveWallpaperHelper.wasLiveWallpaperApplied(
                context = context,
                resultCode = android.app.Activity.RESULT_CANCELED,
                expectedComponent = alternate,
            ),
        )


        // Confirmed with RESULT_OK -> true
        assertTrue(
            LiveWallpaperHelper.wasLiveWallpaperApplied(
                context = context,
                resultCode = android.app.Activity.RESULT_OK,
                expectedComponent = alternate,
            ),
        )


        // Even if an OEM returns RESULT_CANCELED, if expectedComponent actually became active -> true
        val resolveInfo = context.packageManager.queryIntentServices(
            Intent(WallpaperService.SERVICE_INTERFACE),
            PackageManager.GET_META_DATA,
        ).first { it.serviceInfo.name == alternate.className }
        val wallpaperInfo = WallpaperInfo(context, resolveInfo)
        ReflectionHelpers.setStaticField(ShadowWallpaperManager::class.java, "wallpaperInfo", wallpaperInfo)


        assertTrue(
            LiveWallpaperHelper.wasLiveWallpaperApplied(
                context = context,
                resultCode = android.app.Activity.RESULT_CANCELED,
                expectedComponent = alternate,
            ),
        )
        ReflectionHelpers.setStaticField(ShadowWallpaperManager::class.java, "wallpaperInfo", null)
    }


    @Test
    fun `trending topics include 10 curated anime with exact wallhaven query presets`() {
        assertEquals(10, AnimeCatalog.trendingTopics.size)


        val jojo = AnimeCatalog.findTrendingTopic("JoJo's Bizarre Adventure")
        assertEquals("JoJo's Bizarre Adventure", jojo?.query)
        assertEquals(3, jojo?.initialPage)


        val reZero = AnimeCatalog.findTrendingTopic("Re:Zero − Starting Life in Another World")
        assertEquals("Re:Zero", reZero?.query)
        assertEquals(2, reZero?.initialPage)


        val apothecary = AnimeCatalog.findTrendingTopic("The Apothecary Diaries")
        assertEquals("The Apothecary", apothecary?.query)
        assertEquals(2, apothecary?.initialPage)


        val jjk = AnimeCatalog.findTrendingTopic("Jujutsu Kaisen")
        assertEquals("110", jjk?.categories)
        assertEquals("100", jjk?.purity)
        assertEquals("relevance", jjk?.sorting)
        assertEquals("desc", jjk?.order)


        val daemons = AnimeCatalog.findTrendingTopic("Daemons of the Shadow Realm (Yomi no Tsugai)")
        assertEquals("Daemons of the Shadow Realm (Yomi no Tsugai)", daemons?.query)
        assertEquals("110", daemons?.categories)
    }


    @Test
    fun `wallhaven rate limiter caps burst requests under 45 per minute and resets after window`() = runTest {
        var fakeNow = 100_000L
        val limiter = WallhavenRateLimiter(
            maxRequestsPerWindow = 3,
            windowMillis = 60_000L,
            minSpacingMillis = 0L,
            defaultCooldownMillis = 45_000L,
            clock = { fakeNow },
        )


        assertTrue(limiter.tryAcquire() is WallhavenRateLimiter.AcquireResult.Allowed)
        assertTrue(limiter.tryAcquire() is WallhavenRateLimiter.AcquireResult.Allowed)
        assertTrue(limiter.tryAcquire() is WallhavenRateLimiter.AcquireResult.Allowed)


        // 4th request inside same 60s window must be blocked cleanly instead of hitting Wallhaven 429
        val blocked = limiter.tryAcquire()
        assertTrue(blocked is WallhavenRateLimiter.AcquireResult.RateLimited)
        assertEquals(0, limiter.remainingQuota())


        // Advance past 60s sliding window -> quota refills
        fakeNow += 60_001L
        assertEquals(3, limiter.remainingQuota())
        assertTrue(limiter.tryAcquire() is WallhavenRateLimiter.AcquireResult.Allowed)


        // Server 429 response triggers cooldown
        limiter.recordServerRateLimit(retryAfterSeconds = 30L)
        val after429 = limiter.tryAcquire()
        assertTrue(after429 is WallhavenRateLimiter.AcquireResult.RateLimited)
        assertTrue((after429 as WallhavenRateLimiter.AcquireResult.RateLimited).fromServer429)


        fakeNow += 30_001L
        assertTrue(limiter.tryAcquire() is WallhavenRateLimiter.AcquireResult.Allowed)
    }


    @Test
    fun `wallhaven rate limiter triggers breather distraction prompt on 40th request in 60s window`() = runTest {
        var fakeNow = 500_000L
        val limiter = WallhavenRateLimiter(
            maxRequestsPerWindow = 43,
            distractionThreshold = 40,
            windowMillis = 60_000L,
            minSpacingMillis = 0L,
            clock = { fakeNow },
        )


        repeat(39) {
            assertTrue(limiter.tryAcquire() is WallhavenRateLimiter.AcquireResult.Allowed)
            assertEquals(null, limiter.distractionPrompt.value)
        }


        // Exact 40th request inside 60s window triggers distraction popup state
        assertTrue(limiter.tryAcquire() is WallhavenRateLimiter.AcquireResult.Allowed)
        val firstPrompt = limiter.distractionPrompt.value
        assertTrue(firstPrompt != null)
        assertEquals(40, firstPrompt?.requestCount)


        // Dismissing clears UI state and 41st request in same window does not spam a second popup
        limiter.dismissDistractionPrompt()
        assertEquals(null, limiter.distractionPrompt.value)
        assertTrue(limiter.tryAcquire() is WallhavenRateLimiter.AcquireResult.Allowed)
        assertEquals(null, limiter.distractionPrompt.value)


        // Once 60s window slides past, hitting 40 requests again triggers a fresh fun-fact prompt
        fakeNow += 60_001L
        repeat(40) {
            assertTrue(limiter.tryAcquire() is WallhavenRateLimiter.AcquireResult.Allowed)
        }
        val secondPrompt = limiter.distractionPrompt.value
        assertTrue(secondPrompt != null)
        assertEquals(40, secondPrompt?.requestCount)
    }
}