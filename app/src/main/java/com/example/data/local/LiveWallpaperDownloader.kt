package com.example.data.local

import android.content.Context
import android.util.Log
import com.example.service.DeviceHardwareProfile
import com.example.service.LiveWallpaperStaticFallback
import com.example.service.VideoCompatibilityInspector
import com.kuroanimelivewallpapers.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Zero-Egress Offline Video Downloader for Cloudflare R2 Live Wallpaper MP4 files.
 *
 * - Strictly persists videos inside internal app storage (`context.filesDir/live_wallpapers/`),
 *   NEVER temporary `cacheDir`, preventing black screens after reboots or OS cache cleanups.
 * - Streams into an isolated `.tmp` file inside [downloadMutex], flushes + `fd.sync()`s to flash,
 *   verifies full HTTP `Content-Length` byte count and binary payload integrity, then atomically renames.
 * - Inspects downloaded video codec/profile/resolution and automatically fetches the lightweight
 *   H.264/AVC <=1080p compatibility stream (`previewUrl`) when a high-profile/4K/HEVC `fullUrl`
 *   exceeds decoder capabilities on <=2GB RAM, OpenGL ES 2.0, MediaTek, Unisoc, or PowerVR devices.
 */
class LiveWallpaperDownloader(
    private val storageRootDir: File,
    private val downloadClient: OkHttpClient,
) {
    constructor(
        context: Context,
        downloadClient: OkHttpClient = defaultDownloadClient(),
    ) : this(
        storageRootDir = File(context.applicationContext.filesDir, LIVE_WALLPAPERS_DIR),
        downloadClient = downloadClient,
    )

    companion object {
        private const val TAG = "LiveWallpaperDownloader"
        const val LIVE_WALLPAPERS_DIR = "live_wallpapers"
        private const val COMPAT_SUFFIX = "_compat"
        private val SAFE_ID_REGEX = Regex("[^A-Za-z0-9_-]")
        private val APP_USER_AGENT =
            "android:com.kuroanimelivewallpapers.app:v${BuildConfig.VERSION_NAME} (by /u/kurowall_engine)"

        fun defaultDownloadClient(): OkHttpClient = OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", APP_USER_AGENT)
                    .build()
                chain.proceed(req)
            }
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val downloadMutex = Mutex()

    private val storageDir: File
        get() = storageRootDir.apply {
            if (!exists()) mkdirs()
        }

    fun sanitizeId(wallpaperId: String): String {
        return wallpaperId.trim()
            .replace(SAFE_ID_REGEX, "_")
            .trim('_')
            .take(64)
            .ifEmpty { "live_wallpaper" }
    }

    fun getLocalFile(wallpaperId: String): File = File(storageDir, "${sanitizeId(wallpaperId)}.mp4")

    fun getCompatLocalFile(wallpaperId: String): File =
        File(storageDir, "${sanitizeId(wallpaperId)}$COMPAT_SUFFIX.mp4")

    fun isDownloaded(wallpaperId: String): Boolean {
        val compat = getCompatLocalFile(wallpaperId)
        if (VideoCompatibilityInspector.isReliableLocalVideoFile(compat)) {
            return true
        }
        val primary = getLocalFile(wallpaperId)
        return VideoCompatibilityInspector.isReliableLocalVideoFile(primary)
    }

    suspend fun downloadIfNeeded(
        wallpaperId: String,
        fullUrl: String,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        val safeId = sanitizeId(wallpaperId)
        val existing = File(storageDir, "$safeId.mp4")
        if (VideoCompatibilityInspector.isReliableLocalVideoFile(existing)) {
            onProgress(100)
            runCatching { Log.i(TAG, "Zero-egress cache hit: ${existing.absolutePath}") }
            return@withContext Result.success(existing)
        }

        downloadMutex.withLock {
            val targetFile = File(storageDir, "$safeId.mp4")
            if (VideoCompatibilityInspector.isReliableLocalVideoFile(targetFile)) {
                onProgress(100)
                return@withLock Result.success(targetFile)
            }
            if (targetFile.exists()) {
                targetFile.delete()
            }
            downloadUrlToFileLocked(
                rawUrl = fullUrl,
                targetFile = targetFile,
                onProgress = onProgress,
            )
        }
    }

    /**
     * Downloads and validates a device-compatible local MP4 inside `context.filesDir/live_wallpapers/`,
     * automatically falling back to the lightweight H.264/AVC <=1080p [previewUrl] when [fullUrl]
     * uses an unsupported codec/profile/resolution on constrained devices, and persists a static
     * first-frame poster JPEG for zero-black-screen rendering.
     */
    suspend fun prepareDeviceCompatibleVideo(
        context: Context,
        wallpaperId: String,
        fullUrl: String,
        previewUrl: String? = null,
        thumbnailUrl: String? = null,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val deviceProfile = DeviceHardwareProfile.detect(appContext)
        val safeId = sanitizeId(wallpaperId)
        val compatTarget = File(storageDir, "$safeId$COMPAT_SUFFIX.mp4")

        // Fast path: previously verified compatibility stream already cached on disk
        if (VideoCompatibilityInspector.isReliableLocalVideoFile(compatTarget, appContext.cacheDir, appContext.externalCacheDir)) {
            LiveWallpaperStaticFallback.persistPosterForVideo(
                context = appContext,
                videoFile = compatTarget,
                thumbnailUrl = thumbnailUrl,
                okHttpClient = downloadClient,
                isLowRam = deviceProfile.isLowRamDevice,
            )
            onProgress(100)
            return@withContext Result.success(compatTarget)
        }

        val primaryResult = downloadIfNeeded(
            wallpaperId = wallpaperId,
            fullUrl = fullUrl,
            onProgress = onProgress,
        )
        val primaryFile = primaryResult.getOrElse { primaryErr ->
            // If fullUrl network fetch failed and a distinct previewUrl exists, try previewUrl before failing
            val cleanPreview = previewUrl?.trim().orEmpty()
            if (cleanPreview.isNotEmpty() && !cleanPreview.equals(fullUrl.trim(), ignoreCase = true)) {
                val fallbackResult = downloadMutex.withLock {
                    downloadUrlToFileLocked(
                        rawUrl = cleanPreview,
                        targetFile = compatTarget,
                        onProgress = onProgress,
                    )
                }
                fallbackResult.onSuccess { file ->
                    LiveWallpaperStaticFallback.persistPosterForVideo(
                        context = appContext,
                        videoFile = file,
                        thumbnailUrl = thumbnailUrl,
                        okHttpClient = downloadClient,
                        isLowRam = deviceProfile.isLowRamDevice,
                    )
                }
                return@withContext fallbackResult
            }
            return@withContext Result.failure(primaryErr)
        }

        val chosenFile = maybeSelectCompatStream(
            deviceProfile = deviceProfile,
            primaryFile = primaryFile,
            compatTarget = compatTarget,
            fullUrl = fullUrl,
            previewUrl = previewUrl,
        )

        LiveWallpaperStaticFallback.persistPosterForVideo(
            context = appContext,
            videoFile = chosenFile,
            thumbnailUrl = thumbnailUrl,
            okHttpClient = downloadClient,
            isLowRam = deviceProfile.isLowRamDevice,
        )
        onProgress(100)
        Result.success(chosenFile)
    }

    private suspend fun maybeSelectCompatStream(
        deviceProfile: DeviceHardwareProfile,
        primaryFile: File,
        compatTarget: File,
        fullUrl: String,
        previewUrl: String?,
    ): File {
        val cleanPreview = previewUrl?.trim().orEmpty()
        if (cleanPreview.isEmpty() || cleanPreview.equals(fullUrl.trim(), ignoreCase = true)) {
            return primaryFile
        }
        val report = VideoCompatibilityInspector.inspectVideoFile(primaryFile) ?: return primaryFile
        if (!report.shouldPreferCompatibilityStream(deviceProfile)) {
            return primaryFile
        }

        runCatching {
            Log.w(
                TAG,
                "Primary MP4 (${report.mimeType} ${report.width}x${report.height} 10bit=${report.isTenBitOrHdr}) " +
                    "risky on device (lowRam=${deviceProfile.isLowRamDevice}, glEs=${deviceProfile.glEsVersionMajor}); " +
                    "downloading <=1080p H.264 compatibility stream.",
            )
        }

        val compatResult = downloadMutex.withLock {
            if (VideoCompatibilityInspector.isReliableLocalVideoFile(compatTarget)) {
                Result.success(compatTarget)
            } else {
                downloadUrlToFileLocked(
                    rawUrl = cleanPreview,
                    targetFile = compatTarget,
                    onProgress = {},
                )
            }
        }
        return compatResult.getOrDefault(primaryFile)
    }

    private fun downloadUrlToFileLocked(
        rawUrl: String,
        targetFile: File,
        onProgress: (Int) -> Unit,
    ): Result<File> {
        val tempFile = File(storageDir, "${targetFile.name}.tmp")
        return try {
            if (tempFile.exists()) tempFile.delete()
            val safeUrl = validateHttpsUrl(rawUrl)
            onProgress(0)
            val request = Request.Builder().url(safeUrl).build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("Download failed (HTTP ${response.code})"))
                }
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.startsWith("text/") ||
                    contentType.startsWith("application/json") ||
                    contentType.startsWith("application/xml")
                ) {
                    return Result.failure(IOException("Unexpected non-video response ($contentType)"))
                }
                val body = response.body
                    ?: return Result.failure(IOException("Empty video response body"))
                val totalBytes = body.contentLength()
                var copied = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        var lastReportedProgress = 0
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            copied += read
                            if (totalBytes > 0L) {
                                val percent = ((copied * 100L) / totalBytes)
                                    .toInt()
                                    .coerceIn(0, 99)
                                if (percent != lastReportedProgress) {
                                    lastReportedProgress = percent
                                    onProgress(percent)
                                }
                            }
                        }
                        output.flush()
                        runCatching { output.fd.sync() }
                    }
                }

                if (totalBytes > 0L && copied != totalBytes) {
                    tempFile.delete()
                    return Result.failure(
                        IOException("Incomplete video download ($copied of $totalBytes bytes)"),
                    )
                }
            }

            if (!tempFile.exists() || tempFile.length() <= 0L ||
                VideoCompatibilityInspector.looksLikeTextErrorPayload(tempFile)
            ) {
                tempFile.delete()
                return Result.failure(IOException("Downloaded video file is invalid or empty"))
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            onProgress(100)
            runCatching { Log.i(TAG, "Saved live wallpaper to: ${targetFile.absolutePath}") }
            Result.success(targetFile)
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            Result.failure(e)
        }
    }

    /**
     * Frees downloaded MP4 files in [storageDir], preserving the currently active wallpaper
     * (along with its `_compat.mp4` fallback and `_poster.jpg` / `active_live_poster.jpg` frames).
     */
    fun clearDownloadedVideos(
        keepWallpaperId: String? = null,
        keepFilePath: String? = null,
    ): Int {
        val keepBaseById = keepWallpaperId?.takeIf { it.isNotBlank() }?.let { sanitizeId(it) }
        val keepBaseByPath = keepFilePath?.takeIf { it.isNotBlank() }?.let {
            File(it).nameWithoutExtension.removeSuffix(COMPAT_SUFFIX)
        }
        val keepNames = buildSet {
            add(LiveWallpaperStaticFallback.ACTIVE_POSTER_FILENAME)
            if (keepBaseById != null) {
                add("$keepBaseById.mp4")
                add("$keepBaseById$COMPAT_SUFFIX.mp4")
                add("${keepBaseById}_poster.jpg")
            }
            if (keepBaseByPath != null) {
                add("$keepBaseByPath.mp4")
                add("$keepBaseByPath$COMPAT_SUFFIX.mp4")
                add("${keepBaseByPath}_poster.jpg")
            }
            keepFilePath?.takeIf { it.isNotBlank() }?.let { add(File(it).name) }
        }

        var deletedMp4Count = 0
        storageDir.listFiles()?.forEach { file ->
            val isMp4 = file.isFile && file.name.endsWith(".mp4", ignoreCase = true)
            val shouldKeep = file.name in keepNames
            if (!shouldKeep && file.delete() && isMp4) {
                deletedMp4Count++
            }
        }
        return deletedMp4Count
    }

    fun getDownloadedStorageBytes(): Long {
        return storageDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".mp4") }
            ?.sumOf { it.length() }
            ?: 0L
    }

    private fun validateHttpsUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val upgraded = if (trimmed.startsWith("http://", ignoreCase = true)) {
            "https://" + trimmed.substring(7)
        } else {
            trimmed
        }
        val parsed = upgraded.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid live video URL.")
        require(parsed.isHttps) { "Insecure URL scheme blocked. HTTPS is required." }
        return parsed.toString()
    }
}
