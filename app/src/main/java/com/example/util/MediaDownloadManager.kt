package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.domain.model.AppWallpaper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed interface LiveDownloadState {
    data object Idle : LiveDownloadState
    data class Downloading(val progress: Int) : LiveDownloadState
    data class Success(val file: File) : LiveDownloadState
    data class Error(val message: String) : LiveDownloadState
}

/**
 * Handles downloading high-res images & MP4 live loops to device Gallery (via scoped MediaStore,
 * requiring zero broad storage permissions) and caching MP4 files locally using A/B alternating
 * slots (wallpaper_a.mp4 <-> wallpaper_b.mp4) serialized by [downloadMutex] with atomic temp files.
 */
class MediaDownloadManager(
    private val context: Context,
    private val mediaOkHttpClient: OkHttpClient,
) {
    companion object {
        private const val PREF_NAME = "live_wallpaper_prefs"
        private const val KEY_ACTIVE_FILE = "active_video_filename"
        private const val KEY_ACTIVE_FILE_PATH = "active_video_filepath"
        private const val KEY_ACTIVE_UPDATED_AT = "active_video_updated_at"
        const val FILE_SLOT_A = "wallpaper_a.mp4"
        const val FILE_SLOT_B = "wallpaper_b.mp4"
        const val ACTION_RELOAD_WALLPAPER = "com.example.ACTION_RELOAD_WALLPAPER"
        private val SAFE_ID_REGEX = Regex("[^A-Za-z0-9_-]")

        /**
         * Points [com.example.service.AnimeLiveWallpaperService] directly to a locally downloaded
         * R2 live wallpaper file (e.g. `filesDir/live_wallpapers/kuro_0001.mp4`) and broadcasts
         * an immediate reload without duplicating the MP4 on disk.
         */
        fun setActiveLiveVideoFile(context: Context, file: File) {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_ACTIVE_FILE_PATH, file.absolutePath)
                .putLong(KEY_ACTIVE_UPDATED_AT, System.currentTimeMillis())
                .commit()
            context.sendBroadcast(
                Intent(ACTION_RELOAD_WALLPAPER).setPackage(context.packageName),
            )
        }

        fun getActiveLiveUpdatedAt(context: Context): Long {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return prefs.getLong(KEY_ACTIVE_UPDATED_AT, 0L)
        }

        fun getActiveLiveVideoFile(context: Context): File {
            val appCtx = context.applicationContext ?: context
            val filesRoot = appCtx.filesDir
            val cacheDir = appCtx.cacheDir
            val externalCacheDir = appCtx.externalCacheDir
            val prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val directPath = prefs.getString(KEY_ACTIVE_FILE_PATH, null)?.trim().orEmpty()
            if (directPath.isNotEmpty()) {
                resolveValidInternalFile(directPath, filesRoot, cacheDir, externalCacheDir)?.let {
                    return it
                }
            }
            val rawName = prefs.getString(KEY_ACTIVE_FILE, FILE_SLOT_A)
            val fileName = if (rawName == FILE_SLOT_B) FILE_SLOT_B else FILE_SLOT_A
            val preferredFile = File(filesRoot, fileName)
            if (com.example.service.VideoCompatibilityInspector.isReliableLocalVideoFile(preferredFile, cacheDir, externalCacheDir)) {
                return preferredFile
            }
            val alternateName = if (fileName == FILE_SLOT_A) FILE_SLOT_B else FILE_SLOT_A
            val alternateFile = File(filesRoot, alternateName)
            if (com.example.service.VideoCompatibilityInspector.isReliableLocalVideoFile(alternateFile, cacheDir, externalCacheDir)) {
                return alternateFile
            }
            val legacyFile = File(filesRoot, "active_live_wallpaper.mp4")
            return if (com.example.service.VideoCompatibilityInspector.isReliableLocalVideoFile(legacyFile, cacheDir, externalCacheDir)) {
                legacyFile
            } else {
                preferredFile
            }
        }

        internal fun resolveValidInternalFile(
            rawPath: String,
            filesRoot: File,
            cacheDir: File? = null,
            externalCacheDir: File? = null,
        ): File? {
            val trimmed = rawPath.trim()
            if (trimmed.isEmpty()) return null
            val directFile = File(trimmed)
            if (com.example.service.VideoCompatibilityInspector.isReliableLocalVideoFile(directFile, cacheDir, externalCacheDir)) {
                return directFile
            }
            // Heal /data/data/<pkg>/files vs /data/user/<id>/<pkg>/files symlink or multi-user migrations
            val leafName = directFile.name.takeIf { it.isNotBlank() } ?: return null
            val liveDirCandidate = File(File(filesRoot, com.example.data.local.LiveWallpaperDownloader.LIVE_WALLPAPERS_DIR), leafName)
            if (com.example.service.VideoCompatibilityInspector.isReliableLocalVideoFile(liveDirCandidate, cacheDir, externalCacheDir)) {
                return liveDirCandidate
            }
            val rootCandidate = File(filesRoot, leafName)
            if (com.example.service.VideoCompatibilityInspector.isReliableLocalVideoFile(rootCandidate, cacheDir, externalCacheDir)) {
                return rootCandidate
            }
            return null
        }
    }

    // Serializes live MP4 downloads and A/B slot swaps to prevent concurrent file corruption.
    private val downloadMutex = Mutex()

    private val videoDownloadClient: OkHttpClient by lazy {
        if (mediaOkHttpClient.connectTimeoutMillis >= 30_000 &&
            mediaOkHttpClient.readTimeoutMillis >= 60_000
        ) {
            mediaOkHttpClient
        } else {
            mediaOkHttpClient.newBuilder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun validateHttpsUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val upgraded = if (trimmed.startsWith("http://", ignoreCase = true)) {
            "https://" + trimmed.substring(7)
        } else {
            trimmed
        }
        val parsed = upgraded.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid media URL.")
        require(parsed.isHttps) { "Insecure URL scheme blocked. HTTPS is required." }
        return parsed.toString()
    }

    private fun sanitizeFileId(rawId: String): String {
        return rawId.replace(SAFE_ID_REGEX, "_")
            .trim('_')
            .take(64)
            .ifEmpty { "wallpaper" }
    }

    suspend fun saveToGallery(wallpaper: AppWallpaper): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            val rawUrl = if (wallpaper.isLive) {
                wallpaper.videoUrl ?: error("Live video URL unavailable.")
            } else {
                wallpaper.imageUrl
            }
            val safeUrl = validateHttpsUrl(rawUrl)
            val cleanUrlPath = safeUrl.substringBefore("?")
            val isMp4 = wallpaper.isLive || cleanUrlPath.endsWith(".mp4", ignoreCase = true)
            val isPng = !isMp4 && cleanUrlPath.endsWith(".png", ignoreCase = true)
            val extension = when {
                isMp4 -> "mp4"
                isPng -> "png"
                else -> "jpg"
            }
            val mimeType = when {
                isMp4 -> "video/mp4"
                isPng -> "image/png"
                else -> "image/jpeg"
            }

            val safeId = sanitizeFileId(wallpaper.id)
            val safeName = "KuroWall_${safeId}_${System.currentTimeMillis()}.$extension"
            val collection = if (isMp4) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relativeDir = if (isMp4) {
                        "${Environment.DIRECTORY_MOVIES}/KuroWall"
                    } else {
                        "${Environment.DIRECTORY_PICTURES}/KuroWall"
                    }
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values)
                ?: error("Could not create MediaStore entry.")

            try {
                val request = Request.Builder().url(safeUrl).build()
                videoDownloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("Download failed with HTTP ${response.code}")
                    }
                    val body = response.body ?: error("Empty response body")
                    resolver.openOutputStream(uri)?.use { out ->
                        body.byteStream().copyTo(out)
                    } ?: error("Failed to open output stream")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                Result.success(uri)
            } catch (e: CancellationException) {
                resolver.delete(uri, null, null)
                throw e
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Streams an MP4 video into an isolated temp file inside [downloadMutex], then atomically swaps
     * alternating local slots (wallpaper_a.mp4 <-> wallpaper_b.mp4) and signals WallpaperService.
     */
    fun downloadLiveWallpaper(videoUrl: String): Flow<LiveDownloadState> = flow {
        emit(LiveDownloadState.Downloading(0))

        downloadMutex.withLock {
            var tempFile: File? = null
            try {
                val safeUrl = validateHttpsUrl(videoUrl)
                val createdTemp = File.createTempFile("live_dl_", ".mp4.tmp", context.filesDir)
                tempFile = createdTemp

                val request = Request.Builder().url(safeUrl).build()
                videoDownloadClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        emit(LiveDownloadState.Error("Failed to download video (HTTP ${response.code})"))
                        return@withLock
                    }
                    val body = response.body ?: run {
                        emit(LiveDownloadState.Error("Video response body is empty"))
                        return@withLock
                    }

                    val totalBytes = body.contentLength()
                    var downloadedBytes = 0L

                    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    val rawActive = prefs.getString(KEY_ACTIVE_FILE, FILE_SLOT_A)
                    val currentActiveName = if (rawActive == FILE_SLOT_B) FILE_SLOT_B else FILE_SLOT_A
                    val nextFileName = if (currentActiveName == FILE_SLOT_A) FILE_SLOT_B else FILE_SLOT_A
                    val finalFile = File(context.filesDir, nextFileName)

                    body.byteStream().use { inputStream ->
                        FileOutputStream(createdTemp).use { outputStream ->
                            val buffer = ByteArray(8 * 1024)
                            var bytesRead: Int
                            var lastEmittedProgress = 0

                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                outputStream.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                if (totalBytes > 0L) {
                                    val currentProgress = ((downloadedBytes * 100L) / totalBytes)
                                        .toInt()
                                        .coerceIn(0, 100)
                                    if (currentProgress != lastEmittedProgress) {
                                        lastEmittedProgress = currentProgress
                                        emit(LiveDownloadState.Downloading(currentProgress))
                                    }
                                }
                            }
                            outputStream.flush()
                            runCatching { outputStream.fd.sync() }
                        }
                    }

                    if (totalBytes > 0L && downloadedBytes != totalBytes) {
                        emit(LiveDownloadState.Error("Incomplete video download ($downloadedBytes / $totalBytes bytes)"))
                        return@withLock
                    }
                    if (!createdTemp.exists() || createdTemp.length() <= 0L ||
                        com.example.service.VideoCompatibilityInspector.looksLikeTextErrorPayload(createdTemp)
                    ) {
                        emit(LiveDownloadState.Error("Downloaded video payload is invalid"))
                        return@withLock
                    }

                    if (finalFile.exists()) finalFile.delete()
                    if (!createdTemp.renameTo(finalFile)) {
                        createdTemp.copyTo(finalFile, overwrite = true)
                    }

                    // Commit synchronously before broadcasting reload so WallpaperService reads the new slot.
                    prefs.edit()
                        .putString(KEY_ACTIVE_FILE, nextFileName)
                        .putString(KEY_ACTIVE_FILE_PATH, finalFile.absolutePath)
                        .putLong(KEY_ACTIVE_UPDATED_AT, System.currentTimeMillis())
                        .commit()

                    context.sendBroadcast(
                        Intent(ACTION_RELOAD_WALLPAPER).setPackage(context.packageName),
                    )

                    val oldSlotFile = File(context.filesDir, currentActiveName)
                    if (oldSlotFile.exists() && oldSlotFile.name != finalFile.name) {
                        oldSlotFile.delete()
                    }
                    val legacyFile = File(context.filesDir, "active_live_wallpaper.mp4")
                    if (legacyFile.exists() && legacyFile.name != finalFile.name) {
                        legacyFile.delete()
                    }

                    emit(LiveDownloadState.Success(finalFile))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(LiveDownloadState.Error(e.localizedMessage ?: "Network error while downloading video"))
            } finally {
                tempFile?.let { file ->
                    if (file.exists()) file.delete()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun cacheLiveMp4Locally(videoUrl: String): Result<File> = withContext(Dispatchers.IO) {
        var result: Result<File> = Result.failure(IllegalStateException("Download did not complete"))
        downloadLiveWallpaper(videoUrl).collect { state ->
            when (state) {
                is LiveDownloadState.Success -> result = Result.success(state.file)
                is LiveDownloadState.Error -> result = Result.failure(IllegalStateException(state.message))
                else -> Unit
            }
        }
        result
    }
}
