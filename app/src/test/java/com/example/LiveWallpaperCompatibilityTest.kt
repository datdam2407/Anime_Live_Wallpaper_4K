package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.LiveWallpaperDownloader
import com.example.service.AnimeLiveWallpaperService
import com.example.service.DeviceHardwareProfile
import com.example.service.LiveWallpaperStaticFallback
import com.example.service.SoftwarePreferredMediaCodecSelector
import com.example.service.VideoCompatibilityInspector
import com.example.service.VideoCompatibilityReport
import com.example.util.LiveWallpaperHelper
import com.example.util.MediaDownloadManager
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class LiveWallpaperCompatibilityTest {

    @OptIn(UnstableApi::class)
    @Test
    fun `rejects cacheDir and remote URLs and heals migrated internal filesDir paths`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val liveDir = File(context.filesDir, LiveWallpaperDownloader.LIVE_WALLPAPERS_DIR).apply { mkdirs() }

        // 1. OS-purgeable cacheDir file MUST be rejected so reboot/cache-cleanup never causes black screen
        val cachedTempMp4 = File(context.cacheDir, "purged_soon.mp4").apply {
            writeBytes(ByteArray(1024) { 0x11 })
        }
        assertFalse(
            VideoCompatibilityInspector.isReliableLocalVideoFile(
                file = cachedTempMp4,
                cacheDir = context.cacheDir,
                externalCacheDir = context.externalCacheDir,
            ),
        )
        assertFalse(LiveWallpaperHelper.hasSelectedLiveWallpaper(context, cachedTempMp4.absolutePath))

        val rejectedCacheResolved = AnimeLiveWallpaperService.resolvePreferredLiveFile(
            configuredPath = cachedTempMp4.absolutePath,
            configuredUpdatedAt = 5_000L,
            prefsActiveFile = cachedTempMp4,
            prefsUpdatedAt = 5_000L,
            filesRoot = context.filesDir,
            cacheDir = context.cacheDir,
            externalCacheDir = context.externalCacheDir,
        )
        assertNull(rejectedCacheResolved)

        // 2. Valid internal filesDir MP4 is accepted and heals /data/data vs /data/user/10 path changes
        val persistentMp4 = File(liveDir, "kuro_hardened_01.mp4").apply {
            writeBytes(
                byteArrayOf(
                    0x00, 0x00, 0x00, 0x18,
                    'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
                    'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                ) + ByteArray(512) { 0x2A },
            )
        }
        assertTrue(VideoCompatibilityInspector.hasValidMp4Signature(persistentMp4))

        val stalePrefixPath = "/data/data/com.kuroanimelivewallpapers.app/files/live_wallpapers/kuro_hardened_01.mp4"
        val healed = AnimeLiveWallpaperService.resolvePreferredLiveFile(
            configuredPath = stalePrefixPath,
            configuredUpdatedAt = 9_000L,
            prefsActiveFile = null,
            prefsUpdatedAt = 0L,
            filesRoot = context.filesDir,
            cacheDir = context.cacheDir,
            externalCacheDir = context.externalCacheDir,
        )
        assertNotNull(healed)
        assertEquals(persistentMp4.absolutePath, healed?.absolutePath)

        // 3. Direct HTTP/HTTPS URLs are strictly rejected in WallpaperService path resolution
        val rejectedHttp = AnimeLiveWallpaperService.resolvePreferredLiveFile(
            configuredPath = "https://cdn.example.r2.dev/kuro_01/full.mp4",
            configuredUpdatedAt = 10_000L,
            prefsActiveFile = null,
            prefsUpdatedAt = 0L,
            filesRoot = null,
            cacheDir = context.cacheDir,
            externalCacheDir = context.externalCacheDir,
        )
        assertNull(rejectedHttp)

        persistentMp4.delete()
        cachedTempMp4.delete()
    }

    @Test
    fun `downloader rejects truncated Content-Length streams and HTML error pages without poisoning cache`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testDir = File(context.filesDir, "dl_integrity_test").apply {
            deleteRecursively()
            mkdirs()
        }

        // 1. HTML captive-portal page returning HTTP 200 must be rejected
        val htmlClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val html = "<!DOCTYPE html><html><body>Gateway Error</body></html>"
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/html; charset=utf-8")
                    .body(html.toByteArray().toResponseBody("text/html".toMediaType()))
                    .build()
            }
            .build()

        val htmlDownloader = LiveWallpaperDownloader(testDir, htmlClient)
        val htmlResult = htmlDownloader.downloadIfNeeded(
            wallpaperId = "captive_test",
            fullUrl = "https://cdn.example.r2.dev/captive_test/full.mp4",
        )
        assertTrue(htmlResult.isFailure)
        assertFalse(htmlDownloader.isDownloaded("captive_test"))

        // 2. Truncated stream (declares 64KB Content-Length, closes after 8KB) must be rejected
        val truncatedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val partialBuffer = Buffer().write(ByteArray(8 * 1024) { 0x33 })
                val body = partialBuffer.asResponseBody("video/mp4".toMediaType(), contentLength = 64L * 1024L)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()

        val truncatedDownloader = LiveWallpaperDownloader(testDir, truncatedClient)
        val truncatedResult = truncatedDownloader.downloadIfNeeded(
            wallpaperId = "truncated_test",
            fullUrl = "https://cdn.example.r2.dev/truncated_test/full.mp4",
        )
        assertTrue(truncatedResult.isFailure)
        assertFalse(truncatedDownloader.isDownloaded("truncated_test"))

        testDir.deleteRecursively()
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `codec compatibility prefers H264 8-bit 1080p and ranks software MediaCodec first on fallback`() {
        val lowRamPowerVrDevice = DeviceHardwareProfile(
            isLowRamDevice = true,
            isConstrainedRamDevice = true,
            totalRamBytes = 1_800L * 1024L * 1024L,
            glEsVersionMajor = 2,
            isHighRiskDecoderSoc = true,
            is32BitOnlyAbi = true,
        )

        // Ideal H.264/AVC Main profile, 8-bit yuv420p, 1080x1920 @ 30fps -> safe to play directly
        val avc1080p8Bit = VideoCompatibilityReport(
            mimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
            width = 1080,
            height = 1920,
            frameRate = 30,
            profile = MediaCodecInfo.CodecProfileLevel.AVCProfileMain,
            isTenBitOrHdr = false,
            hasHardwareDecoderSupport = true,
            hasAnyDecoderSupport = true,
        )
        assertTrue(avc1080p8Bit.isPreferredBaselineOrMainAvc1080p)
        assertFalse(avc1080p8Bit.shouldPreferCompatibilityStream(lowRamPowerVrDevice))
        assertFalse(avc1080p8Bit.shouldPreferSoftwareDecoderFirst(lowRamPowerVrDevice))

        // Risky 4K HEVC 10-bit stream on <=2GB RAM / GLES 2.0 / PowerVR -> prefers <=1080p H.264 compat stream & SW fallback
        val hevc4k10Bit = VideoCompatibilityReport(
            mimeType = MediaFormat.MIMETYPE_VIDEO_HEVC,
            width = 2160,
            height = 3840,
            frameRate = 60,
            profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            isTenBitOrHdr = true,
            hasHardwareDecoderSupport = false,
            hasAnyDecoderSupport = true,
        )
        assertFalse(hevc4k10Bit.isPreferredBaselineOrMainAvc1080p)
        assertTrue(hevc4k10Bit.shouldPreferCompatibilityStream(lowRamPowerVrDevice))
        assertTrue(hevc4k10Bit.shouldPreferSoftwareDecoderFirst(lowRamPowerVrDevice))

        // Verify SoftwarePreferredMediaCodecSelector promotes c2.android / OMX.google ahead of vendor OMX codecs
        val vendorMtk = androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
            /* name = */ "OMX.MTK.VIDEO.DECODER.AVC",
            /* mimeType = */ MediaFormat.MIMETYPE_VIDEO_AVC,
            /* codecMimeType = */ MediaFormat.MIMETYPE_VIDEO_AVC,
            /* capabilities = */ null,
            /* hardwareAccelerated = */ true,
            /* softwareOnly = */ false,
            /* vendor = */ true,
            /* forceDisableAdaptive = */ false,
            /* forceSecure = */ false,
        )
        val vendorPowerVr = androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
            /* name = */ "OMX.IMG.MSVDX.Decoder.AVC",
            /* mimeType = */ MediaFormat.MIMETYPE_VIDEO_AVC,
            /* codecMimeType = */ MediaFormat.MIMETYPE_VIDEO_AVC,
            /* capabilities = */ null,
            /* hardwareAccelerated = */ true,
            /* softwareOnly = */ false,
            /* vendor = */ true,
            /* forceDisableAdaptive = */ false,
            /* forceSecure = */ false,
        )
        val googleSoftware = androidx.media3.exoplayer.mediacodec.MediaCodecInfo.newInstance(
            /* name = */ "c2.android.avc.decoder",
            /* mimeType = */ MediaFormat.MIMETYPE_VIDEO_AVC,
            /* codecMimeType = */ MediaFormat.MIMETYPE_VIDEO_AVC,
            /* capabilities = */ null,
            /* hardwareAccelerated = */ false,
            /* softwareOnly = */ true,
            /* vendor = */ false,
            /* forceDisableAdaptive = */ false,
            /* forceSecure = */ false,
        )

        val ranked = SoftwarePreferredMediaCodecSelector.rankSoftwareDecodersFirst(
            listOf(vendorMtk, vendorPowerVr, googleSoftware),
        )
        assertEquals("c2.android.avc.decoder", ranked.first().name)
        assertEquals(3, ranked.size)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `static first-frame fallback renders non-black preview onto Canvas when video is missing or engine starts cold`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Clear any configured video so engine exercises cold-boot / missing-file fallback
        context.getSharedPreferences("live_wallpaper_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(context.filesDir, MediaDownloadManager.FILE_SLOT_A).delete()
        File(context.filesDir, MediaDownloadManager.FILE_SLOT_B).delete()
        File(context.filesDir, LiveWallpaperDownloader.LIVE_WALLPAPERS_DIR).deleteRecursively()

        val outputBuffer = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        outputBuffer.eraseColor(Color.BLACK)

        val posterBitmap = LiveWallpaperStaticFallback.loadStaticPreviewBitmap(
            context = context,
            videoFile = null,
            targetWidth = 360,
            targetHeight = 640,
            isLowRam = true,
        )
        assertNotNull("Bundled anime night fallback bitmap must load offline", posterBitmap)

        val canvas = Canvas(outputBuffer)
        LiveWallpaperStaticFallback.drawBitmapOrGradientOnCanvas(canvas, posterBitmap)

        // Sample grid of pixels across the Canvas to verify it is NOT a blank black screen
        var nonBlackPixels = 0
        for (y in 20 until 620 step 25) {
            for (x in 20 until 340 step 25) {
                val px = outputBuffer.getPixel(x, y)
                if (Color.red(px) > 4 || Color.green(px) > 4 || Color.blue(px) > 4) {
                    nonBlackPixels++
                }
            }
        }
        assertTrue("Expected non-black rendered fallback frame, got $nonBlackPixels lit samples", nonBlackPixels > 10)

        // Verify null-bitmap gradient fallback also never produces a pure black frame
        val gradientBuffer = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        gradientBuffer.eraseColor(Color.BLACK)
        LiveWallpaperStaticFallback.drawBitmapOrGradientOnCanvas(Canvas(gradientBuffer), null)
        val midPx = gradientBuffer.getPixel(180, 320)
        assertTrue(
            "Expected non-black gradient fallback pixel at center",
            Color.red(midPx) > 8 || Color.green(midPx) > 8 || Color.blue(midPx) > 8,
        )

        // Also exercise WallpaperService.Engine lifecycle transitions without crashing
        val service = Robolectric.buildService(AnimeLiveWallpaperService::class.java).create().get()
        val engine = service.onCreateEngine() as AnimeLiveWallpaperService.ExoWallpaperEngine
        val fakeHolder = object : SurfaceHolder {
            override fun addCallback(callback: SurfaceHolder.Callback?) = Unit
            override fun removeCallback(callback: SurfaceHolder.Callback?) = Unit
            override fun isCreating(): Boolean = false
            @Deprecated("Deprecated in Java")
            override fun setType(type: Int) = Unit
            override fun setFixedSize(width: Int, height: Int) = Unit
            override fun setSizeFromLayout() = Unit
            override fun setFormat(format: Int) = Unit
            override fun setKeepScreenOn(screenOn: Boolean) = Unit
            override fun lockCanvas(): Canvas = Canvas(outputBuffer)
            override fun lockCanvas(dirty: Rect?): Canvas = Canvas(outputBuffer)
            override fun unlockCanvasAndPost(canvas: Canvas?) = Unit
            override fun getSurfaceFrame(): Rect = Rect(0, 0, 360, 640)
            override fun getSurface(): Surface? = null
        }

        engine.onCreate(fakeHolder)
        engine.onSurfaceCreated(fakeHolder)
        engine.onSurfaceChanged(fakeHolder, 1, 360, 640)
        engine.onVisibilityChanged(true)
        assertTrue(engine.isEngineCurrentlyVisible)
        engine.onVisibilityChanged(false)
        assertFalse(engine.isEngineCurrentlyVisible)
        engine.onSurfaceDestroyed(fakeHolder)
        engine.onDestroy()
    }
}
