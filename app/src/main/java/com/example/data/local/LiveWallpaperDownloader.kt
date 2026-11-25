package com.example.data.local

import android.content.Context
import android.util.Log
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
import java.util.concurrent.TimeUnit

/**
 * Zero-Egress Offline Video Downloader for Cloudflare R2 full.mp4 files.
 *
 * - Saves each wallpaper once to [Context.getFilesDir]/live_wallpapers/<safeId>.mp4
 * - Streams into a `.tmp` file inside a [Mutex] and atomically renames on completion so
 *   dropped connections never leave half-written MP4 files behind.
 * - Subsequent taps for the same wallpaper jump straight to 100% (<10ms) with 0 network egress.
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

    fun isDownloaded(wallpaperId: String): Boolean {
        val file = getLocalFile(wallpaperId)
        return file.exists() && file.length() > 0L
    }

    suspend fun downloadIfNeeded(
        wallpaperId: String,
        fullUrl: String,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        val safeId = sanitizeId(wallpaperId)
        val existing = File(storageDir, "$safeId.mp4")
        if (existing.exists() && existing.length() > 0L) {
            onProgress(100)
            runCatching { Log.i(TAG, "Zero-egress cache hit: ${existing.absolutePath}") }
            return@withContext Result.success(existing)
        }

        downloadMutex.withLock {
            val targetFile = File(storageDir, "$safeId.mp4")
            if (targetFile.exists() && targetFile.length() > 0L) {
                onProgress(100)
                return@withLock Result.success(targetFile)
            }

            val tempFile = File(storageDir, "$safeId.mp4.tmp")
            try {
                val safeUrl = validateHttpsUrl(fullUrl)
                onProgress(0)
                val request = Request.Builder().url(safeUrl).build()
                downloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withLock Result.failure(Exception("Download failed (HTTP ${response.code})"))
                    }
                    val body = response.body
                        ?: return@withLock Result.failure(Exception("Empty video response body"))
                    val totalBytes = body.contentLength()

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8 * 1024)
                            var copied = 0L
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
                        }
                    }
                }

                if (!tempFile.exists() || tempFile.length() <= 0L) {
                    tempFile.delete()
                    return@withLock Result.failure(Exception("Downloaded video file is empty"))
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
    }

    /**
     * Frees downloaded MP4 files in [storageDir], optionally preserving the currently active
     * wallpaper either by [keepWallpaperId] or by exact [keepFilePath].
     */
    fun clearDownloadedVideos(
        keepWallpaperId: String? = null,
        keepFilePath: String? = null,
    ): Int {
        val keepByIdName = keepWallpaperId?.takeIf { it.isNotBlank() }?.let { "${sanitizeId(it)}.mp4" }
        val keepByPathName = keepFilePath?.takeIf { it.isNotBlank() }?.let { File(it).name }
        var deletedCount = 0
        storageDir.listFiles()?.forEach { file ->
            val shouldKeep = (keepByIdName != null && file.name == keepByIdName) ||
                (keepByPathName != null && file.name == keepByPathName)
            if (!shouldKeep && file.delete()) {
                deletedCount++
            }
        }
        return deletedCount
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
