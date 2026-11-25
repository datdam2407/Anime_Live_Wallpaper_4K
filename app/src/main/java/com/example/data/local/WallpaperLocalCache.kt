package com.example.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

private val Context.staticWallpaperCacheDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "kuro_static_wallpaper_cache",
)

/**
 * Local persistence contract for the static wallpaper catalog JSON string
 * supporting Stale-While-Revalidate startup hydration.
 */
interface WallpaperLocalCache {
    suspend fun getJson(): String?
    suspend fun saveJson(json: String)
    suspend fun getLastUpdatedAtMs(): Long
    fun observeJson(): Flow<String?>
}

/**
 * DataStore-backed persistent JSON cache for `static_wallpapers_v1.json`.
 * Automatically migrates any legacy file cache into DataStore on first read.
 */
class DataStoreWallpaperLocalCache(
    private val context: Context,
    private val legacyCacheFile: File? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : WallpaperLocalCache {

    private object Keys {
        val STATIC_CATALOG_JSON = stringPreferencesKey("static_catalog_json")
        val STATIC_CATALOG_UPDATED_AT_MS = longPreferencesKey("static_catalog_updated_at_ms")
    }

    override suspend fun getJson(): String? = withContext(Dispatchers.IO) {
        val prefs = runCatching { context.staticWallpaperCacheDataStore.data.first() }.getOrNull()
        val stored = prefs?.get(Keys.STATIC_CATALOG_JSON)?.takeIf { it.isNotBlank() }
        if (stored != null) {
            return@withContext stored
        }

        // One-time migration from legacy disk file cache if present.
        val legacy = legacyCacheFile
        if (legacy != null && legacy.exists() && legacy.length() > 0L) {
            val migrated = runCatching { legacy.readText(Charsets.UTF_8) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            if (migrated != null) {
                runCatching { saveJson(migrated) }
                return@withContext migrated
            }
        }
        null
    }

    override suspend fun saveJson(json: String) {
        if (json.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val now = clock()
                context.staticWallpaperCacheDataStore.edit { prefs ->
                    prefs[Keys.STATIC_CATALOG_JSON] = json
                    prefs[Keys.STATIC_CATALOG_UPDATED_AT_MS] = now
                }
            }.onFailure { err ->
                runCatching { Log.w(TAG, "Failed to persist static wallpaper JSON to DataStore: ${err.message}") }
            }
        }
    }

    override suspend fun getLastUpdatedAtMs(): Long = withContext(Dispatchers.IO) {
        val stored = runCatching {
            context.staticWallpaperCacheDataStore.data.first()[Keys.STATIC_CATALOG_UPDATED_AT_MS]
        }.getOrNull()
        if (stored != null && stored > 0L) {
            return@withContext stored
        }
        val legacyModified = legacyCacheFile?.takeIf { it.exists() }?.lastModified() ?: 0L
        if (legacyModified > 0L) legacyModified else Long.MIN_VALUE
    }

    override fun observeJson(): Flow<String?> {
        return context.staticWallpaperCacheDataStore.data.map { prefs ->
            prefs[Keys.STATIC_CATALOG_JSON]?.takeIf { it.isNotBlank() }
        }
    }

    companion object {
        private const val TAG = "WallpaperLocalCache"
    }
}

/**
 * Atomic file-backed [WallpaperLocalCache] used when a specific disk file is provided (e.g., JVM tests).
 */
class FileWallpaperLocalCache(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : WallpaperLocalCache {

    private val jsonFlow = MutableStateFlow<String?>(null)

    override suspend fun getJson(): String? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() <= 0L) return@withContext null
        runCatching { file.readText(Charsets.UTF_8) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.also { jsonFlow.value = it }
    }

    override suspend fun saveJson(json: String) {
        if (json.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json, Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    file.writeText(json, Charsets.UTF_8)
                    tmp.delete()
                }
                jsonFlow.value = json
            }
        }
    }

    override suspend fun getLastUpdatedAtMs(): Long = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext Long.MIN_VALUE
        file.lastModified().takeIf { it > 0L } ?: clock()
    }

    override fun observeJson(): Flow<String?> = jsonFlow.asStateFlow()
}

/**
 * Lightweight in-memory [WallpaperLocalCache] for unit tests or fallback when disk storage is unavailable.
 */
class InMemoryWallpaperLocalCache(
    initialJson: String? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : WallpaperLocalCache {

    private val state = MutableStateFlow(initialJson)
    @Volatile
    private var updatedAtMs: Long = if (initialJson.isNullOrBlank()) Long.MIN_VALUE else clock()

    override suspend fun getJson(): String? = state.value

    override suspend fun saveJson(json: String) {
        if (json.isBlank()) return
        state.value = json
        updatedAtMs = clock()
    }

    override suspend fun getLastUpdatedAtMs(): Long = updatedAtMs

    override fun observeJson(): Flow<String?> = state.asStateFlow()
}
