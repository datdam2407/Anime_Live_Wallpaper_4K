package com.example.di


import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.ads.InterstitialAdManager
import com.example.data.local.KuroWallDatabase
import com.example.data.local.LiveWallpaperDataSource
import com.example.data.local.SettingsDataStore
import com.example.data.remote.NetworkModule
import com.example.data.repository.WallpaperRepository
import com.example.util.BitmapCropper
import com.example.util.MediaDownloadManager


class AppContainer(context: Context) {
    private val appContext = context.applicationContext


    val database: KuroWallDatabase by lazy {
        KuroWallDatabase.build(appContext)
    }


    val settingsDataStore: SettingsDataStore by lazy {
        SettingsDataStore(appContext)
    }


    val liveWallpaperDataSource: LiveWallpaperDataSource by lazy {
        LiveWallpaperDataSource(appContext)
    }


    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .okHttpClient { NetworkModule.mediaOkHttpClient }
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.25)
                    .strongReferencesEnabled(true)
                    .weakReferencesEnabled(true)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("coil_image_cache"))
                    .maxSizeBytes(256L * 1024L * 1024L)
                    .build()
            }
            .respectCacheHeaders(false)
            .allowHardware(true)
            .crossfade(false)
            .build()
    }


    val wallpaperRepository: WallpaperRepository by lazy {
        WallpaperRepository(
            wallhavenApi = NetworkModule.wallhavenService,
            redditApi = NetworkModule.redditService,
            wallpaperDao = database.wallpaperDao(),
            liveWallpaperDataSource = liveWallpaperDataSource,
        )
    }


    val bitmapCropper: BitmapCropper by lazy {
        BitmapCropper(
            context = appContext,
            imageLoader = imageLoader,
        )
    }


    val mediaDownloadManager: MediaDownloadManager by lazy {
        MediaDownloadManager(
            context = appContext,
            mediaOkHttpClient = NetworkModule.mediaOkHttpClient,
        )
    }


    val interstitialAdManager: InterstitialAdManager by lazy {
        InterstitialAdManager(appContext = appContext)
    }


    val nativeAdManager: com.example.ads.NativeAdManager by lazy {
        com.example.ads.NativeAdManager(appContext = appContext)
    }
}




