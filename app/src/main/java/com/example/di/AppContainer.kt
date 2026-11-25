package com.example.di

import android.content.Context
import android.os.SystemClock
import android.util.Log
import coil.EventListener
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import com.example.ads.GoogleMobileAdsConsentManager
import com.example.ads.InterstitialAdManager
import com.example.ads.NativeAdManager
import com.example.data.local.DataStoreWallpaperLocalCache
import com.example.data.local.KuroWallDatabase
import com.example.data.local.LiveWallpaperDownloader
import com.example.data.local.SettingsDataStore
import com.example.data.local.WallpaperLocalCache
import com.example.data.remote.LiveCatalogApi
import com.example.data.remote.NetworkClient
import com.example.data.remote.NetworkModule
import com.example.data.remote.WallhavenRateLimiter
import com.example.data.remote.WallhavenService
import com.example.data.repository.LiveWallpaperRepository
import com.example.data.repository.StaticCatalogRepository
import com.example.data.repository.WallpaperRepository
import com.example.util.BitmapCropper
import com.example.util.MediaDownloadManager
import com.example.util.WebpImageDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val startupIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            catalogCacheFile = File(appContext.filesDir, "live_catalog_cache_v1.json"),
        )
    }

    val wallpaperLocalCache: WallpaperLocalCache by lazy {
        DataStoreWallpaperLocalCache(
            context = appContext,
            legacyCacheFile = File(appContext.filesDir, StaticCatalogRepository.DEFAULT_DISK_CACHE_FILE_NAME),
        )
    }

    val staticCatalogRepository: StaticCatalogRepository by lazy {
        StaticCatalogRepository(
            localCache = wallpaperLocalCache,
        )
    }

    /**
     * Hydrates both static_wallpapers_v1.json and live_wallpapers_v1.json from persistent disk cache
     * and immediately kicks off non-blocking background fetches on Dispatchers.IO.
     */
    fun prefetchCatalogsOnStartup() {
        staticCatalogRepository.prefetchCatalog(startupIoScope)
        liveWallpaperRepository.prefetchCatalog(startupIoScope)
    }

    val wallhavenService: WallhavenService by lazy {
        NetworkModule.createWallhavenService(appContext)
    }

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(appContext)
            .okHttpClient { NetworkModule.mediaOkHttpClient }
            .components {
                add(WebpImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
            }
            .eventListener(
                object : EventListener {
                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        runCatching {
                            Log.e(
                                "COIL_IMAGE_ERROR",
                                "Failed to load image data=${request.data}: ${result.throwable.javaClass.simpleName}: ${result.throwable.message}",
                                result.throwable,
                            )
                        }
                    }
                },
            )
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
            .allowRgb565(false)
            .allowHardware(false)
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
            staticCatalogRepository = staticCatalogRepository,
        )
    }

    val bitmapCropper: BitmapCropper by lazy {
        BitmapCropper(
            context = appContext,
            imageLoader = imageLoader,
            okHttpClient = NetworkModule.mediaOkHttpClient,
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
