package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.ResolutionTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kurowall_settings")

data class UserSettings(
    val preferredResolution: ResolutionTier = ResolutionTier.ALL,
    val muteLiveWallpaper: Boolean = true,
    val activeLiveWallpaperPath: String? = null,
    val activeLiveWallpaperTitle: String? = null,
    val activeLiveUpdatedAt: Long = 0L,
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val PREFERRED_RESOLUTION = stringPreferencesKey("preferred_resolution")
        val MUTE_LIVE_WALLPAPER = booleanPreferencesKey("mute_live_wallpaper")
        val ACTIVE_LIVE_PATH = stringPreferencesKey("active_live_path")
        val ACTIVE_LIVE_TITLE = stringPreferencesKey("active_live_title")
        val ACTIVE_LIVE_UPDATED_AT = longPreferencesKey("active_live_updated_at")
    }

    val settingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        val resName = prefs[Keys.PREFERRED_RESOLUTION]
        val resolution = ResolutionTier.entries.firstOrNull { it.name == resName }
            ?: ResolutionTier.ALL
        UserSettings(
            preferredResolution = resolution,
            muteLiveWallpaper = prefs[Keys.MUTE_LIVE_WALLPAPER] ?: true,
            activeLiveWallpaperPath = prefs[Keys.ACTIVE_LIVE_PATH],
            activeLiveWallpaperTitle = prefs[Keys.ACTIVE_LIVE_TITLE],
            activeLiveUpdatedAt = prefs[Keys.ACTIVE_LIVE_UPDATED_AT] ?: 0L,
        )
    }

    suspend fun setPreferredResolution(tier: ResolutionTier) {
        context.dataStore.edit { it[Keys.PREFERRED_RESOLUTION] = tier.name }
    }

    suspend fun setMuteLiveWallpaper(muted: Boolean) {
        context.dataStore.edit { it[Keys.MUTE_LIVE_WALLPAPER] = muted }
    }

    suspend fun setActiveLiveWallpaper(localPath: String, title: String) {
        context.dataStore.edit {
            it[Keys.ACTIVE_LIVE_PATH] = localPath
            it[Keys.ACTIVE_LIVE_TITLE] = title
            it[Keys.ACTIVE_LIVE_UPDATED_AT] = System.currentTimeMillis()
        }
    }
}
