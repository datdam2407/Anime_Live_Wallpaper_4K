package com.example.util

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.domain.model.WallpaperTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Core Tech 5.B: Anti-blur 4K/2K/FHD Static Wallpaper Setter.
 * Downloads the original high-res Bitmap via Coil, measures the device's exact physical display
 * dimensions, center-crops the bitmap proportionally to the screen size, and delivers the
 * cropped Bitmap directly to WallpaperManager so the OS never stretches or blurs it.
 *
 * IMPORTANT: Never recycle the source Bitmap returned by Coil's MemoryCache — doing so would
 * corrupt subsequent wallpaper applications (e.g., applying Home Screen, then Both) on the same image.
 */
class BitmapCropper(
    private val context: Context,
    private val imageLoader: ImageLoader,
) {
    suspend fun setStaticWallpaper(
        imageUrl: String,
        target: WallpaperTarget,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceBitmap = loadSoftwareBitmap(imageUrl)
            applyCroppedBitmap(sourceBitmap, target)
        }
    }

    suspend fun setStaticWallpaperFromUri(
        imageUri: Uri,
        target: WallpaperTarget,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceBitmap = loadSoftwareBitmapFromUri(imageUri)
            applyCroppedBitmap(sourceBitmap, target)
        }
    }

    private fun applyCroppedBitmap(
        sourceBitmap: Bitmap,
        target: WallpaperTarget,
    ) {
        val (screenWidth, screenHeight) = getRealScreenSize()

        val croppedBitmap = centerCropToScreen(
            source = sourceBitmap,
            targetWidth = screenWidth,
            targetHeight = screenHeight,
        )

        val wallpaperManager = WallpaperManager.getInstance(context)
        val flags = when (target) {
            WallpaperTarget.HOME -> WallpaperManager.FLAG_SYSTEM
            WallpaperTarget.LOCK -> WallpaperManager.FLAG_LOCK
            WallpaperTarget.BOTH -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
        }

        wallpaperManager.setBitmap(croppedBitmap, null, true, flags)

        // Only recycle newly allocated temporary cropped bitmap, NEVER the cached sourceBitmap
        if (croppedBitmap !== sourceBitmap && !croppedBitmap.isRecycled) {
            croppedBitmap.recycle()
        }
    }

    private suspend fun loadSoftwareBitmap(imageUrl: String): Bitmap {
        val cachedRequest = ImageRequest.Builder(context)
            .data(imageUrl)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()

        val firstResult = imageLoader.execute(cachedRequest)
        val firstDrawable = (firstResult as? SuccessResult)?.drawable
        val firstBitmap = firstDrawable?.toBitmap()

        if (firstBitmap != null && !firstBitmap.isRecycled) {
            return firstBitmap
        }

        // Fallback: bypass memory cache and decode fresh from disk/network
        val freshRequest = ImageRequest.Builder(context)
            .data(imageUrl)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.WRITE_ONLY)
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()

        val freshResult = imageLoader.execute(freshRequest)
        val freshDrawable = (freshResult as? SuccessResult)?.drawable
            ?: error("Unable to load wallpaper image. Please check connection and try again.")
        val freshBitmap = freshDrawable.toBitmap()
        if (freshBitmap.isRecycled) {
            error("Wallpaper bitmap is unavailable. Please try again.")
        }
        return freshBitmap
    }

    private suspend fun loadSoftwareBitmapFromUri(imageUri: Uri): Bitmap {
        val request = ImageRequest.Builder(context)
            .data(imageUri)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()

        val result = imageLoader.execute(request)
        val drawable = (result as? SuccessResult)?.drawable
            ?: error("Unable to load selected photo. Please try another image.")
        val bitmap = drawable.toBitmap()
        if (bitmap.isRecycled) {
            error("Selected photo is unavailable. Please try again.")
        }
        return bitmap
    }

    private fun getRealScreenSize(): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width().coerceAtLeast(1080) to bounds.height().coerceAtLeast(1920)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels.coerceAtLeast(1080) to metrics.heightPixels.coerceAtLeast(1920)
        }
    }

    companion object {
        fun centerCropToScreen(
            source: Bitmap,
            targetWidth: Int,
            targetHeight: Int,
        ): Bitmap {
            val srcW = source.width
            val srcH = source.height
            if (srcW <= 0 || srcH <= 0 || targetWidth <= 0 || targetHeight <= 0) return source

            val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
            val srcAspect = srcW.toFloat() / srcH.toFloat()

            val outW: Int
            val outH: Int
            if (srcW >= targetWidth && srcH >= targetHeight) {
                val cappedW = targetWidth.coerceAtMost(2160)
                val cappedH = (cappedW / targetAspect).roundToInt()
                outW = cappedW
                outH = cappedH
            } else {
                outW = targetWidth
                outH = targetHeight
            }

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
