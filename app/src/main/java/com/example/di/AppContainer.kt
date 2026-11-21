package com.example.di

import android.content.Context
import android.os.SystemClock
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.ads.GoogleMobileAdsConsentManager
import com.example.ads.InterstitialAdManager
import com.example.ads.NativeAdManager
import com.example.data.local.KuroWallDatabase
import com.example.data.local.LiveWallpaperDownloader
import com.example.data.local.SettingsDataStore
import com.example.data.remote.LiveCatalogApi
import com.example.data.remote.NetworkClient
import com.example.data.remote.NetworkModule
import com.example.data.remote.WallhavenRateLimiter
import com.example.data.remote.WallhavenService
import com.example.data.repository.LiveWallpaperRepository
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

    val liveCatalogApi: LiveCatalogApi by lazy {
        NetworkClient.createLiveCatalogApi(appContext)
    }

    val liveWallpaperDownloader: LiveWallpaperDownloader by lazy {
        LiveWallpaperDownloader(
            context = appContext,
            downloadClient = NetworkModule.mediaOkHttpClient,
        )
    }

    val liveWallpaperRepository: LiveWallpaperRepository by lazy {
        LiveWallpaperRepository(
            api = liveCatalogApi,
            downloader = liveWallpaperDownloader,
        )
    }

    val wallhavenService: WallhavenService by lazy {
        NetworkModule.createWallhavenService(appContext)
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

    val wallhavenRateLimiter: WallhavenRateLimiter by lazy {
        WallhavenRateLimiter(clock = { SystemClock.elapsedRealtime() })
    }

    val wallpaperRepository: WallpaperRepository by lazy {
        WallpaperRepository(
            wallhavenApi = wallhavenService,
            redditApi = NetworkModule.redditService,
            wallpaperDao = database.wallpaperDao(),
            liveWallpaperRepository = liveWallpaperRepository,
            wallhavenRateLimiter = wallhavenRateLimiter,
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

    val consentManager: GoogleMobileAdsConsentManager by lazy {
        GoogleMobileAdsConsentManager(appContext)
    }

    val interstitialAdManager: InterstitialAdManager by lazy {
        InterstitialAdManager(appContext = appContext)
    }

    val nativeAdManager: NativeAdManager by lazy {
        NativeAdManager(appContext = appContext)
    }
}
