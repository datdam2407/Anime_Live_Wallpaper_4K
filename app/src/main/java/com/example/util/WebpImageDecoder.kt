package com.example.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.pxOrElse
import okio.BufferedSource
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dedicated Coil [Decoder] for WebP images (including VP8, VP8L, VP8X extended, animated WebP
 * first-frame, and ICC-profiled Cloudflare R2 `.webp` assets).
 *
 * Uses Android's native [ImageDecoder] (API 28+) with [ImageDecoder.ALLOCATOR_SOFTWARE] to avoid
 * GPU/emulator hardware-bitmap failures and BitmapFactory limitations on modern WebP encodings,
 * with automatic fallback to [BitmapFactory] if needed.
 */
class WebpImageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val file = source.file().toFile()
        val targetWidth = options.size.width.pxOrElse { 0 }
        val targetHeight = options.size.height.pxOrElse { 0 }

        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(file, targetWidth, targetHeight)
                ?: decodeWithBitmapFactory(file, targetWidth, targetHeight)
        } else {
            decodeWithBitmapFactory(file, targetWidth, targetHeight)
        } ?: throw IllegalStateException("Unable to decode WebP image (${file.length()} bytes)")

        val isSampled = targetWidth > 0 && targetHeight > 0 &&
            (bitmap.width <= targetWidth || bitmap.height <= targetHeight)

        return DecodeResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = isSampled,
        )
    }

    private fun decodeWithImageDecoder(
        file: File,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val imageSource = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(imageSource) { decoder, info, _ ->
                // Software allocator avoids blank/failed hardware-bitmap WebP decodes on emulators & OEM GPUs.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false

                val srcW = info.size.width
                val srcH = info.size.height
                if (srcW > 0 && srcH > 0) {
                    val maxW = if (targetWidth > 0) targetWidth else MAX_FALLBACK_DIMENSION
                    val maxH = if (targetHeight > 0) targetHeight else MAX_FALLBACK_DIMENSION
                    val scale = min(maxW.toFloat() / srcW.toFloat(), maxH.toFloat() / srcH.toFloat())
                    if (scale < 1f) {
                        val dstW = (srcW * scale).roundToInt().coerceAtLeast(1)
                        val dstH = (srcH * scale).roundToInt().coerceAtLeast(1)
                        decoder.setTargetSize(dstW, dstH)
                    }
                }
            }
        }.onFailure { err ->
            runCatching { Log.w(TAG, "ImageDecoder failed for WebP (${file.name}): ${err.message}") }
        }.getOrNull()
    }

    private fun decodeWithBitmapFactory(
        file: File,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            var sampleSize = 1
            if (srcW > 0 && srcH > 0 && targetWidth > 0 && targetHeight > 0) {
                while ((srcW / (sampleSize * 2)) >= targetWidth && (srcH / (sampleSize * 2)) >= targetHeight) {
                    sampleSize *= 2
                }
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = false
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        }.onFailure { err ->
            runCatching { Log.w(TAG, "BitmapFactory fallback failed for WebP (${file.name}): ${err.message}") }
        }.getOrNull()
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!isWebp(result.mimeType, result.source.source())) return null
            return WebpImageDecoder(result.source, options)
        }

        private fun isWebp(mimeType: String?, source: BufferedSource): Boolean {
            if (mimeType.equals("image/webp", ignoreCase = true)) return true
            return runCatching {
                source.rangeEquals(0, RIFF_HEADER) && source.rangeEquals(8, WEBP_HEADER)
            }.getOrDefault(false)
        }

        override fun equals(other: Any?): Boolean = other is Factory
        override fun hashCode(): Int = javaClass.hashCode()
    }

    companion object {
        private const val TAG = "WebpImageDecoder"
        private const val MAX_FALLBACK_DIMENSION = 2048
        private val RIFF_HEADER = "RIFF".encodeUtf8()
        private val WEBP_HEADER = "WEBP".encodeUtf8()
    }
}
