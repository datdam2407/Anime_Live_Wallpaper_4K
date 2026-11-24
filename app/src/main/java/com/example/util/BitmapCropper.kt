package com.example.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import coil.ImageLoader
import com.example.data.remote.NetworkModule
import com.example.domain.model.WallpaperTarget
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Memory-safe, progress-tracked Static Wallpaper Setter for mobile screens:
 * 1. Streams the wallpaper image into `cacheDir/apply_wallpaper_cache` via OkHttp with explicit
 *    timeouts and real-time download progress (reusing cached files instantaneously on retry/re-apply).
 * 2. Measures the device's physical screen dimensions via WindowMetrics / DisplayMetrics and decodes
 *    the local file using `BitmapFactory.Options.inSampleSize` so even 4K/8K sources never cause
 *    OutOfMemoryError.
 * 3. Center-crops and scales cleanly to exact screen dimensions and applies via `WallpaperManager`
 *    on `Dispatchers.IO`, with safe OEM fallback for combined Home + Lock screen targets.
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
        onProgress: (percent: Int, statusLabel: String) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(4, "Connecting to wallpaper server…")

            val localFile = try {
                downloadToCache(
                    url = imageUrl,
                    onDownloadPercent = { dlPercent ->
                        val mapped = (5 + (dlPercent * 0.65f).roundToInt()).coerceIn(5, 70)
                        onProgress(mapped, "Downloading wallpaper… $dlPercent%")
                    },
                )
            } catch (primaryErr: Exception) {
                runCatching { Log.w(TAG, "Primary download failed ($imageUrl): ${primaryErr.message}", primaryErr) }
                val fallback = fallbackThumbnailUrl?.takeIf { it.isNotBlank() && it != imageUrl }
                    ?: throw IOException(
                        "Unable to download wallpaper (${primaryErr.localizedMessage ?: "network timeout"}). Please try again.",
                        primaryErr,
                    )
                onProgress(20, "Primary server slow — loading backup copy…")
                downloadToCache(
                    url = fallback,
                    onDownloadPercent = { dlPercent ->
                        val mapped = (20 + (dlPercent * 0.50f).roundToInt()).coerceIn(20, 70)
                        onProgress(mapped, "Downloading backup image… $dlPercent%")
                    },
                )
            }

            onProgress(76, "Optimizing resolution for your screen…")
            val (screenWidth, screenHeight) = getRealScreenSize()
            val decodedBitmap = decodeSampledBitmapFromFile(
                file = localFile,
                reqWidth = screenWidth,
                reqHeight = screenHeight,
            ) ?: run {
                // Remove corrupt cached file so the next attempt fetches cleanly
                runCatching { localFile.delete() }
                error("Could not decode downloaded wallpaper image. Please try again.")
            }

            var croppedBitmap: Bitmap? = null
            try {
                onProgress(86, "Applying to ${target.label}…")
                croppedBitmap = centerCropToScreen(
                    source = decodedBitmap,
                    targetWidth = screenWidth,
                    targetHeight = screenHeight,
                )
                applyBitmapToWallpaperManager(croppedBitmap, target)
                onProgress(100, "Wallpaper applied!")
            } finally {
                if (croppedBitmap != null && croppedBitmap !== decodedBitmap && !croppedBitmap.isRecycled) {
                    croppedBitmap.recycle()
                }
                if (!decodedBitmap.isRecycled) {
                    decodedBitmap.recycle()
                }
            }
        }
    }

    suspend fun setStaticWallpaperFromUri(
        imageUri: Uri,
        target: WallpaperTarget,
        onProgress: (percent: Int, statusLabel: String) -> Unit = { _, _ -> },
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            onProgress(15, "Reading selected image…")
            val (screenWidth, screenHeight) = getRealScreenSize()
            val decodedBitmap = decodeSampledBitmapFromUri(
                uri = imageUri,
                reqWidth = screenWidth,
                reqHeight = screenHeight,
            ) ?: error("Unable to load selected photo. Please try another image.")

            var croppedBitmap: Bitmap? = null
            try {
                onProgress(75, "Optimizing resolution for your screen…")
                croppedBitmap = centerCropToScreen(
                    source = decodedBitmap,
                    targetWidth = screenWidth,
                    targetHeight = screenHeight,
                )
                onProgress(88, "Applying to ${target.label}…")
                applyBitmapToWallpaperManager(croppedBitmap, target)
                onProgress(100, "Wallpaper applied!")
            } finally {
                if (croppedBitmap != null && croppedBitmap !== decodedBitmap && !croppedBitmap.isRecycled) {
                    croppedBitmap.recycle()
                }
                if (!decodedBitmap.isRecycled) {
                    decodedBitmap.recycle()
                }
            }
        }
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
                                // Unknown Content-Length: smoothly advance based on KB streamed
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

    private fun decodeSampledBitmapFromFile(
        file: File,
        reqWidth: Int,
        reqHeight: Int,
    ): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            return null
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                srcWidth = boundsOpts.outWidth,
                srcHeight = boundsOpts.outHeight,
                reqWidth = reqWidth,
                reqHeight = reqHeight,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }
        return try {
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        } catch (_: OutOfMemoryError) {
            // Ultra-safe OOM fallback: double sample size and retry once
            decodeOpts.inSampleSize = (decodeOpts.inSampleSize * 2).coerceAtLeast(2)
            decodeOpts.inPreferredConfig = Bitmap.Config.RGB_565
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        }
    }

    private fun decodeSampledBitmapFromUri(
        uri: Uri,
        reqWidth: Int,
        reqHeight: Int,
    ): Bitmap? {
        val boundsOpts = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        } ?: return null
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            return null
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                srcWidth = boundsOpts.outWidth,
                srcHeight = boundsOpts.outHeight,
                reqWidth = reqWidth,
                reqHeight = reqHeight,
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
    }

    private fun applyBitmapToWallpaperManager(
        bitmap: Bitmap,
        target: WallpaperTarget,
    ) {
        val wallpaperManager = WallpaperManager.getInstance(context)
        if (!wallpaperManager.isSetWallpaperAllowed) {
            error("Setting wallpaper is currently restricted by device policy.")
        }

        when (target) {
            WallpaperTarget.HOME -> {
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
            }
            WallpaperTarget.LOCK -> {
                wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            }
            WallpaperTarget.BOTH -> {
                try {
                    wallpaperManager.setBitmap(
                        bitmap,
                        null,
                        true,
                        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                    )
                } catch (combinedErr: Exception) {
                    // Some OEM ROMs (including certain MIUI/HyperOS builds) reject combined flags;
                    // apply Home and Lock sequentially as a safe fallback.
                    runCatching { Log.w(TAG, "Combined setBitmap failed, falling back to sequential flags", combinedErr) }
                    wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                    runCatching {
                        wallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
                    }
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
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay?.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

        // Normalize to portrait orientation & cap at 2K mobile bounds (1440x3200) so
        // WallpaperManager Binder transfers stay fast and never trigger TransactionTooLarge/OOM.
        val rawShort = minOf(rawPair.first, rawPair.second).coerceIn(720, MAX_WALLPAPER_SHORT_EDGE_PX)
        val rawLong = maxOf(rawPair.first, rawPair.second).coerceIn(1280, MAX_WALLPAPER_LONG_EDGE_PX)
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
        private const val MAX_WALLPAPER_SHORT_EDGE_PX = 1440
        private const val MAX_WALLPAPER_LONG_EDGE_PX = 3200

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

            if (kotlin.math.abs(srcAspect - targetAspect) < 0.01f && srcW == outW && srcH == outH) {
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
