package com.example.data.repository

import android.util.Log
import com.example.data.local.FileWallpaperLocalCache
import com.example.data.local.InMemoryWallpaperLocalCache
import com.example.data.local.WallpaperLocalCache
import com.example.data.remote.LiveCatalogApi
import com.example.data.remote.NetworkModule
import com.example.data.remote.StaticCatalogItemDto
import com.example.data.remote.StaticCatalogResponse
import com.example.domain.model.AppWallpaper
import com.example.domain.model.ResolutionTier
import com.example.domain.model.WallpaperFilter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

data class StaticCatalogPageResult(
    val items: List<AppWallpaper>,
    val totalCount: Int,
    val pageCount: Int,
    val isFromCache: Boolean,
    val isBeyondCatalog: Boolean = false,
)

/**
 * Manages the primary Home Screen static wallpaper catalog using a fast CDN-first + GitHub Raw
 * fallback pipeline with Stale-While-Revalidate local caching:
 * - **Primary CDN:** `https://cdn.jsdelivr.net/gh/datdam2407/kuro-wallpaper-data@main/static_wallpapers_v1.json`
 *   (short 5s connect/read timeout for fast failover).
 * - **Fallback URL:** `https://raw.githubusercontent.com/datdam2407/kuro-wallpaper-data/main/static_wallpapers_v1.json`
 *   (appends `?t=<epochMs>` cache-buster when `forceRefresh = true`).
 * - **Stale-While-Revalidate Local Cache:** Persists the latest valid JSON string in [WallpaperLocalCache]
 *   (DataStore). On app startup, emits cached wallpapers immediately while running [fetchWallpapersJson]
 *   asynchronously on [Dispatchers.IO] to update the cache silently in the background.
 */
class StaticCatalogRepository(
    private val localCache: WallpaperLocalCache = InMemoryWallpaperLocalCache(),
    private val okHttpClient: OkHttpClient = defaultFastCatalogClient(),
    private val api: LiveCatalogApi? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * Backward-compatible constructor accepting a [LiveCatalogApi] and/or legacy [File] cache.
     */
    constructor(
        api: LiveCatalogApi,
        catalogCacheFile: File? = null,
        clock: () -> Long = { System.currentTimeMillis() },
    ) : this(
        localCache = if (catalogCacheFile != null) {
            FileWallpaperLocalCache(catalogCacheFile, clock)
        } else {
            InMemoryWallpaperLocalCache(clock = clock)
        },
        api = api,
        clock = clock,
    )

    companion object {
        private const val TAG = "StaticCatalogRepo"
        const val CDN_URL =
            "https://cdn.jsdelivr.net/gh/datdam2407/kuro-wallpaper-data@main/static_wallpapers_v1.json"
        const val FALLBACK_URL =
            "https://raw.githubusercontent.com/datdam2407/kuro-wallpaper-data/main/static_wallpapers_v1.json"
        const val DEFAULT_PAGE_SIZE = 24
        const val DEFAULT_DISK_CACHE_FILE_NAME = "static_catalog_cache_v1.json"
        const val CATALOG_FRESH_TTL_MS = 6L * 60L * 60L * 1000L // 6 hours
        const val REFRESH_COOLDOWN_MS = 60_000L // 60 seconds
        const val FAST_FAILOVER_TIMEOUT_SECONDS = 5L

        private val moshi: Moshi by lazy {
            Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()
        }

        private val responseAdapter by lazy {
            moshi.adapter(StaticCatalogResponse::class.java)
        }

        fun defaultFastCatalogClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .dns(NetworkModule.Ipv4PreferredDns)
                .connectionSpecs(NetworkModule.tlsConnectionSpecs)
                .connectTimeout(FAST_FAILOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(FAST_FAILOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(FAST_FAILOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private val fetchMutex = Mutex()
    private val diskHydrationAttempted = AtomicBoolean(false)
    private val backgroundRevalidateInFlight = AtomicBoolean(false)

    @Volatile
    private var memoryItems: List<AppWallpaper> = emptyList()

    @Volatile
    private var lastFetchedAtMs: Long = Long.MIN_VALUE

    @Volatile
    private var diskLastModifiedAtMs: Long = Long.MIN_VALUE

    @Volatile
    private var refreshShuffleCounter: Int = 0

    private val _catalogUpdates = MutableStateFlow<List<AppWallpaper>>(emptyList())

    /**
     * Emits the latest parsed static catalog whenever local cache hydrates or background
     * Stale-While-Revalidate fetches fresher data from jsDelivr CDN / GitHub Raw.
     */
    val catalogUpdates: StateFlow<List<AppWallpaper>> = _catalogUpdates.asStateFlow()

    val hasCatalogInMemory: Boolean
        get() = memoryItems.isNotEmpty()

    /**
     * Stale-While-Revalidate startup entry point:
     * 1. Immediately hydrates the locally cached JSON into RAM and emits it on [catalogUpdates].
     * 2. Always launches [fetchWallpapersJson] asynchronously on [Dispatchers.IO] to refresh the
     *    local cache silently in the background without blocking UI startup.
     */
    fun prefetchCatalog(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            hydrateFromDiskIfNeeded()
            if (backgroundRevalidateInFlight.compareAndSet(false, true)) {
                try {
                    ensureCatalogLoaded(forceNetwork = true, silentRevalidate = true)
                } finally {
                    backgroundRevalidateInFlight.set(false)
                }
            }
        }
    }

    /**
     * Fetches the raw `static_wallpapers_v1.json` payload on [Dispatchers.IO] using OkHttp:
     * - Tries **jsDelivr CDN** first (5s connect/read timeout for fast failover).
     * - Falls back to **GitHub Raw** (`?t=<epochMs>` appended when [forceRefresh] is true).
     * - Validates the JSON parses to at least 1 valid wallpaper before persisting to [localCache].
     */
    suspend fun fetchWallpapersJson(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val rawJson = if (api != null) {
            val response = fetchViaRetrofitCdnThenRaw()
                ?: throw IOException("Unable to fetch static catalog from jsDelivr CDN or GitHub Raw.")
            responseAdapter.toJson(response)
        } else {
            try {
                executeRequest(url = CDN_URL, forceNetwork = forceRefresh)
            } catch (cdnErr: CancellationException) {
                throw cdnErr
            } catch (cdnErr: Exception) {
                runCatching {
                    Log.w(TAG, "jsDelivr CDN fetch failed (${cdnErr.message}), failing over to GitHub Raw")
                }
                val fallback = if (forceRefresh) "$FALLBACK_URL?t=${clock()}" else FALLBACK_URL
                executeRequest(url = fallback, forceNetwork = forceRefresh)
            }
        }

        val parsed = parseValidatedItems(rawJson)
        if (parsed.isEmpty()) {
            throw IOException("Fetched static wallpaper JSON contained 0 valid wallpaper items.")
        }

        localCache.saveJson(rawJson)
        memoryItems = parsed
        val now = clock()
        lastFetchedAtMs = now
        diskLastModifiedAtMs = now
        _catalogUpdates.value = parsed
        rawJson
    }

    private fun executeRequest(url: String, forceNetwork: Boolean): String {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", NetworkModule.MEDIA_USER_AGENT)
        if (forceNetwork) {
            requestBuilder.cacheControl(CacheControl.FORCE_NETWORK)
        }
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP Error: ${response.code}")
            }
            val bodyString = response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Empty response body")
            // Guard against CDN returning an HTML error page with 200 status.
            if (parseValidatedItems(bodyString).isEmpty()) {
                throw IOException("Response body did not contain valid static wallpaper JSON")
            }
            return bodyString
        }
    }

    /**
     * Loads previously persisted `static_wallpapers_v1.json` from [localCache] into RAM on [Dispatchers.IO].
     */
    suspend fun hydrateFromDiskIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (memoryItems.isNotEmpty()) return@withContext true
        if (!diskHydrationAttempted.compareAndSet(false, true)) {
            return@withContext memoryItems.isNotEmpty()
        }

        val parsed = runCatching {
            val rawJson = localCache.getJson() ?: return@runCatching emptyList()
            parseValidatedItems(rawJson)
        }.getOrElse { err ->
            runCatching { Log.w(TAG, "Failed to hydrate static catalog local cache: ${err.message}") }
            emptyList()
        }

        if (parsed.isEmpty()) return@withContext false
        if (memoryItems.isEmpty()) {
            memoryItems = parsed
            diskLastModifiedAtMs = localCache.getLastUpdatedAtMs()
            _catalogUpdates.value = parsed
        }
        true
    }

    fun isCacheFresh(nowMillis: Long = clock()): Boolean {
        if (memoryItems.isEmpty()) return false
        val effectiveTimestamp = maxOf(lastFetchedAtMs, diskLastModifiedAtMs)
        if (effectiveTimestamp == Long.MIN_VALUE) return false
        val age = nowMillis - effectiveTimestamp
        return age in 0L until CATALOG_FRESH_TTL_MS
    }

    /**
     * Instant local-only snapshot (RAM -> LocalCache on Dispatchers.IO, 0 network calls) for Home Screen.
     */
    suspend fun getCachedPageSnapshot(
        filter: WallpaperFilter,
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): StaticCatalogPageResult = withContext(Dispatchers.IO) {
        if (memoryItems.isEmpty()) {
            hydrateFromDiskIfNeeded()
        }
        slicePage(
            sourceItems = memoryItems,
            filter = filter,
            page = page,
            pageSize = pageSize,
            isFromCache = true,
        )
    }

    /**
     * Loads a page of the Home Screen static catalog from RAM/LocalCache or jsDelivr CDN
     * (with GitHub Raw failover).
     */
    suspend fun getHomePage(
        filter: WallpaperFilter,
        page: Int = 1,
        forceRefresh: Boolean = false,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): StaticCatalogPageResult = withContext(Dispatchers.IO) {
        if (memoryItems.isEmpty()) {
            hydrateFromDiskIfNeeded()
        }

        val now = clock()
        var servedFromCache = true

        if (forceRefresh && page == 1) {
            val withinCooldown = lastFetchedAtMs != Long.MIN_VALUE &&
                (now - lastFetchedAtMs) in 0L until REFRESH_COOLDOWN_MS
            val shuffleSeed = ++refreshShuffleCounter * 7919L + now
            if (withinCooldown && memoryItems.isNotEmpty()) {
                val shuffled = memoryItems.shuffled(Random(shuffleSeed))
                memoryItems = shuffled
                _catalogUpdates.value = shuffled
                servedFromCache = true
            } else {
                val refreshed = ensureCatalogLoaded(forceNetwork = true, shuffleSeed = shuffleSeed)
                servedFromCache = !refreshed
                if (!refreshed && memoryItems.isNotEmpty()) {
                    val shuffled = memoryItems.shuffled(Random(shuffleSeed))
                    memoryItems = shuffled
                    _catalogUpdates.value = shuffled
                }
            }
        } else if (memoryItems.isEmpty() || !isCacheFresh(now)) {
            val fetched = ensureCatalogLoaded(forceNetwork = memoryItems.isEmpty())
            servedFromCache = !fetched
        }

        slicePage(
            sourceItems = memoryItems,
            filter = filter,
            page = page,
            pageSize = pageSize,
            isFromCache = servedFromCache,
        )
    }

    suspend fun warmup(): Boolean = withContext(Dispatchers.IO) {
        hydrateFromDiskIfNeeded()
        if (isCacheFresh()) return@withContext true
        ensureCatalogLoaded(forceNetwork = false)
    }

    /**
     * Coordinates serialized network fetch -> JSON validation -> localCache persistence -> RAM/Flow emit.
     */
    private suspend fun ensureCatalogLoaded(
        forceNetwork: Boolean,
        shuffleSeed: Long? = null,
        silentRevalidate: Boolean = false,
    ): Boolean = fetchMutex.withLock {
        val now = clock()
        if (!forceNetwork && !silentRevalidate && isCacheFresh(now)) {
            return@withLock false
        }

        val succeeded = try {
            fetchWallpapersJson(forceRefresh = forceNetwork && !silentRevalidate)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Static catalog fetch failed (CDN + Raw fallback): ${e.message}") }
            false
        }

        if (!succeeded || memoryItems.isEmpty()) return@withLock false

        if (shuffleSeed != null) {
            val shuffled = memoryItems.shuffled(Random(shuffleSeed))
            memoryItems = shuffled
            _catalogUpdates.value = shuffled
        }

        runCatching {
            val sample = memoryItems.firstOrNull()
            Log.d(
                TAG,
                "Loaded ${memoryItems.size} R2 static wallpapers via CDN/Raw (sample thumb=${sample?.thumbnailUrl}, image=${sample?.imageUrl})",
            )
        }
        true
    }

    private suspend fun fetchViaRetrofitCdnThenRaw(): StaticCatalogResponse? {
        val injectedApi = api ?: return null
        val cdnResult = try {
            val resp = injectedApi.fetchStaticCatalogCdn()
            if (!resp.isSuccessful) {
                runCatching { Log.w(TAG, "jsDelivr CDN static catalog HTTP ${resp.code()}: ${resp.message()}") }
                null
            } else {
                resp.body()?.takeIf { it.resolvedItems().isNotEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "jsDelivr CDN static catalog fetch failed: ${e.message}", e) }
            null
        }
        if (cdnResult != null) return cdnResult

        return try {
            val resp = injectedApi.fetchStaticCatalogRaw()
            if (!resp.isSuccessful) {
                runCatching { Log.w(TAG, "GitHub Raw static catalog HTTP ${resp.code()}: ${resp.message()}") }
                null
            } else {
                resp.body()?.takeIf { it.resolvedItems().isNotEmpty() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "GitHub Raw static catalog fallback failed: ${e.message}", e) }
            null
        }
    }

    private fun parseValidatedItems(rawJson: String): List<AppWallpaper> {
        val response = runCatching { responseAdapter.fromJson(rawJson) }.getOrNull() ?: return emptyList()
        return mapDtosToWallpapers(response.resolvedItems())
    }

    private fun mapDtosToWallpapers(dtos: List<StaticCatalogItemDto>): List<AppWallpaper> {
        return dtos.asSequence()
            .mapNotNull { dto ->
                val resolvedImage = dto.resolvedImageUrl() ?: dto.resolvedThumbnailUrl() ?: return@mapNotNull null
                val resolvedThumb = dto.resolvedThumbnailUrl() ?: resolvedImage
                val resolvedId = dto.resolvedId().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val derivedSeries = dto.series?.trim()?.takeIf { it.isNotEmpty() }
                    ?: dto.category?.trim()?.takeIf { it.isNotEmpty() }
                    ?: inferSeriesFromR2Url(resolvedImage)
                    ?: "Featured 4K"
                val derivedTitle = dto.title?.trim()?.takeIf { it.isNotEmpty() } ?: derivedSeries
                AppWallpaper(
                    id = resolvedId,
                    title = derivedTitle,
                    animeSeries = derivedSeries,
                    imageUrl = resolvedImage,
                    thumbnailUrl = resolvedThumb,
                    videoUrl = null,
                    isLive = false,
                    width = (dto.width ?: 1080).coerceAtLeast(1080),
                    height = (dto.height ?: 1920).coerceAtLeast(1920),
                    source = "R2",
                )
            }
            .distinctBy { it.id }
            .toList()
    }

    private fun inferSeriesFromR2Url(url: String): String? {
        val marker = "/wallpapers/"
        val start = url.indexOf(marker, ignoreCase = true)
        if (start < 0) return null
        val after = url.substring(start + marker.length)
        val folder = after.substringBefore('/', missingDelimiterValue = "").trim()
        if (folder.isEmpty() || folder.contains('.')) return null
        return folder.split('_', '-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
            .takeIf { it.isNotBlank() }
    }

    private fun slicePage(
        sourceItems: List<AppWallpaper>,
        filter: WallpaperFilter,
        page: Int,
        pageSize: Int,
        isFromCache: Boolean,
    ): StaticCatalogPageResult {
        val filtered = filterByResolution(sourceItems, filter)
        if (filtered.isEmpty()) {
            return StaticCatalogPageResult(
                items = emptyList(),
                totalCount = 0,
                pageCount = 0,
                isFromCache = isFromCache,
                isBeyondCatalog = false,
            )
        }

        val safePageSize = pageSize.coerceAtLeast(1)
        val totalPages = (filtered.size + safePageSize - 1) / safePageSize
        val start = ((page - 1).coerceAtLeast(0)) * safePageSize
        if (start >= filtered.size) {
            return StaticCatalogPageResult(
                items = emptyList(),
                totalCount = filtered.size,
                pageCount = totalPages,
                isFromCache = isFromCache,
                isBeyondCatalog = true,
            )
        }

        val end = (start + safePageSize).coerceAtMost(filtered.size)
        return StaticCatalogPageResult(
            items = filtered.subList(start, end).toList(),
            totalCount = filtered.size,
            pageCount = totalPages,
            isFromCache = isFromCache,
            isBeyondCatalog = false,
        )
    }

    private fun filterByResolution(
        items: List<AppWallpaper>,
        filter: WallpaperFilter,
    ): List<AppWallpaper> {
        return when (filter.resolution) {
            ResolutionTier.ALL,
            ResolutionTier.FHD_1080P,
            -> items

            ResolutionTier.QHD_2K -> items.filter { wp ->
                maxOf(wp.width, wp.height) >= 2560 || minOf(wp.width, wp.height) >= 1440
            }

            ResolutionTier.UHD_4K -> items.filter { wp ->
                maxOf(wp.width, wp.height) >= 3840 || minOf(wp.width, wp.height) >= 2160
            }
        }
    }
}
