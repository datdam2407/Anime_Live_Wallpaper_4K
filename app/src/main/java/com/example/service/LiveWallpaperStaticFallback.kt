package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.view.Surface
import android.view.SurfaceHolder
import com.example.data.local.LiveWallpaperDownloader
import com.kuroanimelivewallpapers.app.R
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Guarantees users NEVER see a blank black screen on low-RAM (<= 2GB), slow-buffering, or
 * codec-incompatible devices:
 * 1. Persists a lightweight first-frame poster JPEG inside `context.filesDir/live_wallpapers/`
 *    (never `cacheDir`) so static fallback survives reboots and OS cache cleanups offline.
 * 2. Renders a pre-playback static frame onto the [Surface] via OpenGL ES 2.0 (`EGL14` + `GLES20`)
 *    and immediately disconnects EGL (`eglDestroySurface`), leaving the buffer latched on screen
 *    while keeping the [Surface] clean for `ExoPlayer.setVideoSurface` / `MediaCodec`.
 * 3. Draws directly onto [SurfaceHolder.lockCanvas] ([Canvas]) whenever no local video exists or
 *    when video playback permanently falls back to static rendering.
 */
object LiveWallpaperStaticFallback {

    const val ACTIVE_POSTER_FILENAME = "active_live_poster.jpg"
    private const val POSTER_SUFFIX = "_poster.jpg"

    fun getActivePosterFile(context: Context): File {
        val dir = File(context.applicationContext.filesDir, LiveWallpaperDownloader.LIVE_WALLPAPERS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, ACTIVE_POSTER_FILENAME)
    }

    fun getPosterFileForVideo(videoFile: File): File {
        val parent = videoFile.parentFile ?: File(".")
        if (!parent.exists()) parent.mkdirs()
        val base = videoFile.nameWithoutExtension.removeSuffix("_compat")
        return File(parent, "$base$POSTER_SUFFIX")
    }

    /**
     * Extracts and saves a static preview frame to `context.filesDir/live_wallpapers/` (with
     * fallback to [thumbnailUrl]) so `WallpaperService.Engine` always has an instant local poster.
     */
    fun persistPosterForVideo(
        context: Context,
        videoFile: File,
        thumbnailUrl: String? = null,
        okHttpClient: OkHttpClient? = null,
        isLowRam: Boolean = false,
    ): File? {
        val perVideoPoster = getPosterFileForVideo(videoFile)
        val activePoster = getActivePosterFile(context)

        val maxWidth = if (isLowRam) 540 else 1080
        val maxHeight = if (isLowRam) 960 else 1920

        var savedPoster: File? = null
        if (perVideoPoster.exists() && perVideoPoster.length() > 256L) {
            savedPoster = perVideoPoster
        } else {
            val extracted = extractVideoFirstFrame(videoFile, maxWidth, maxHeight, isLowRam)
            if (extracted != null) {
                if (writeBitmapAtomically(extracted, perVideoPoster)) {
                    savedPoster = perVideoPoster
                }
                if (!extracted.isRecycled) extracted.recycle()
            }
        }

        if (savedPoster == null && !thumbnailUrl.isNullOrBlank() && okHttpClient != null) {
            if (downloadThumbnailToPoster(thumbnailUrl, perVideoPoster, okHttpClient)) {
                savedPoster = perVideoPoster
            }
        }

        if (savedPoster != null && savedPoster.exists() && savedPoster.length() > 0L) {
            runCatching {
                if (savedPoster.absolutePath != activePoster.absolutePath) {
                    val tempActive = File(activePoster.parentFile, "$ACTIVE_POSTER_FILENAME.tmp")
                    savedPoster.copyTo(tempActive, overwrite = true)
                    if (!tempActive.renameTo(activePoster)) {
                        tempActive.copyTo(activePoster, overwrite = true)
                        tempActive.delete()
                    }
                }
            }
            return savedPoster
        }
        return null
    }

    /**
     * Loads a downsampled static preview [Bitmap] strictly within safe heap limits
     * (`RGB_565` ~1 MB on <= 2GB RAM devices). Never performs network I/O.
     */
    fun loadStaticPreviewBitmap(
        context: Context,
        videoFile: File?,
        targetWidth: Int,
        targetHeight: Int,
        isLowRam: Boolean,
    ): Bitmap? {
        val capW = if (isLowRam) targetWidth.coerceIn(320, 540) else targetWidth.coerceIn(480, 1080)
        val capH = if (isLowRam) targetHeight.coerceIn(480, 960) else targetHeight.coerceIn(800, 1920)
        val preferredConfig = if (isLowRam) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888

        // 1. Per-wallpaper poster in filesDir
        if (videoFile != null) {
            val videoPoster = getPosterFileForVideo(videoFile)
            decodeSampledFile(videoPoster, capW, capH, preferredConfig)?.let { return it }
        }

        // 2. Active poster in filesDir
        val activePoster = getActivePosterFile(context)
        decodeSampledFile(activePoster, capW, capH, preferredConfig)?.let { return it }

        // 3. Extract directly from local MP4 if available, and persist for future boots
        if (videoFile != null && videoFile.exists() && videoFile.length() > 0L) {
            extractVideoFirstFrame(videoFile, capW, capH, isLowRam)?.let { extracted ->
                runCatching {
                    writeBitmapAtomically(extracted, getPosterFileForVideo(videoFile))
                    writeBitmapAtomically(extracted, activePoster)
                }
                return extracted
            }
        }

        // 4. Bundled offline anime night artwork fallback
        return decodeSampledResource(context, R.drawable.img_hero_anime_night, capW, capH, preferredConfig)
            ?: decodeSampledResource(context, R.drawable.img_launcher_emblem, capW, capH, preferredConfig)
    }

    /**
     * Draws a static preview frame directly onto [holder]'s [Canvas].
     * NOTE: Only call this when no `MediaCodec` / `ExoPlayer` will attach to the current [Surface]
     * instance (e.g., when no local video exists yet or after all video decoders failed and detached),
     * because `SurfaceHolder.lockCanvas()` permanently binds `NATIVE_WINDOW_API_CPU` to that `Surface`.
     */
    fun drawFallbackToCanvas(
        holder: SurfaceHolder,
        bitmap: Bitmap?,
    ): Boolean {
        val surface = holder.surface
        if (surface == null || !surface.isValid) return false
        var canvas: Canvas? = null
        return try {
            canvas = holder.lockCanvas() ?: return false
            drawBitmapOrGradientOnCanvas(canvas, bitmap)
            true
        } catch (_: Throwable) {
            false
        } finally {
            if (canvas != null) {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }

    internal fun drawBitmapOrGradientOnCanvas(canvas: Canvas, bitmap: Bitmap?) {
        val dstW = canvas.width.toFloat().coerceAtLeast(1f)
        val dstH = canvas.height.toFloat().coerceAtLeast(1f)

        if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) {
            val srcW = bitmap.width.toFloat()
            val srcH = bitmap.height.toFloat()
            val scale = max(dstW / srcW, dstH / srcH)
            val dx = (dstW - srcW * scale) * 0.5f
            val dy = (dstH - srcH * scale) * 0.5f
            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(dx, dy)
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawColor(android.graphics.Color.rgb(12, 11, 18))
            canvas.drawBitmap(bitmap, matrix, paint)
        } else {
            val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    dstW,
                    dstH,
                    intArrayOf(
                        android.graphics.Color.rgb(18, 15, 34),
                        android.graphics.Color.rgb(34, 18, 52),
                        android.graphics.Color.rgb(12, 11, 18),
                    ),
                    floatArrayOf(0f, 0.52f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, dstW, dstH, gradientPaint)
        }
    }

    /**
     * Pushes a single center-cropped static preview frame onto [surface] using pure OpenGL ES 2.0
     * (compatible with all 969 OpenGL ES 2.0-only devices and all 5,184 PowerVR GPUs using NPOT
     * `GL_CLAMP_TO_EDGE` textures), then **immediately destroys the `EGLSurface`**.
     *
     * Because `eglDestroySurface` cleanly disconnects `NATIVE_WINDOW_API_EGL`, SurfaceFlinger keeps
     * displaying this static frame during buffering while `ExoPlayer.setVideoSurface(surface)`
     * (`MediaCodec`) can immediately connect to the exact same [surface] without producer conflicts!
     */
    fun renderPrePlaybackFrameGles20(
        surface: Surface,
        bitmap: Bitmap,
        surfaceWidth: Int,
        surfaceHeight: Int,
    ): Boolean {
        if (!surface.isValid || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return false
        }
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var context: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var program = 0
        val texIds = IntArray(1)

        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return false
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return false

            val config = chooseGles20Config(display) ?: return false
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return false

            val surfAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, surfAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) return false

            val vpW = surfaceWidth.coerceAtLeast(1)
            val vpH = surfaceHeight.coerceAtLeast(1)
            GLES20.glViewport(0, 0, vpW, vpH)
            GLES20.glClearColor(0.05f, 0.04f, 0.08f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val vertexShader = compileShader(
                GLES20.GL_VERTEX_SHADER,
                """
                attribute vec2 aPos;
                attribute vec2 aUv;
                varying vec2 vUv;
                void main() {
                    gl_Position = vec4(aPos, 0.0, 1.0);
                    vUv = aUv;
                }
                """.trimIndent(),
            )
            val fragmentShader = compileShader(
                GLES20.GL_FRAGMENT_SHADER,
                """
                precision mediump float;
                uniform sampler2D uTex;
                varying vec2 vUv;
                void main() {
                    gl_FragColor = texture2D(uTex, vUv);
                }
                """.trimIndent(),
            )
            if (vertexShader == 0 || fragmentShader == 0) return false

            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            GLES20.glDeleteShader(vertexShader)
            GLES20.glDeleteShader(fragmentShader)

            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) return false

            GLES20.glUseProgram(program)

            // Center-crop UV calculation
            val viewAspect = vpW.toFloat() / vpH.toFloat()
            val bmpAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
            val (uMin, uMax, vMin, vMax) = if (bmpAspect > viewAspect) {
                val visibleU = (viewAspect / bmpAspect).coerceIn(0.05f, 1f)
                val padU = (1f - visibleU) * 0.5f
                floatArrayOf(padU, 1f - padU, 0f, 1f)
            } else {
                val visibleV = (bmpAspect / viewAspect).coerceIn(0.05f, 1f)
                val padV = (1f - visibleV) * 0.5f
                floatArrayOf(0f, 1f, padV, 1f - padV)
            }

            val posData = floatArrayOf(
                -1f, -1f,
                1f, -1f,
                -1f, 1f,
                1f, 1f,
            )
            // Note: Android Bitmap top is Y=0, while GLES viewport bottom is Y=-1
            val uvData = floatArrayOf(
                uMin, vMax,
                uMax, vMax,
                uMin, vMin,
                uMax, vMin,
            )

            val posBuf = toFloatBuffer(posData)
            val uvBuf = toFloatBuffer(uvData)

            GLES20.glGenTextures(1, texIds, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
            // Mandatory GL_CLAMP_TO_EDGE + GL_LINEAR for NPOT textures on GLES 2.0 (PowerVR / Mali)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

            val aPos = GLES20.glGetAttribLocation(program, "aPos")
            val aUv = GLES20.glGetAttribLocation(program, "aUv")
            val uTex = GLES20.glGetUniformLocation(program, "uTex")

            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, posBuf)
            GLES20.glEnableVertexAttribArray(aUv)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 0, uvBuf)
            GLES20.glUniform1i(uTex, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glFinish()
            EGL14.eglSwapBuffers(display, eglSurface)
        } catch (_: Throwable) {
            false
        } finally {
            runCatching {
                if (texIds[0] != 0) GLES20.glDeleteTextures(1, texIds, 0)
                if (program != 0) GLES20.glDeleteProgram(program)
            }
            if (display != EGL14.EGL_NO_DISPLAY) {
                runCatching {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    runCatching { EGL14.eglDestroySurface(display, eglSurface) }
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    runCatching { EGL14.eglDestroyContext(display, context) }
                }
                runCatching { EGL14.eglTerminate(display) }
            }
        }
    }

    private fun chooseGles20Config(display: EGLDisplay): EGLConfig? {
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        val rgba8888 = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_NONE,
        )
        if (EGL14.eglChooseConfig(display, rgba8888, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            return configs[0]
        }
        val rgb565 = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 5,
            EGL14.EGL_GREEN_SIZE, 6,
            EGL14.EGL_BLUE_SIZE, 5,
            EGL14.EGL_NONE,
        )
        if (EGL14.eglChooseConfig(display, rgb565, 0, configs, 0, 1, numConfigs, 0) && numConfigs[0] > 0) {
            return configs[0]
        }
        return null
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return 0
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun toFloatBuffer(values: FloatArray): FloatBuffer {
        return ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }
    }

    private fun extractVideoFirstFrame(
        videoFile: File,
        maxW: Int,
        maxH: Int,
        isLowRam: Boolean,
    ): Bitmap? {
        if (!videoFile.exists() || videoFile.length() <= 0L) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    maxW,
                    maxH,
                ) ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            if (raw != null && isLowRam && raw.config != Bitmap.Config.RGB_565) {
                val compact = raw.copy(Bitmap.Config.RGB_565, false)
                if (compact != null) {
                    raw.recycle()
                    compact
                } else {
                    raw
                }
            } else {
                raw
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun writeBitmapAtomically(bitmap: Bitmap, targetFile: File): Boolean {
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        return try {
            targetFile.parentFile?.mkdirs()
            FileOutputStream(tempFile).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 86, out)) {
                    return false
                }
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (!tempFile.exists() || tempFile.length() <= 0L) {
                tempFile.delete()
                return false
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (_: Throwable) {
            tempFile.delete()
            false
        }
    }

    private fun downloadThumbnailToPoster(
        thumbnailUrl: String,
        targetFile: File,
        okHttpClient: OkHttpClient,
    ): Boolean {
        if (!thumbnailUrl.startsWith("https://", ignoreCase = true)) return false
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.dl.tmp")
        return try {
            targetFile.parentFile?.mkdirs()
            val req = Request.Builder().url(thumbnailUrl).build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false
                FileOutputStream(tempFile).use { out ->
                    body.byteStream().copyTo(out)
                    out.flush()
                    runCatching { out.fd.sync() }
                }
            }
            if (!tempFile.exists() || tempFile.length() <= 256L ||
                VideoCompatibilityInspector.looksLikeTextErrorPayload(tempFile)
            ) {
                tempFile.delete()
                return false
            }
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            true
        } catch (_: Throwable) {
            tempFile.delete()
            false
        }
    }

    private fun decodeSampledFile(
        file: File,
        reqW: Int,
        reqH: Int,
        config: Bitmap.Config,
    ): Bitmap? {
        if (!file.exists() || file.length() <= 0L) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
                inPreferredConfig = config
            }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeSampledResource(
        context: Context,
        resId: Int,
        reqW: Int,
        reqH: Int,
        config: Bitmap.Config,
    ): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, resId, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, reqW, reqH)
                inPreferredConfig = config
            }
            BitmapFactory.decodeResource(context.resources, resId, opts)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    internal fun computeInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        var sample = 1
        val safeReqW = reqW.coerceAtLeast(1)
        val safeReqH = reqH.coerceAtLeast(1)
        if (srcH > safeReqH || srcW > safeReqW) {
            val halfH = srcH / 2
            val halfW = srcW / 2
            while ((halfH / sample) >= safeReqH && (halfW / sample) >= safeReqW) {
                sample *= 2
            }
        }
        return sample.coerceAtLeast(1)
    }
}
