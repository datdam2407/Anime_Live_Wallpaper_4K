package com.example.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.domain.model.AppWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
 * slots (wallpaper_a.mp4 <-> wallpaper_b.mp4) with progress emission for smooth WallpaperService hot-swap.
 */
class MediaDownloadManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val PREF_NAME = "live_wallpaper_prefs"
        private const val KEY_ACTIVE_FILE = "active_video_filename"
        const val FILE_SLOT_A = "wallpaper_a.mp4"
        const val FILE_SLOT_B = "wallpaper_b.mp4"
        private const val TEMP_DOWNLOAD_FILE = "downloading_live_temp.tmp"
        const val ACTION_RELOAD_WALLPAPER = "com.example.ACTION_RELOAD_WALLPAPER"

        fun getActiveLiveVideoFile(context: Context): File {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val fileName = prefs.getString(KEY_ACTIVE_FILE, FILE_SLOT_A) ?: FILE_SLOT_A
            val preferredFile = File(context.filesDir, fileName)
            if (preferredFile.exists() && preferredFile.length() > 0L) {
                return preferredFile
            }
            val alternateName = if (fileName == FILE_SLOT_A) FILE_SLOT_B else FILE_SLOT_A
            val alternateFile = File(context.filesDir, alternateName)
            if (alternateFile.exists() && alternateFile.length() > 0L) {
                return alternateFile
            }
            val legacyFile = File(context.filesDir, "active_live_wallpaper.mp4")
            return if (legacyFile.exists() && legacyFile.length() > 0L) legacyFile else preferredFile
        }
    }

    private val videoDownloadClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    suspend fun saveToGallery(wallpaper: AppWallpaper): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (wallpaper.isLive) {
                wallpaper.videoUrl ?: error("Live video URL unavailable.")
            } else {
                wallpaper.imageUrl
            }
            val isMp4 = wallpaper.isLive || url.endsWith(".mp4", ignoreCase = true)
            val isPng = !isMp4 && url.endsWith(".png", ignoreCase = true)
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

            val safeName = "KuroWall_${wallpaper.id}_${System.currentTimeMillis()}.$extension"
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
                val request = Request.Builder().url(url).build()
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
                uri
            } catch (e: Throwable) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
    }

    /**
     * Streams an MP4 video into an alternating local slot (wallpaper_a.mp4 <-> wallpaper_b.mp4)
     * with real-time 0..100 progress emissions, then signals WallpaperService to hot-swap immediately.
     */
    fun downloadLiveWallpaper(videoUrl: String): Flow<LiveDownloadState> = flow {
        emit(LiveDownloadState.Downloading(0))

        val tempFile = File(context.filesDir, TEMP_DOWNLOAD_FILE)
        try {
            val request = Request.Builder().url(videoUrl).build()
            videoDownloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(LiveDownloadState.Error("Không tải được video (HTTP ${response.code})"))
                    return@flow
                }
                val body = response.body ?: run {
                    emit(LiveDownloadState.Error("File video rỗng"))
                    return@flow
                }

                val totalBytes = body.contentLength()
                var downloadedBytes = 0L

                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val currentActiveName = prefs.getString(KEY_ACTIVE_FILE, FILE_SLOT_A) ?: FILE_SLOT_A
                val nextFileName = if (currentActiveName == FILE_SLOT_A) FILE_SLOT_B else FILE_SLOT_A
                val finalFile = File(context.filesDir, nextFileName)

                if (tempFile.exists()) tempFile.delete()

                body.byteStream().use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
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
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }

                prefs.edit().putString(KEY_ACTIVE_FILE, nextFileName).apply()

                val oldSlotFile = File(context.filesDir, currentActiveName)
                if (oldSlotFile.exists() && oldSlotFile.name != finalFile.name) {
                    oldSlotFile.delete()
                }
                val legacyFile = File(context.filesDir, "active_live_wallpaper.mp4")
                if (legacyFile.exists() && legacyFile.name != finalFile.name) {
                    legacyFile.delete()
                }

                context.sendBroadcast(
                    Intent(ACTION_RELOAD_WALLPAPER).setPackage(context.packageName),
                )

                emit(LiveDownloadState.Success(finalFile))
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            emit(LiveDownloadState.Error(e.localizedMessage ?: "Lỗi kết nối mạng khi tải video"))
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
