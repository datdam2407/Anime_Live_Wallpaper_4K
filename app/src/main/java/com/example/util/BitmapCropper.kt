package com.example.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import coil.ImageLoader
import com.example.data.remote.NetworkModule
import com.example.domain.model.WallpaperScaleMode
import com.example.domain.model.WallpaperTarget
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-Native-Resolution, True-to-Original Static Wallpaper Applier for mobile screens:
 * 1. Downloads the FULL native artwork URL into `cacheDir/apply_wallpaper_cache` via OkHttp with
 *    real-time download progress (reusing verified cached files instantaneously on re-apply).
 *    Never silently downgrades to a low-res thumbnail when an original full-res URL is present.
 * 2. Decodes at 1:1 native pixel resolution (`ARGB_8888`, up to OOM-safe 4096px ceiling) so
 *    2K/4K artworks retain maximum sharpness even under Android 11+ launcher wallpaper zoom.
 * 3. Accounts for Android launcher parallax vs. exact physical display bounds (`WindowMetrics` /
 *    `DisplayMetrics`) by calling `suggestDesiredDimensions(screenWidth, screenHeight)` and passing
 *    an exact native-coordinate `visibleCropHint` to `WallpaperManager.setBitmap(...)` — matching
 *    the Detail screen preview without unexpected edge cut-offs or double-resampling blur.
 */
class BitmapCropper(
    private val context: Context,
    @Suppress("unused") private val imageLoader: ImageLoader,
    private val okHttpClient: OkHttpClient = NetworkModule.mediaOkHttpClient,
) {
    private val applyCacheDir: File by lazy {
        File(context.cacheDir, APPLY_CACHE_DIR_NAME).apply { mkdirs() }
    }

    private val applyHttpClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun setStaticWallpaper(
        imageUrl: String,
        target: WallpaperTarget,
        fallbackThumbnailUrl: String? = null,
        scaleMode: WallpaperScaleMode = WallpaperScaleMode.CENTER_CROP,
        onProgress: (percent: Int, statusLabel: String) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fullResUrl = imageUrl.trim().ifEmpty { fallbackThumbnailUrl?.trim().orEmpty() }
            if (fullResUrl.isEmpty()) {
                throw IOException("Missing full-resolution wallpaper URL.")
            }

            onProgress(4, "Connecting to wallpaper server…")
            val localFile = downloadFullResolutionWithRetry(
                url = fullResUrl,
                onDownloadPercent = { dlPercent ->
                    val mapped = (5 + (dlPercent * 0.65f).roundToInt()).coerceIn(5, 70)
                    onProgress(mapped, "Downloading full-res artwork… $dlPercent%")
                },
            )

            onProgress(76, "Decoding full native resolution…")
            val (screenWidth, screenHeight) = getRealScreenSize()
            val decodedBitmap = decodeFullResolutionBitmapFromFile(localFile) ?: run {
                // Remove corrupt cached file so the next attempt downloads cleanly.
                runCatching { localFile.delete() }
                error("Could not decode downloaded wallpaper image. Please try again.")
            }

            applyDecodedBitmap(
                decodedBitmap = decodedBitmap,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                target = target,
                scaleMode = scaleMode,
                onProgress = onProgress,
            )
        }
    }

    suspend fun setStaticWallpaperFromUri(
        imageUri: Uri,
        target: WallpaperTarget,
        scaleMode: WallpaperScaleMode = WallpaperScaleMode.CENTER_CROP,
        onProgress: (percent: Int, statusLabel: String) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(15, "Reading selected image…")
            val (screenWidth, screenHeight) = getRealScreenSize()
            val decodedBitmap = decodeFullResolutionBitmapFromUri(imageUri)
                ?: error("Unable to load selected photo. Please try another image.")

            applyDecodedBitmap(
                decodedBitmap = decodedBitmap,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                target = target,
                scaleMode = scaleMode,
                onProgress = onProgress,
            )
        }
    }

    private fun applyDecodedBitmap(
        decodedBitmap: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        target: WallpaperTarget,
        scaleMode: WallpaperScaleMode,
        onProgress: (percent: Int, statusLabel: String) -> Unit,
    ) {
        var workingBitmap: Bitmap? = null
        try {
            val cropHint: Rect
            val bitmapToApply: Bitmap
            when (scaleMode) {
                WallpaperScaleMode.CENTER_CROP -> {
                    // Preserve 100% of native full-resolution pixels & pass the uncropped source
                    // with an exact centered screen-aspect visibleCropHint. This prevents both
                    // bilinear downscale blur AND aggressive launcher parallax cut-offs.
                    onProgress(84, "Matching exact screen framing…")
                    cropHint = computeVisibleCropHint(
                        srcWidth = decodedBitmap.width,
                        srcHeight = decodedBitmap.height,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                    )
                    bitmapToApply = decodedBitmap
                }

                WallpaperScaleMode.FIT_ORIGINAL -> {
                    // Preserve 100% of the original composition with zero cropped edges onto a
                    // native-resolution canvas matching the exact physical display aspect ratio.
                    onProgress(84, "Fitting full artwork to screen…")
                    val fitted = buildFitOriginalBitmap(
                        source = decodedBitmap,
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                    )
                    workingBitmap = fitted
                    bitmapToApply = fitted
                    cropHint = Rect(0, 0, fitted.width, fitted.height)
                }
            }

            onProgress(90, "Applying native wallpaper to ${target.label}…")
            applyBitmapToWallpaperManager(
                bitmap = bitmapToApply,
                target = target,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                visibleCropHint = cropHint,
            )
            onProgress(100, "Wallpaper applied!")
        } finally {
            if (workingBitmap != null && workingBitmap !== decodedBitmap && !workingBitmap.isRecycled) {
                workingBitmap.recycle()
            }
            if (!decodedBitmap.isRecycled) {
                decodedBitmap.recycle()
            }
        }
    }

    private suspend fun downloadFullResolutionWithRetry(
        url: String,
        onDownloadPercent: (Int) -> Unit,
    ): File {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return downloadToCache(url = url, onDownloadPercent = onDownloadPercent)
            } catch (err: Exception) {
                lastError = err
                runCatching {
                    Log.w(TAG, "Full-res download attempt ${attempt + 1} failed ($url): ${err.message}", err)
                }
                if (attempt == 0) {
                    delay(300L)
                }
            }
        }
        throw IOException(
            "Unable to download full-resolution wallpaper (${lastError?.localizedMessage ?: "network timeout"}). Please try again.",
            lastError,
        )
    }

    private fun downloadToCache(
        url: String,
        onDownloadPercent: (Int) -> Unit,
    ): File {
        val targetFile = File(applyCacheDir, "${sha256Prefix(url)}.img")
        if (targetFile.exists() && targetFile.length() > MIN_VALID_IMAGE_BYTES) {
            targetFile.setLastModified(System.currentTimeMillis())
            onDownloadPercent(100)
            return targetFile
        }

        val tempFile = File(applyCacheDir, "${targetFile.name}.tmp")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            applyHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} while downloading wallpaper")
                }
                val body = response.body ?: throw IOException("Empty response body from wallpaper server")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                var lastReportedPercent = -1

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            val percent = if (totalBytes > 0L) {
                                ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(1, 99)
                            } else {
                                (downloadedBytes / (32 * 1024L)).toInt().coerceIn(10, 92)
                            }
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onDownloadPercent(percent)
                            }
                        }
                        output.flush()
                    }
                }

                if (tempFile.length() <= MIN_VALID_IMAGE_BYTES) {
                    throw IOException("Downloaded wallpaper file is incomplete")
                }

                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                onDownloadPercent(100)
                pruneApplyCacheDir(keepFile = targetFile)
                return targetFile
            }
        } catch (e: Exception) {
            runCatching { tempFile.delete() }
            throw e
        }
    }

    private fun pruneApplyCacheDir(keepFile: File) {
        val files = applyCacheDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".tmp") && it.absolutePath != keepFile.absolutePath }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        files.drop(MAX_CACHED_APPLY_FILES - 1).forEach { stale ->
            runCatching { stale.delete() }
        }
    }

    /**
     * Decodes a downloaded image file at 1:1 full native resolution (up to 4096px edge / 16.7 MP),
     * preserving complete 2K/4K detail without premature downsampling to screen size.
     */
    private fun decodeFullResolutionBitmapFromFile(file: File): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
            val nativeSample = calculateNativeCeilingSampleSize(boundsOpts.outWidth, boundsOpts.outHeight)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = nativeSample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                inMutable = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
            }
            val decoded = try {
                BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            } catch (_: OutOfMemoryError) {
                decodeOpts.inSampleSize = (decodeOpts.inSampleSize * 2).coerceAtLeast(2)
                BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            }
            if (decoded != null) return decoded
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                val source = android.graphics.ImageDecoder.createSource(file)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    val srcW = info.size.width
                    val srcH = info.size.height
                    val longest = max(srcW, srcH)
                    if (longest > MAX_NATIVE_DECODE_EDGE_PX && srcW > 0 && srcH > 0) {
                        val scale = MAX_NATIVE_DECODE_EDGE_PX.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (srcW * scale).roundToInt().coerceAtLeast(1),
                            (srcH * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            }.getOrNull()
        }
        return null
    }

    private fun decodeFullResolutionBitmapFromUri(uri: Uri): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        } ?: return null
        if (boundsOpts.outWidth > 0 && boundsOpts.outHeight > 0) {
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateNativeCeilingSampleSize(boundsOpts.outWidth, boundsOpts.outHeight)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
            }
            val decoded = try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOpts)
                }
            } catch (_: OutOfMemoryError) {
                decodeOpts.inSampleSize = (decodeOpts.inSampleSize * 2).coerceAtLeast(2)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, decodeOpts)
                }
            }
            if (decoded != null) return decoded
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return runCatching {
                val source = android.graphics.ImageDecoder.createSource(context.contentResolver, uri)
                android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                    val srcW = info.size.width
                    val srcH = info.size.height
                    val longest = max(srcW, srcH)
                    if (longest > MAX_NATIVE_DECODE_EDGE_PX && srcW > 0 && srcH > 0) {
                        val scale = MAX_NATIVE_DECODE_EDGE_PX.toFloat() / longest.toFloat()
                        decoder.setTargetSize(
                            (srcW * scale).roundToInt().coerceAtLeast(1),
                            (srcH * scale).roundToInt().coerceAtLeast(1),
                        )
                    }
                }
            }.getOrNull()
        }
        return null
    }

    private fun applyBitmapToWallpaperManager(
        bitmap: Bitmap,
        target: WallpaperTarget,
        screenWidth: Int,
        screenHeight: Int,
        visibleCropHint: Rect?,
    ) {
        val wallpaperManager = WallpaperManager.getInstance(context)
        if (!wallpaperManager.isSetWallpaperAllowed) {
            error("Setting wallpaper is currently restricted by device policy.")
        }

        // Prevent Android launchers from enforcing a wide 2x multi-page parallax surface that
        // aggressively zooms/crops portrait wallpapers on Home Screen.
        if (target == WallpaperTarget.HOME || target == WallpaperTarget.BOTH) {
            runCatching {
                wallpaperManager.suggestDesiredDimensions(screenWidth, screenHeight)
            }.onFailure { err ->
                runCatching { Log.w(TAG, "suggestDesiredDimensions($screenWidth x $screenHeight) ignored: ${err.message}") }
            }
        }

        val validCropHint = visibleCropHint?.takeIf { rect ->
            rect.left >= 0 &&
                rect.top >= 0 &&
                rect.right <= bitmap.width &&
                rect.bottom <= bitmap.height &&
                rect.width() > 0 &&
                rect.height() > 0
        }

        fun applyForFlag(flag: Int) {
            try {
                wallpaperManager.setBitmap(bitmap, validCropHint, true, flag)
            } catch (cropErr: Exception) {
                if (validCropHint == null) throw cropErr
                // Fallback for OEM ROMs that reject explicit visibleCropHint: slice at FULL native
                // cropped pixel resolution (zero downscaling!) and apply cleanly.
                runCatching {
                    Log.w(TAG, "setBitmap with visibleCropHint failed for flag=$flag, retrying native slice", cropErr)
                }
                val nativeSlice = Bitmap.createBitmap(
                    bitmap,
                    validCropHint.left,
                    validCropHint.top,
                    validCropHint.width(),
                    validCropHint.height(),
                )
                try {
                    wallpaperManager.setBitmap(nativeSlice, null, true, flag)
                } finally {
                    if (nativeSlice !== bitmap && !nativeSlice.isRecycled) {
                        nativeSlice.recycle()
                    }
                }
            }
        }

        when (target) {
            WallpaperTarget.HOME -> applyForFlag(WallpaperManager.FLAG_SYSTEM)
            WallpaperTarget.LOCK -> applyForFlag(WallpaperManager.FLAG_LOCK)
            WallpaperTarget.BOTH -> {
                try {
                    applyForFlag(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
                } catch (combinedErr: Exception) {
                    // Some OEM ROMs (including certain MIUI/HyperOS builds) reject combined flags;
                    // apply Home and Lock sequentially as a safe fallback.
                    runCatching { Log.w(TAG, "Combined setBitmap failed, falling back to sequential flags", combinedErr) }
                    applyForFlag(WallpaperManager.FLAG_SYSTEM)
                    runCatching { applyForFlag(WallpaperManager.FLAG_LOCK) }
                }
            }
        }
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val rawPair = if (windowManager == null) {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // maximumWindowMetrics returns the true physical display bounds even in split-screen.
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

        val rawShort = minOf(rawPair.first, rawPair.second).coerceIn(720, MAX_NATIVE_DECODE_EDGE_PX)
        val rawLong = maxOf(rawPair.first, rawPair.second).coerceIn(1280, MAX_NATIVE_DECODE_EDGE_PX)
        return rawShort to rawLong
    }

    private fun sha256Prefix(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "BitmapCropper"
        private const val APPLY_CACHE_DIR_NAME = "apply_wallpaper_cache"
        private const val MAX_CACHED_APPLY_FILES = 8
        private const val MIN_VALID_IMAGE_BYTES = 1024L
        private const val MAX_NATIVE_DECODE_EDGE_PX = 4096
        private const val MAX_NATIVE_DECODE_PIXELS = 4096L * 4096L
        private const val MAX_WALLPAPER_SHORT_EDGE_PX = 1440
        private const val MAX_WALLPAPER_LONG_EDGE_PX = 3200

        /**
         * Preserves 1:1 full native resolution (`inSampleSize = 1`) for all images up to 4K
         * (`<= 4096px` max edge and `<= 16.7 MP`), only downsampling extreme 6K/8K+ sources to
         * prevent OutOfMemoryError.
         */
        fun calculateNativeCeilingSampleSize(srcWidth: Int, srcHeight: Int): Int {
            if (srcWidth <= 0 || srcHeight <= 0) return 1
            var sample = 1
            while (
                (srcWidth / sample) > MAX_NATIVE_DECODE_EDGE_PX ||
                (srcHeight / sample) > MAX_NATIVE_DECODE_EDGE_PX ||
                ((srcWidth / sample).toLong() * (srcHeight / sample).toLong()) > MAX_NATIVE_DECODE_PIXELS
            ) {
                sample *= 2
            }
            return sample.coerceAtLeast(1)
        }

        /**
         * Computes the exact centered [Rect] inside the full-resolution source bitmap matching the
         * device screen's aspect ratio (`screenWidth / screenHeight`) with zero premature downscaling.
         */
        fun computeVisibleCropHint(
            srcWidth: Int,
            srcHeight: Int,
            screenWidth: Int,
            screenHeight: Int,
        ): Rect {
            if (srcWidth <= 0 || srcHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
                return Rect(0, 0, srcWidth.coerceAtLeast(1), srcHeight.coerceAtLeast(1))
            }
            val srcAspect = srcWidth.toFloat() / srcHeight.toFloat()
            val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
            if (abs(srcAspect - screenAspect) < 0.005f) {
                return Rect(0, 0, srcWidth, srcHeight)
            }
            return if (srcAspect > screenAspect) {
                val cropW = (srcHeight * screenAspect).roundToInt().coerceIn(1, srcWidth)
                val left = ((srcWidth - cropW) / 2).coerceAtLeast(0)
                Rect(left, 0, (left + cropW).coerceAtMost(srcWidth), srcHeight)
            } else {
                val cropH = (srcWidth / screenAspect).roundToInt().coerceIn(1, srcHeight)
                val top = ((srcHeight - cropH) / 2).coerceAtLeast(0)
                Rect(0, top, srcWidth, (top + cropH).coerceAtMost(srcHeight))
            }
        }

        /**
         * Places the ENTIRE original artwork uncropped (`0%` edge loss) centered inside a canvas
         * matching the exact device display aspect ratio while preserving the source's native
         * pixel density (up to [MAX_NATIVE_DECODE_EDGE_PX]).
         */
        fun buildFitOriginalBitmap(
            source: Bitmap,
            screenWidth: Int,
            screenHeight: Int,
        ): Bitmap {
            val srcW = source.width
            val srcH = source.height
            if (srcW <= 0 || srcH <= 0 || screenWidth <= 0 || screenHeight <= 0) return source

            val screenAspect = screenWidth.toFloat() / screenHeight.toFloat()
            val srcAspect = srcW.toFloat() / srcH.toFloat()
            if (abs(srcAspect - screenAspect) < 0.005f) {
                return source
            }

            // Choose canvas dimensions large enough to preserve native artwork pixels (and at least
            // physical display pixels), capped cleanly at MAX_NATIVE_DECODE_EDGE_PX.
            var outW: Int
            var outH: Int
            if (srcAspect > screenAspect) {
                outW = max(srcW, screenWidth)
                outH = ceil(outW / screenAspect).toInt()
            } else {
                outH = max(srcH, screenHeight)
                outW = ceil(outH * screenAspect).toInt()
            }

            val maxEdge = max(outW, outH)
            if (maxEdge > MAX_NATIVE_DECODE_EDGE_PX) {
                val clampScale = MAX_NATIVE_DECODE_EDGE_PX.toFloat() / maxEdge.toFloat()
                outW = (outW * clampScale).roundToInt().coerceAtLeast(1)
                outH = (outH * clampScale).roundToInt().coerceAtLeast(1)
            }

            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            // Deep obsidian base + subtle ambient edge backdrop so any letterbox blends naturally.
            canvas.drawColor(Color.rgb(11, 10, 16))

            val bgScale = max(outW.toFloat() / srcW.toFloat(), outH.toFloat() / srcH.toFloat())
            val bgMatrix = Matrix().apply {
                setScale(bgScale, bgScale)
                postTranslate((outW - srcW * bgScale) / 2f, (outH - srcH * bgScale) / 2f)
            }
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = 46
            }
            canvas.drawBitmap(source, bgMatrix, bgPaint)
            canvas.drawColor(Color.argb(170, 11, 10, 16))

            val fgScale = min(outW.toFloat() / srcW.toFloat(), outH.toFloat() / srcH.toFloat())
            val fgW = srcW * fgScale
            val fgH = srcH * fgScale
            val fgMatrix = Matrix().apply {
                setScale(fgScale, fgScale)
                postTranslate((outW - fgW) / 2f, (outH - fgH) / 2f)
            }
            val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            canvas.drawBitmap(source, fgMatrix, fgPaint)
            return output
        }

        fun calculateInSampleSize(
            srcWidth: Int,
            srcHeight: Int,
            reqWidth: Int,
            reqHeight: Int,
        ): Int {
            var inSampleSize = 1
            if (srcHeight > reqHeight || srcWidth > reqWidth) {
                val halfHeight = srcHeight / 2
                val halfWidth = srcWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize.coerceAtLeast(1)
        }

        fun centerCropToScreen(
            source: Bitmap,
            targetWidth: Int,
            targetHeight: Int,
        ): Bitmap {
            val srcW = source.width
            val srcH = source.height
            if (srcW <= 0 || srcH <= 0 || targetWidth <= 0 || targetHeight <= 0) return source

            val outW = targetWidth.coerceAtMost(MAX_WALLPAPER_SHORT_EDGE_PX)
            val outH = targetHeight.coerceAtMost(MAX_WALLPAPER_LONG_EDGE_PX)
            val targetAspect = outW.toFloat() / outH.toFloat()
            val srcAspect = srcW.toFloat() / srcH.toFloat()

            if (abs(srcAspect - targetAspect) < 0.01f && srcW == outW && srcH == outH) {
                return source
            }

            val scale = max(
                outW.toFloat() / srcW.toFloat(),
                outH.toFloat() / srcH.toFloat(),
            )
            val scaledW = srcW * scale
            val scaledH = srcH * scale
            val dx = (outW - scaledW) / 2f
            val dy = (outH - scaledH) / 2f

            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            canvas.drawBitmap(source, matrix, paint)
            return output
        }
    }
}
