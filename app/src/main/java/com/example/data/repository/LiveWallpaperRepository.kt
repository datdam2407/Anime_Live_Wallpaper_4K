package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.LiveWallpaperDownloader
import com.example.data.remote.LiveCatalogApi
import com.example.data.remote.LiveCatalogResponse
import com.example.data.remote.LiveWallpaperItem
import com.example.data.remote.NetworkClient
import com.example.domain.model.AppWallpaper
import com.example.domain.model.FacetType
import com.example.domain.model.LiveCatalogFacets
import com.example.domain.model.LiveFilterState
import com.example.domain.model.LiveSearchSuggestion
import com.example.domain.model.LiveTagChip
import com.example.domain.model.SearchSuggestionType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

data class LivePageResult(
    val items: List<AppWallpaper>,
    val hasMore: Boolean,
    val totalCount: Int,
)

/**
 * Coordinates the jsDelivr / GitHub Raw + Cloudflare R2 Live Wallpaper pipeline:
 * 1. Grid: [LiveWallpaperItem.thumbUrl] (~35 KB)
 * 2. Detail Preview: [LiveWallpaperItem.previewUrl] (~350 KB)
 * 3. Apply / Save Offline: [LiveWallpaperItem.fullUrl] (~1–8 MB) cached once in filesDir/live_wallpapers/
 * 4. Persistent Disk JSON Cache + RAM pre-normalized index for <2ms in-memory filtering across
 *    category, series, tags, title, and slug from live_wallpapers_v1.json.
 */
class LiveWallpaperRepository(
    private val api: LiveCatalogApi,
    private val downloader: LiveWallpaperDownloader,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val catalogCacheFile: File? = null,
    private val fallbackHeadStartMs: Long = DEFAULT_FALLBACK_HEAD_START_MS,
) {
    constructor(context: Context) : this(
        api = NetworkClient.createLiveCatalogApi(context),
        downloader = LiveWallpaperDownloader(context),
        catalogCacheFile = File(context.applicationContext.filesDir, DEFAULT_DISK_CACHE_FILE_NAME),
    )

    companion object {
        private const val DEBUG_TAG = "LIVE_DEBUG"
        const val DEFAULT_PAGE_SIZE = 45

        private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
        private val TOKEN_SPLIT_REGEX = Regex("[^a-z0-9]+")
        private val ANIME_SYNONYM_GROUPS: List<List<String>> = listOf(
            listOf("demonslayer", "kimetsunoyaiba", "kimetsu", "tanjiro", "nezuko"),
            listOf("jujutsukaisen", "jjk", "jujutsu", "gojo", "sukuna"),
            listOf("attackontitan", "aot", "shingeki", "shingekinokyojin", "eren", "levi"),
            listOf("sololeveling", "jinwoo", "sungjinwoo", "arise"),
            listOf("frieren", "sousounofrieren", "beyondjourneysend"),
            listOf("onepiece", "luffy", "zoro"),
            listOf("myheroacademia", "mha", "bnha", "bokunohero"),
            listOf("chainsawman", "csm", "denji", "makima"),
            listOf("dragonball", "dbz", "dbs", "goku", "vegeta"),
            listOf("rezero", "zerokara", "rem", "emilia"),
            listOf("bleach", "ichigo", "renji", "bankai"),
            listOf("cyberpunk", "edgerunners", "lucy", "david"),
            listOf("naruto", "shippuden", "sasuke", "itachi", "kakashi"),
            listOf("totoro", "ghibli", "studioghibli"),
            // Color palette synonyms for Trending Color search pills
            listOf("black", "dark", "night", "shadow", "obsidian"),
            listOf("blackandwhite", "monochrome", "bw", "grayscale", "manga"),
            listOf("white", "snow", "silver", "moonlight", "light"),
            listOf("red", "crimson", "scarlet", "flame", "fire"),
            listOf("blue", "cyan", "ocean", "sky", "ice", "azure"),
            listOf("yellow", "gold", "golden", "amber", "sun"),
            listOf("green", "emerald", "forest", "jade", "nature"),
            listOf("pink", "sakura", "cherry", "rose", "pastel"),
            listOf("purple", "violet", "hollow", "lavender", "neon"),
            listOf("orange", "sunset", "autumn", "blaze"),
            // Explore domain synonyms for the 12 Trending Categories
            listOf("games", "gaming", "videogames", "game", "genshin", "fantasy", "magic", "action"),
            listOf("city", "cityscape", "urban", "tokyo", "street", "cyberpunk", "neon", "night"),
            listOf("cars", "car", "sportscar", "racing", "drift", "jdm", "vehicle"),
            listOf("nature", "landscape", "forest", "mountain", "ocean", "sky", "moonlight", "moon"),
            listOf("tvmovies", "movies", "cinema", "film", "series", "cinematic"),
            listOf("cute", "kawaii", "chibi", "pastel", "wholesome", "sakura"),
            listOf("scifi", "futuristic", "cyber", "cyberpunk", "mecha", "space", "neon"),
            listOf("abstract", "minimal", "geometric", "art", "purple", "dark", "aesthetic"),
            listOf("animals", "wildlife", "cat", "wolf", "dragon", "pet", "fox"),
            listOf("comics", "manga", "superhero", "comic", "sword", "shonen"),
            listOf("lifestyle", "aesthetic", "chill", "cozy", "lofi", "sunset"),
        )

        /**
         * Color palette synonym expansion map for Trending Color pills (OR match across JSON tags/titles/slugs).
         */
        internal val COLOR_SYNONYM_MAP: Map<String, List<String>> = mapOf(
            "black" to listOf("black", "dark", "amoled", "night", "shadow"),
            "black and white" to listOf("monochrome", "grayscale", "black and white", "pencil sketch", "manga", "bw"),
            "blue" to listOf("blue", "cyan", "ice", "ocean", "water"),
            "purple" to listOf("purple", "violet", "hollow purple", "neon"),
            "pink" to listOf("pink", "sakura", "cherry blossom", "rose", "pastel"),
            "red" to listOf("red", "crimson", "blood", "fire", "flames"),
            "yellow" to listOf("yellow", "gold", "golden", "lightning", "sun"),
            "green" to listOf("green", "emerald", "forest", "nature"),
            "orange" to listOf("orange", "sunset", "autumn", "fire"),
            "white" to listOf("white", "snow", "clouds", "silver"),
        )

        private val GENERIC_NOISE_TAGS: Set<String> = setOf(
            "4k", "hd", "uhd", "fhd", "2k", "8k",
            "live", "livewallpaper", "wallpaper", "wallpapers",
            "video", "mp4", "loop", "animated", "animation", "moving",
            "phone", "mobile", "vertical", "portrait", "background",
        )
        private const val DISPLAY_TAG_LIMIT = 18
        const val LIVE_CATALOG_REFRESH_COOLDOWN_MS = 60_000L
        const val DEFAULT_DISK_CACHE_FILE_NAME = "live_catalog_cache_v1.json"
        const val DEFAULT_FALLBACK_HEAD_START_MS = 800L

        private val catalogMoshi: Moshi by lazy {
            Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()
        }
        private val catalogJsonAdapter by lazy {
            catalogMoshi.adapter(LiveCatalogResponse::class.java)
        }
    }

    private class IndexedLiveItem(
        val item: LiveWallpaperItem,
        val catCollapsed: String,
        val seriesCollapsed: String,
        val titleCollapsed: String,
        val slugCollapsed: String,
        val idCollapsed: String,
        val tagCollapsedList: List<String>,
        val fieldTokens: Set<String>,
        val combinedCollapsed: String,
    )

    private val catalogMutex = Mutex()
    private val diskHydrationAttempted = AtomicBoolean(false)
    private val backgroundRefreshInFlight = AtomicBoolean(false)
    @Volatile
    private var memoryCatalog: List<LiveWallpaperItem> = emptyList()
    @Volatile
    private var indexedCatalog: List<IndexedLiveItem> = emptyList()
    private val liveCoverUrlCache = ConcurrentHashMap<String, List<String>>()
    @Volatile
    private var lastCatalogFetchedAtMs: Long = Long.MIN_VALUE
    @Volatile
    private var feedShuffleCounter: Int = 0
    @Volatile
    private var tagRotationCounter: Int = 0
    @Volatile
    private var lastDisplayedTagKeys: Set<String> = emptySet()
    @Volatile
    private var activeRotationDeprioritizedKeys: Set<String> = emptySet()

    private val _catalogFacets = MutableStateFlow(LiveCatalogFacets())
    val catalogFacets: StateFlow<LiveCatalogFacets> = _catalogFacets.asStateFlow()

    val hasCatalogInMemory: Boolean
        get() = memoryCatalog.isNotEmpty()

    /**
     * Immediately hydrates [memoryCatalog] from the persistent local JSON cache on [Dispatchers.IO]
     * (typically <15ms) and launches a non-blocking network race refresh in the background so Live
     * Wallpapers are ready instantaneously when the user switches tabs.
     */
    fun prefetchCatalog(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            hydrateFromDiskIfNeeded()
            if (backgroundRefreshInFlight.compareAndSet(false, true)) {
                try {
                    fetchLiveWallpapers(forceRefresh = false, allowNetworkRefreshOverDisk = true)
                } finally {
                    backgroundRefreshInFlight.set(false)
                }
            }
        }
    }

    /**
     * Loads previously persisted `live_wallpapers_v1.json` from local disk into RAM + search index
     * so cold starts have instant 0ms live catalog access without waiting for the network.
     */
    suspend fun hydrateFromDiskIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        if (memoryCatalog.isNotEmpty()) return@withContext true
        val cacheFile = catalogCacheFile ?: return@withContext false
        if (!diskHydrationAttempted.compareAndSet(false, true)) {
            return@withContext memoryCatalog.isNotEmpty()
        }
        if (!cacheFile.exists() || cacheFile.length() <= 0L) return@withContext false
        val parsedItems = runCatching {
            val rawJson = cacheFile.readText(Charsets.UTF_8)
            val parsed = catalogJsonAdapter.fromJson(rawJson)
            sanitizeCatalogItems(parsed?.wallpapers.orEmpty())
        }.getOrElse { err ->
            runCatching { Log.w(DEBUG_TAG, "Failed to hydrate live catalog disk cache: ${err.message}") }
            emptyList()
        }
        if (parsedItems.isEmpty()) return@withContext false
        if (memoryCatalog.isEmpty()) {
            publishCatalogToMemory(parsedItems, fetchedFromNetwork = false)
            runCatching {
                Log.d(DEBUG_TAG, "Hydrated ${parsedItems.size} live wallpapers from disk cache instantly")
            }
        }
        true
    }

    private fun sanitizeCatalogItems(rawList: List<LiveWallpaperItem>): List<LiveWallpaperItem> {
        return rawList.filter { item ->
            !item.id.isNullOrBlank() &&
                (!item.thumbUrl.isNullOrBlank() || !item.previewUrl.isNullOrBlank()) &&
                !item.fullUrl.isNullOrBlank()
        }
    }

    private fun publishCatalogToMemory(
        items: List<LiveWallpaperItem>,
        fetchedFromNetwork: Boolean,
    ) {
        val indexed = items.map { buildIndexedItem(it) }
        memoryCatalog = items
        indexedCatalog = indexed
        if (fetchedFromNetwork) {
            lastCatalogFetchedAtMs = clock()
        }
        liveCoverUrlCache.clear()
        val rotationSeed = tagRotationCounter
        val nextFacets = extractCatalogFacets(
            items = items,
            rotationSeed = rotationSeed,
            deprioritizeTagKeys = if (rotationSeed > 0) activeRotationDeprioritizedKeys else emptySet(),
        )
        if (lastDisplayedTagKeys.isEmpty()) {
            lastDisplayedTagKeys = nextFacets.displayTags.map { normalize(it.query) }.toSet()
        }
        _catalogFacets.value = nextFacets
    }

    private fun persistCatalogToDisk(response: LiveCatalogResponse) {
        val targetFile = catalogCacheFile ?: return
        runCatching {
            targetFile.parentFile?.mkdirs()
            val serialized = catalogJsonAdapter.toJson(response)
            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            tempFile.writeText(serialized, Charsets.UTF_8)
            if (!tempFile.renameTo(targetFile)) {
                targetFile.writeText(serialized, Charsets.UTF_8)
                tempFile.delete()
            }
        }.onFailure { err ->
            runCatching { Log.w(DEBUG_TAG, "Unable to persist live catalog cache: ${err.message}") }
        }
    }

    suspend fun fetchLiveWallpapers(
        forceRefresh: Boolean = false,
        allowNetworkRefreshOverDisk: Boolean = false,
    ): Result<List<LiveWallpaperItem>> =
        withContext(Dispatchers.IO) {
            if (memoryCatalog.isEmpty()) {
                hydrateFromDiskIfNeeded()
            }
            val nowMs = clock()
            val fetchedFromNetworkRecently = lastCatalogFetchedAtMs != Long.MIN_VALUE &&
                (nowMs - lastCatalogFetchedAtMs) in 0L until LIVE_CATALOG_REFRESH_COOLDOWN_MS
            val shouldReturnMemoryImmediately = memoryCatalog.isNotEmpty() && when {
                forceRefresh -> fetchedFromNetworkRecently
                allowNetworkRefreshOverDisk -> lastCatalogFetchedAtMs != Long.MIN_VALUE
                else -> true
            }
            if (shouldReturnMemoryImmediately) {
                if (_catalogFacets.value.totalWallpapers != memoryCatalog.size) {
                    _catalogFacets.value = extractCatalogFacets(memoryCatalog)
                }
                return@withContext Result.success(memoryCatalog)
            }
            catalogMutex.withLock {
                val lockedNowMs = clock()
                val lockedWithinCooldown = lastCatalogFetchedAtMs != Long.MIN_VALUE &&
                    (lockedNowMs - lastCatalogFetchedAtMs) in 0L until LIVE_CATALOG_REFRESH_COOLDOWN_MS
                val lockedShouldReturnMemory = memoryCatalog.isNotEmpty() && when {
                    forceRefresh -> lockedWithinCooldown
                    allowNetworkRefreshOverDisk -> lastCatalogFetchedAtMs != Long.MIN_VALUE
                    else -> true
                }
                if (lockedShouldReturnMemory) {
                    if (_catalogFacets.value.totalWallpapers != memoryCatalog.size) {
                        _catalogFacets.value = extractCatalogFacets(memoryCatalog)
                    }
                    return@withLock Result.success(memoryCatalog)
                }
                try {
                    val response = fetchCatalogWithFallback()
                    val rawList = response.wallpapers.orEmpty()
                    val items = sanitizeCatalogItems(rawList)
                    runCatching {
                        Log.d(
                            DEBUG_TAG,
                            "Fetched R2 live catalog: raw=${rawList.size}, valid=${items.size}, " +
                                "firstThumb=${items.firstOrNull()?.thumbUrl}, " +
                                "firstPreview=${items.firstOrNull()?.previewUrl}, " +
                                "firstFull=${items.firstOrNull()?.fullUrl}",
                        )
                    }
                    if (items.isNotEmpty()) {
                        publishCatalogToMemory(items, fetchedFromNetwork = true)
                        persistCatalogToDisk(response.copy(wallpapers = items, total = items.size))
                        Result.success(items)
                    } else if (memoryCatalog.isNotEmpty()) {
                        Result.success(memoryCatalog)
                    } else {
                        Result.failure(
                            IllegalStateException(
                                "live_wallpapers_v1.json parsed ${rawList.size} entries but 0 had valid id/thumbUrl/fullUrl.",
                            ),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runCatching {
                        Log.e(
                            DEBUG_TAG,
                            "fetchLiveWallpapers failed (${e.javaClass.simpleName}): ${e.message}",
                            e,
                        )
                    }
                    if (memoryCatalog.isNotEmpty()) {
                        Result.success(memoryCatalog)
                    } else {
                        Result.failure(e)
                    }
                }
            }
        }

    /**
     * Staggered parallel race between jsDelivr CDN and GitHub Raw mirror:
     * - jsDelivr starts immediately; if it responds within [fallbackHeadStartMs], GitHub Raw is never called.
     * - If jsDelivr fails early OR stalls past [fallbackHeadStartMs], GitHub Raw races in parallel;
     *   whichever returns a valid non-empty catalog first wins immediately and cancels the slower request.
     */
    private suspend fun fetchCatalogWithFallback(): LiveCatalogResponse = supervisorScope {
        val winner = CompletableDeferred<LiveCatalogResponse>()
        val wakeFallback = CompletableDeferred<Unit>()
        val primaryErrorRef = AtomicReference<Throwable?>(null)
        val fallbackErrorRef = AtomicReference<Throwable?>(null)
        val emptyResponseRef = AtomicReference<LiveCatalogResponse?>(null)

        val primaryJob = launch {
            try {
                val primaryResponse = api.getLiveCatalog()
                if (primaryResponse.wallpapers.orEmpty().isNotEmpty()) {
                    winner.complete(primaryResponse)
                } else {
                    emptyResponseRef.compareAndSet(null, primaryResponse)
                    wakeFallback.complete(Unit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (primaryError: Throwable) {
                primaryErrorRef.set(primaryError)
                runCatching {
                    Log.w(
                        DEBUG_TAG,
                        "jsDelivr getLiveCatalog failed (${primaryError.javaClass.simpleName}): ${primaryError.message}. Racing GitHub Raw mirror...",
                    )
                }
                wakeFallback.complete(Unit)
            }
        }

        val fallbackJob = launch {
            if (fallbackHeadStartMs > 0L) {
                withTimeoutOrNull(fallbackHeadStartMs) {
                    wakeFallback.await()
                }
            }
            if (winner.isCompleted) return@launch
            try {
                val fallbackResponse = api.getLiveCatalogByUrl(LiveCatalogApi.GITHUB_RAW_FALLBACK_URL)
                if (fallbackResponse.wallpapers.orEmpty().isNotEmpty()) {
                    winner.complete(fallbackResponse)
                } else {
                    emptyResponseRef.compareAndSet(null, fallbackResponse)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (fallbackError: Throwable) {
                fallbackErrorRef.set(fallbackError)
                runCatching {
                    Log.e(
                        DEBUG_TAG,
                        "GitHub Raw fallback failed (${fallbackError.javaClass.simpleName}): ${fallbackError.message}",
                        fallbackError,
                    )
                }
            }
        }

        val completionWatcher = launch {
            primaryJob.join()
            fallbackJob.join()
            if (!winner.isCompleted) {
                val empty = emptyResponseRef.get()
                if (empty != null) {
                    winner.complete(empty)
                } else {
                    val failure = primaryErrorRef.get()
                        ?: fallbackErrorRef.get()
                        ?: IllegalStateException("Both jsDelivr and GitHub Raw live catalog mirrors failed.")
                    winner.completeExceptionally(failure)
                }
            }
        }

        try {
            winner.await()
        } finally {
            completionWatcher.cancel()
            primaryJob.cancel()
            fallbackJob.cancel()
        }
    }

    suspend fun getLiveWallpapersPage(
        query: String,
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        forceRefresh: Boolean = false,
    ): Result<LivePageResult> {
        val catalogResult = fetchLiveWallpapers(forceRefresh = forceRefresh)
        val items = catalogResult.getOrElse { error ->
            return Result.failure(error)
        }

        return withContext(Dispatchers.Default) {
            val filtered = filterAndRankItems(items, query)
            val ordered = applyFeedShuffle(
                items = filtered,
                seedKey = query.trim().lowercase(),
                preserveRelevanceTiers = query.isNotBlank() &&
                    !query.equals(WallpaperRepository.HOME_TRENDING_KEY, ignoreCase = true),
            )
            Result.success(paginateItems(ordered, page, pageSize))
        }
    }

    suspend fun getLiveWallpapersPage(
        filterState: LiveFilterState,
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        forceRefresh: Boolean = false,
    ): Result<LivePageResult> {
        val catalogResult = fetchLiveWallpapers(forceRefresh = forceRefresh)
        val items = catalogResult.getOrElse { error ->
            return Result.failure(error)
        }

        return withContext(Dispatchers.Default) {
            val filtered = filterAndRankItems(items, filterState)
            val ordered = applyFeedShuffle(
                items = filtered,
                seedKey = filterState.cacheKey(),
                preserveRelevanceTiers = filterState.query.isNotBlank() || filterState.selectedTags.isNotEmpty(),
            )
            Result.success(paginateItems(ordered, page, pageSize))
        }
    }

    fun getCachedCatalogSnapshot(
        query: String,
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): LivePageResult? {
        val items = memoryCatalog
        if (items.isEmpty()) return null
        val filtered = filterAndRankItems(items, query)
        val ordered = applyFeedShuffle(
            items = filtered,
            seedKey = query.trim().lowercase(),
            preserveRelevanceTiers = query.isNotBlank() &&
                !query.equals(WallpaperRepository.HOME_TRENDING_KEY, ignoreCase = true),
        )
        return paginateItems(ordered, page, pageSize)
    }

    fun getCachedCatalogSnapshot(
        filterState: LiveFilterState,
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): LivePageResult? {
        val items = memoryCatalog
        if (items.isEmpty()) return null
        val filtered = filterAndRankItems(items, filterState)
        val ordered = applyFeedShuffle(
            items = filtered,
            seedKey = filterState.cacheKey(),
            preserveRelevanceTiers = filterState.query.isNotBlank() || filterState.selectedTags.isNotEmpty(),
        )
        return paginateItems(ordered, page, pageSize)
    }

    /**
     * Deterministically shuffles the live catalog after a user-initiated refresh so the grid shows
     * brand-new videos at the top while keeping pagination (page 1, 2, 3...) duplicate-free.
     */
    private fun applyFeedShuffle(
        items: List<LiveWallpaperItem>,
        seedKey: String,
        preserveRelevanceTiers: Boolean,
    ): List<LiveWallpaperItem> {
        val shuffleVersion = feedShuffleCounter
        if (shuffleVersion <= 0 || items.size <= 1) return items
        val rng = Random(shuffleVersion * 31337L + seedKey.hashCode().toLong() * 131L + items.size)
        return if (preserveRelevanceTiers && items.size > 24) {
            items.chunked(24).flatMap { band -> band.shuffled(rng) }
        } else {
            items.shuffled(rng)
        }
    }

    private fun paginateItems(
        filtered: List<LiveWallpaperItem>,
        page: Int,
        pageSize: Int,
    ): LivePageResult {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 50)
        val totalCount = filtered.size
        val fromIndex = (safePage - 1) * safeSize
        if (fromIndex >= totalCount) {
            return LivePageResult(emptyList(), hasMore = false, totalCount = totalCount)
        }
        val toIndex = minOf(fromIndex + safeSize, totalCount)
        return LivePageResult(
            items = filtered.subList(fromIndex, toIndex).map { it.toAppWallpaper() },
            hasMore = toIndex < totalCount,
            totalCount = totalCount,
        )
    }

    /**
     * Returns distinct sanitized R2 thumbnail URLs for live wallpapers matching [query]
     * using the exact same relevance matcher as Live tab search, ensuring every LIVE-badged
     * category card is guaranteed to return live videos when clicked.
     */
    fun getMatchingLiveCoverUrls(query: String, limit: Int = 10): List<String> {
        val items = memoryCatalog
        if (items.isEmpty()) return emptyList()
        val cacheKey = "${query.trim().lowercase()}|$limit"
        liveCoverUrlCache[cacheKey]?.let { return it }
        val resolved = filterAndRankItems(items, query)
            .mapNotNull { it.toAppWallpaper().thumbnailUrl.takeIf { url -> url.isNotBlank() } }
            .distinct()
            .take(limit)
        liveCoverUrlCache[cacheKey] = resolved
        return resolved
    }

    /**
     * Produces instant search autocomplete suggestions from the loaded live JSON catalog
     * across categories, #tags, and wallpaper titles.
     */
    fun getSearchSuggestions(rawInput: String, limit: Int = 8): List<LiveSearchSuggestion> {
        val trimmed = rawInput.trim().removePrefix("#").trim()
        if (trimmed.isEmpty()) return emptyList()
        val items = memoryCatalog
        if (items.isEmpty()) return emptyList()

        val collapsedInput = normalize(trimmed)
        val inputTokens = tokenize(trimmed)
        if (collapsedInput.isEmpty() && inputTokens.isEmpty()) return emptyList()

        val facets = _catalogFacets.value.takeIf { it.totalWallpapers == items.size }
            ?: extractCatalogFacets(items)
        val suggestions = LinkedHashMap<String, LiveSearchSuggestion>()

        fun matchesFacet(value: String): Boolean {
            val normalizedVal = normalize(value)
            if (normalizedVal.contains(collapsedInput)) return true
            val valTokens = tokenize(value)
            return inputTokens.isNotEmpty() && inputTokens.all { qTok ->
                valTokens.any { it.contains(qTok) } || normalizedVal.contains(qTok)
            }
        }

        // 1. Matching categories first
        facets.categories
            .filter { matchesFacet(it.label) }
            .forEach { chip ->
                val key = "cat:${normalize(chip.label)}"
                if (suggestions.size < limit) {
                    suggestions[key] = LiveSearchSuggestion(
                        label = chip.label,
                        query = chip.query,
                        type = SearchSuggestionType.CATEGORY,
                        count = chip.count,
                    )
                }
            }

        // 2. Matching series second (highest-intent anime/game franchises)
        facets.series
            .filter { matchesFacet(it.label) }
            .forEach { chip ->
                val key = "series:${normalize(chip.label)}"
                if (suggestions.size < limit) {
                    suggestions[key] = LiveSearchSuggestion(
                        label = chip.label,
                        query = chip.query,
                        type = SearchSuggestionType.SERIES,
                        count = chip.count,
                    )
                }
            }

        // 3. Matching #tags third
        facets.tags
            .filter { matchesFacet(it.query) }
            .forEach { chip ->
                val key = "tag:${normalize(chip.query)}"
                if (suggestions.size < limit) {
                    suggestions[key] = LiveSearchSuggestion(
                        label = chip.label,
                        query = chip.query,
                        type = SearchSuggestionType.TAG,
                        count = chip.count,
                    )
                }
            }

        // 4. Matching wallpaper titles fourth
        items.forEach { item ->
            if (suggestions.size >= limit) return@forEach
            val title = item.title?.trim().orEmpty()
            if (title.isNotEmpty() && matchesFacet(title)) {
                val key = "title:${normalize(title)}"
                if (key !in suggestions) {
                    suggestions[key] = LiveSearchSuggestion(
                        label = title,
                        query = title,
                        type = SearchSuggestionType.TITLE,
                        count = 1,
                    )
                }
            }
        }

        return suggestions.values.toList()
    }

    /**
     * Rotates both the displayed distinct #Tag chips AND the live video feed order immediately
     * from the in-memory catalog (0 network requests needed during the 60s CDN cooldown).
     */
    fun rotateContentAndTags() {
        feedShuffleCounter++
        rotateDisplayTags()
    }

    /**
     * Rotates the displayed distinct #Tag chips immediately from the in-memory catalog
     * (deprioritizing previously displayed tags so pull-to-refresh / Refresh surfaces fresh themes).
     */
    fun rotateDisplayTags() {
        val items = memoryCatalog
        if (items.isEmpty()) return
        val nextSeed = ++tagRotationCounter
        val deprioritized = lastDisplayedTagKeys
        activeRotationDeprioritizedKeys = deprioritized
        val nextFacets = extractCatalogFacets(
            items = items,
            rotationSeed = nextSeed,
            deprioritizeTagKeys = deprioritized,
        )
        lastDisplayedTagKeys = nextFacets.displayTags.map { normalize(it.query) }.toSet()
        _catalogFacets.value = nextFacets
    }

    internal fun extractCatalogFacets(
        items: List<LiveWallpaperItem>,
        rotationSeed: Int = 0,
        deprioritizeTagKeys: Set<String> = emptySet(),
    ): LiveCatalogFacets {
        if (items.isEmpty()) return LiveCatalogFacets()

        val categoryCounts = LinkedHashMap<String, Triple<String, Int, String?>>()
        val seriesCounts = LinkedHashMap<String, Triple<String, Int, String?>>()
        val tagCounts = LinkedHashMap<String, Pair<String, Int>>()
        val tagItemIds = LinkedHashMap<String, LinkedHashSet<String>>()
        val categoryTagCounts = LinkedHashMap<String, LinkedHashMap<String, Pair<String, Int>>>()
        val categoryTagItemIds = LinkedHashMap<String, LinkedHashMap<String, LinkedHashSet<String>>>()

        items.forEachIndexed { idx, item ->
            val itemId = item.id?.takeIf { it.isNotBlank() } ?: "idx_$idx"
            val rawCat = item.category?.trim().orEmpty()
            val catKey = normalize(rawCat)
            val catLower = rawCat.lowercase()
            val thumb = item.toAppWallpaper().thumbnailUrl.takeIf { it.isNotBlank() }
            if (rawCat.isNotEmpty() && catKey.isNotEmpty()) {
                val existing = categoryCounts[catKey]
                categoryCounts[catKey] = if (existing == null) {
                    Triple(rawCat, 1, thumb)
                } else {
                    Triple(existing.first, existing.second + 1, existing.third ?: thumb)
                }
            }

            // Filter out junk series (null, blank, or duplicating the category name such as "Anime"/"City").
            val rawSeries = item.series?.trim().orEmpty()
            val seriesKey = normalize(rawSeries)
            if (rawSeries.isNotEmpty() && seriesKey.isNotEmpty() && !rawSeries.equals(rawCat, ignoreCase = true)) {
                val existing = seriesCounts[seriesKey]
                seriesCounts[seriesKey] = if (existing == null) {
                    Triple(rawSeries, 1, thumb)
                } else {
                    Triple(existing.first, existing.second + 1, existing.third ?: thumb)
                }
            }

            // Deduplicate tags within a single item so counts represent distinct wallpapers
            val seenTagsInItem = HashSet<String>()
            val perCategoryMap = if (catLower.isNotEmpty()) {
                categoryTagCounts.getOrPut(catLower) { LinkedHashMap() }
            } else {
                null
            }
            val perCategoryIdsMap = if (catLower.isNotEmpty()) {
                categoryTagItemIds.getOrPut(catLower) { LinkedHashMap() }
            } else {
                null
            }
            item.tags.orEmpty().forEach { rawTag ->
                val cleanedTag = rawTag.trim().removePrefix("#").trim()
                val tagKey = normalize(cleanedTag)
                if (cleanedTag.isNotEmpty() && tagKey.length >= 2 && seenTagsInItem.add(tagKey)) {
                    val existing = tagCounts[tagKey]
                    tagCounts[tagKey] = if (existing == null) {
                        cleanedTag to 1
                    } else {
                        existing.first to (existing.second + 1)
                    }
                    tagItemIds.getOrPut(tagKey) { LinkedHashSet() }.add(itemId)

                    if (perCategoryMap != null && perCategoryIdsMap != null) {
                        val existingCatTag = perCategoryMap[tagKey]
                        perCategoryMap[tagKey] = if (existingCatTag == null) {
                            cleanedTag to 1
                        } else {
                            existingCatTag.first to (existingCatTag.second + 1)
                        }
                        perCategoryIdsMap.getOrPut(tagKey) { LinkedHashSet() }.add(itemId)
                    }
                }
            }
        }

        val categoryKeysNormalized = categoryCounts.keys.toSet()

        val categoryChips = categoryCounts.values
            .sortedWith(compareByDescending<Triple<String, Int, String?>> { it.second }.thenBy { it.first.lowercase() })
            .map { (label, count, coverUrl) ->
                LiveTagChip(
                    label = label,
                    query = label,
                    count = count,
                    type = FacetType.CATEGORY,
                    coverUrl = coverUrl,
                )
            }
            .toList()

        val seriesChips = seriesCounts.values
            .sortedWith(compareByDescending<Triple<String, Int, String?>> { it.second }.thenBy { it.first.lowercase() })
            .map { (label, count, coverUrl) ->
                LiveTagChip(
                    label = label,
                    query = label,
                    count = count,
                    type = FacetType.SERIES,
                    coverUrl = coverUrl,
                )
            }
            .toList()

        fun buildSortedTagChips(source: Map<String, Pair<String, Int>>): List<LiveTagChip> =
            source.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Pair<String, Int>>> { it.value.second }
                        .thenBy { it.value.first.lowercase() },
                )
                .map { (_, pair) ->
                    val (rawTag, count) = pair
                    LiveTagChip(
                        label = "#$rawTag",
                        query = rawTag,
                        count = count,
                        type = FacetType.TAG,
                    )
                }
                .toList()

        val tagChips = buildSortedTagChips(tagCounts)
        val diverseDisplayTags = buildDiverseDisplayTags(
            sourceCounts = tagCounts,
            sourceItemIds = tagItemIds,
            categoryKeys = categoryKeysNormalized,
            totalItemsInScope = items.size,
            rotationSeed = rotationSeed,
            deprioritizeTagKeys = deprioritizeTagKeys,
            limit = DISPLAY_TAG_LIMIT,
        )

        val immutableTagsByCategory = categoryTagCounts.entries
            .associate { (catLower, counts) ->
                catLower to buildSortedTagChips(counts).take(24)
            }
            .toMap()

        val immutableDisplayTagsByCategory = categoryTagCounts.entries
            .associate { (catLower, counts) ->
                val catIdsMap = categoryTagItemIds[catLower].orEmpty()
                val catTotal = categoryCounts[normalize(catLower)]?.second ?: items.size
                catLower to buildDiverseDisplayTags(
                    sourceCounts = counts,
                    sourceItemIds = catIdsMap,
                    categoryKeys = categoryKeysNormalized,
                    totalItemsInScope = catTotal,
                    rotationSeed = rotationSeed,
                    deprioritizeTagKeys = deprioritizeTagKeys,
                    limit = DISPLAY_TAG_LIMIT,
                )
            }
            .toMap()

        return LiveCatalogFacets(
            categories = categoryChips,
            series = seriesChips,
            tags = tagChips,
            displayTags = diverseDisplayTags,
            tagsByCategory = immutableTagsByCategory,
            displayTagsByCategory = immutableDisplayTagsByCategory,
            totalWallpapers = items.size,
            tagRotationVersion = rotationSeed,
        )
    }

    /**
     * Selects genuinely distinct, non-overlapping #Tag chips so adjacent pills (e.g., #ninja / #shinobi / #naruto)
     * never return the exact same set of wallpapers. On refresh ([rotationSeed] > 0), unseen distinct themes
     * are prioritized first so pulling to refresh or tapping Refresh always reveals fresh #tags.
     */
    private fun buildDiverseDisplayTags(
        sourceCounts: Map<String, Pair<String, Int>>,
        sourceItemIds: Map<String, Set<String>>,
        categoryKeys: Set<String>,
        totalItemsInScope: Int,
        rotationSeed: Int,
        deprioritizeTagKeys: Set<String>,
        limit: Int,
    ): List<LiveTagChip> {
        if (sourceCounts.isEmpty()) return emptyList()

        // Filter out category-duplicate tags (e.g. #anime when Category "Anime" is already above)
        // and generic boilerplate tags (#4k, #wallpaper, #live) unless nothing else exists.
        val filteredEntries = sourceCounts.entries.filter { (key, pair) ->
            val count = pair.second
            val isBroadNoise = totalItemsInScope >= 10 && count >= (totalItemsInScope * 0.85).toInt()
            key !in GENERIC_NOISE_TAGS && key !in categoryKeys && !isBroadNoise
        }.ifEmpty { sourceCounts.entries.toList() }

        val popularitySorted = filteredEntries.sortedWith(
            compareByDescending<Map.Entry<String, Pair<String, Int>>> { it.value.second }
                .thenBy { it.value.first.lowercase() },
        )

        val orderedCandidates = if (rotationSeed == 0) {
            popularitySorted
        } else {
            // Focus rotation on well-populated tags first (count >= 2 when catalog is large)
            val richPool = popularitySorted.filter { it.value.second >= 2 }.ifEmpty { popularitySorted }
            val rng = Random(rotationSeed * 7919L + totalItemsInScope)
            val unseen = richPool.filter { it.key !in deprioritizeTagKeys }.shuffled(rng)
            val previouslyShown = richPool.filter { it.key in deprioritizeTagKeys }.shuffled(rng)
            unseen + previouslyShown
        }

        val accepted = mutableListOf<LiveTagChip>()
        val acceptedIdSets = mutableListOf<Set<String>>()
        val acceptedTopPairs = mutableListOf<List<String>>()

        for ((tagKey, pair) in orderedCandidates) {
            if (accepted.size >= limit) break
            val ids = sourceItemIds[tagKey].orEmpty()
            val topPair = ids.take(2)

            val isRedundant = acceptedIdSets.indices.any { existingIdx ->
                val existingIds = acceptedIdSets[existingIdx]
                val minSize = minOf(ids.size, existingIds.size)
                if (minSize == 0) {
                    false
                } else {
                    val intersection = if (ids.size <= existingIds.size) {
                        ids.count { it in existingIds }
                    } else {
                        existingIds.count { it in ids }
                    }
                    val union = ids.size + existingIds.size - intersection
                    val containment = intersection.toDouble() / minSize.toDouble()
                    val jaccard = if (union > 0) intersection.toDouble() / union.toDouble() else 0.0
                    val sameTopTwo = topPair.size >= 2 && topPair == acceptedTopPairs[existingIdx]
                    (minSize >= 2 && (containment >= 0.55 || jaccard >= 0.40)) || sameTopTwo
                }
            }

            if (!isRedundant) {
                accepted += LiveTagChip(
                    label = "#${pair.first}",
                    query = pair.first,
                    count = pair.second,
                    type = FacetType.TAG,
                )
                acceptedIdSets += ids
                acceptedTopPairs += topPair
            }
        }

        return accepted.toList()
    }

    fun isDownloaded(id: String): Boolean = downloader.isDownloaded(id)

    fun getLocalFile(id: String): File = downloader.getLocalFile(id)

    suspend fun downloadFullVideo(
        item: LiveWallpaperItem,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = downloader.downloadIfNeeded(
        wallpaperId = item.id.orEmpty(),
        fullUrl = item.fullUrl.orEmpty(),
        onProgress = onProgress,
    )

    suspend fun downloadFullVideo(
        wallpaperId: String,
        fullUrl: String,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = downloader.downloadIfNeeded(wallpaperId, fullUrl, onProgress)

    fun clearDownloadedVideos(
        keepWallpaperId: String? = null,
        keepFilePath: String? = null,
    ): Int = downloader.clearDownloadedVideos(
        keepWallpaperId = keepWallpaperId,
        keepFilePath = keepFilePath,
    )

    fun getDownloadedStorageBytes(): Long = downloader.getDownloadedStorageBytes()

    private fun buildIndexedItem(item: LiveWallpaperItem): IndexedLiveItem {
        val rawCategory = item.category.orEmpty()
        val rawSeries = item.series.orEmpty()
        val rawTitle = item.title.orEmpty()
        val rawSlug = item.slug.orEmpty()
        val rawId = item.id.orEmpty()
        val rawTags = item.tags.orEmpty()

        val catCollapsed = normalize(rawCategory)
        val seriesCollapsed = normalize(rawSeries)
        val titleCollapsed = normalize(rawTitle)
        val slugCollapsed = normalize(rawSlug)
        val idCollapsed = normalize(rawId)
        val tagCollapsedList = rawTags.map { normalize(it) }.filter { it.isNotEmpty() }

        val fieldTokens = HashSet<String>().apply {
            addAll(tokenize(rawTitle))
            addAll(tokenize(rawSeries))
            addAll(tokenize(rawCategory))
            addAll(tokenize(rawSlug))
            rawTags.forEach { addAll(tokenize(it)) }
        }
        val combinedCollapsed = buildString {
            append(titleCollapsed)
            append(' ')
            append(seriesCollapsed)
            append(' ')
            append(catCollapsed)
            append(' ')
            append(slugCollapsed)
            tagCollapsedList.forEach {
                append(' ')
                append(it)
            }
        }
        return IndexedLiveItem(
            item = item,
            catCollapsed = catCollapsed,
            seriesCollapsed = seriesCollapsed,
            titleCollapsed = titleCollapsed,
            slugCollapsed = slugCollapsed,
            idCollapsed = idCollapsed,
            tagCollapsedList = tagCollapsedList,
            fieldTokens = fieldTokens,
            combinedCollapsed = combinedCollapsed,
        )
    }

    private fun resolveIndexedPool(items: List<LiveWallpaperItem>): List<IndexedLiveItem> {
        val currentIndexed = indexedCatalog
        if (items === memoryCatalog && currentIndexed.size == items.size) {
            return currentIndexed
        }
        return items.map { buildIndexedItem(it) }
    }

    internal fun filterAndRankItems(
        items: List<LiveWallpaperItem>,
        filterState: LiveFilterState,
    ): List<LiveWallpaperItem> {
        if (filterState.isHome) return items

        var working = resolveIndexedPool(items)

        // 1. Category facet (exact category match first; fallback to synonym/keyword match for legacy JSONs)
        val rawCategory = filterState.selectedCategory?.trim().orEmpty()
        if (rawCategory.isNotEmpty()) {
            val catNorm = normalize(rawCategory)
            val exactCat = working.filter { it.catCollapsed == catNorm }
            working = if (exactCat.isNotEmpty()) {
                exactCat
            } else {
                filterAndRankIndexed(working, rawCategory)
            }
            if (working.isEmpty()) return emptyList()
        }

        // 2. Series facet (matches explicit series field, or category when series is absent)
        val rawSeries = filterState.selectedSeries?.trim().orEmpty()
        if (rawSeries.isNotEmpty()) {
            val seriesNorm = normalize(rawSeries)
            val exactSeries = working.filter { indexed ->
                indexed.seriesCollapsed == seriesNorm ||
                    (indexed.seriesCollapsed.isEmpty() && indexed.catCollapsed == seriesNorm)
            }
            working = if (exactSeries.isNotEmpty()) {
                exactSeries
            } else {
                filterAndRankIndexed(working, rawSeries)
            }
            if (working.isEmpty()) return emptyList()
        }

        // 3. Tag filter with specificity ranking (items where the tag appears in title/slug or as primary tag rank first)
        if (filterState.selectedTags.isNotEmpty()) {
            val normalizedSelectedTags = filterState.selectedTags
                .map { normalize(it.removePrefix("#")) }
                .filter { it.isNotEmpty() }
            if (normalizedSelectedTags.isNotEmpty()) {
                working = working.mapNotNull { indexed ->
                    val score = scoreSelectedTags(indexed, normalizedSelectedTags)
                    if (score > 0) indexed to score else null
                }
                    .sortedByDescending { it.second }
                    .map { it.first }
                if (working.isEmpty()) return emptyList()
            }
        }

        // 4. Free-text query or Color palette synonym OR filter
        val cleanedQuery = filterState.query.trim().removePrefix("#").trim()
        val collapsedQuery = normalize(cleanedQuery)
        val isAllText = collapsedQuery.isEmpty() ||
            collapsedQuery == "hometrending" ||
            collapsedQuery == "all"
        if (isAllText) return working.map { it.item }

        if (filterState.isColorSearch) {
            val synonyms = COLOR_SYNONYM_MAP[cleanedQuery.lowercase()]
            if (synonyms != null) {
                val normalizedSynonyms = synonyms.map { normalize(it) }.filter { it.isNotEmpty() }
                val colorMatches = working.mapNotNull { indexed ->
                    val score = scoreColorSynonyms(indexed, normalizedSynonyms)
                    if (score > 0) indexed to score else null
                }
                    .sortedByDescending { it.second }
                    .map { it.first }
                if (colorMatches.isNotEmpty()) return colorMatches.map { it.item }
            }
        }

        return filterAndRankIndexed(working, cleanedQuery).map { it.item }
    }

    private fun scoreSelectedTags(
        indexed: IndexedLiveItem,
        normalizedSelectedTags: List<String>,
    ): Int {
        var totalScore = 0
        for (tagNorm in normalizedSelectedTags) {
            val exactTagIdx = indexed.tagCollapsedList.indexOfFirst { it == tagNorm }
            val inTitle = indexed.titleCollapsed.contains(tagNorm)
            val inSlug = indexed.slugCollapsed.contains(tagNorm)
            val inSeries = indexed.seriesCollapsed.contains(tagNorm)
            val score = when {
                exactTagIdx == 0 && (inTitle || inSlug) -> 120
                inTitle -> 110
                inSeries -> 105
                exactTagIdx == 0 -> 100
                exactTagIdx in 1..2 -> 92 - (exactTagIdx * 2)
                exactTagIdx > 2 -> 80
                inSlug -> 78
                indexed.tagCollapsedList.any { it.contains(tagNorm) } -> 72
                indexed.catCollapsed.contains(tagNorm) -> 65
                matchesExactSynonym(
                    tagNorm,
                    indexed.catCollapsed,
                    indexed.seriesCollapsed,
                    indexed.titleCollapsed,
                    indexed.tagCollapsedList,
                ) -> 55
                else -> 0
            }
            if (score == 0) return 0
            totalScore += score
        }
        return totalScore
    }

    private fun scoreColorSynonyms(
        indexed: IndexedLiveItem,
        normalizedSynonyms: List<String>,
    ): Int {
        var best = 0
        for (synNorm in normalizedSynonyms) {
            val candidate = when {
                indexed.tagCollapsedList.any { it == synNorm } -> 100
                indexed.titleCollapsed.contains(synNorm) -> 90
                indexed.tagCollapsedList.any { it.contains(synNorm) } -> 85
                indexed.slugCollapsed.contains(synNorm) -> 80
                indexed.seriesCollapsed.contains(synNorm) || indexed.catCollapsed.contains(synNorm) -> 75
                else -> 0
            }
            if (candidate > best) best = candidate
            if (best == 100) break
        }
        return best
    }

    internal fun filterAndRankItems(
        items: List<LiveWallpaperItem>,
        rawQuery: String,
    ): List<LiveWallpaperItem> {
        val cleaned = rawQuery.trim().removePrefix("#").trim()
        val collapsedQuery = normalize(cleaned)
        val isAllQuery = collapsedQuery.isEmpty() ||
            collapsedQuery == "hometrending" ||
            collapsedQuery == "all"

        if (isAllQuery) return items
        return filterAndRankIndexed(resolveIndexedPool(items), rawQuery).map { it.item }
    }

    private fun filterAndRankIndexed(
        pool: List<IndexedLiveItem>,
        rawQuery: String,
    ): List<IndexedLiveItem> {
        val cleaned = rawQuery.trim().removePrefix("#").trim()
        val collapsedQuery = normalize(cleaned)
        val isAllQuery = collapsedQuery.isEmpty() ||
            collapsedQuery == "hometrending" ||
            collapsedQuery == "all"

        if (isAllQuery) return pool

        if (collapsedQuery == "anime") {
            val exactAnimeCat = pool.filter { it.catCollapsed == "anime" }
            if (exactAnimeCat.isNotEmpty()) return exactAnimeCat
            return pool
        }

        val queryTokens = tokenize(cleaned)

        // Pass 1: Strict match (exact category/series/tag, full phrase, multi-keyword AND across title+series+tags+category+slug, or exact alias)
        val primaryMatches = pool.mapNotNull { indexed ->
            val score = scoreMatchIndexed(indexed, collapsedQuery, queryTokens)
            if (score > 0) indexed to score else null
        }
            .sortedByDescending { it.second }
            .map { it.first }

        if (primaryMatches.isNotEmpty()) {
            return primaryMatches
        }

        // Pass 2: Official long series-title fallback (e.g. "Frieren: Beyond Journey's End" -> series/category "Frieren")
        // Only runs when strict pass returns 0 results so multi-keyword AND queries stay precise.
        return pool.mapNotNull { indexed ->
            val score = scoreSeriesFallbackIndexed(indexed, collapsedQuery)
            if (score > 0) indexed to score else null
        }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Scores how well [item] strictly matches [collapsedQuery] and multi-keyword [queryTokens]
     * across title, series, tags, category, slug, and id.
     * Returns 0 if not a match; higher score means higher relevance.
     */
    internal fun scoreMatch(
        item: LiveWallpaperItem,
        collapsedQuery: String,
        queryTokens: List<String>,
    ): Int = scoreMatchIndexed(buildIndexedItem(item), collapsedQuery, queryTokens)

    private fun scoreMatchIndexed(
        indexed: IndexedLiveItem,
        collapsedQuery: String,
        queryTokens: List<String>,
    ): Int {
        // 1. Exact category, series, or tag match (highest priority)
        if (indexed.catCollapsed == collapsedQuery ||
            indexed.seriesCollapsed == collapsedQuery ||
            indexed.tagCollapsedList.any { it == collapsedQuery }
        ) {
            return 100
        }

        // 2. Direct full-query substring match in title, series, category, tags, slug, or id
        if (indexed.titleCollapsed.contains(collapsedQuery)) return 90
        if (indexed.seriesCollapsed.contains(collapsedQuery)) return 88
        if (indexed.catCollapsed.contains(collapsedQuery)) return 85
        if (indexed.tagCollapsedList.any { it.contains(collapsedQuery) }) return 80
        if (indexed.slugCollapsed.contains(collapsedQuery) || indexed.idCollapsed.contains(collapsedQuery)) return 75

        // 3. Multi-keyword AND match across combined fields (title + series + category + tags + slug)
        if (queryTokens.isNotEmpty()) {
            val allTokensMatched = queryTokens.all { qTok ->
                indexed.fieldTokens.any { fTok -> fTok.contains(qTok) } ||
                    indexed.combinedCollapsed.contains(qTok) ||
                    matchesExactSynonym(
                        qTok,
                        indexed.catCollapsed,
                        indexed.seriesCollapsed,
                        indexed.titleCollapsed,
                        indexed.tagCollapsedList,
                    )
            }
            if (allTokensMatched) {
                return 65
            }
        }

        // 4. Exact full-phrase synonym/abbreviation match (e.g., "Attack on Titan" -> "aot", "Kimetsu no Yaiba" -> "Demon Slayer")
        if (matchesExactSynonym(
                collapsedQuery,
                indexed.catCollapsed,
                indexed.seriesCollapsed,
                indexed.titleCollapsed,
                indexed.tagCollapsedList,
            )
        ) {
            return 60
        }

        return 0
    }

    private fun scoreSeriesFallbackIndexed(
        indexed: IndexedLiveItem,
        collapsedQuery: String,
    ): Int {
        if (indexed.seriesCollapsed.length >= 4 && collapsedQuery.contains(indexed.seriesCollapsed)) {
            return 58
        }
        if (indexed.catCollapsed.length >= 4 && collapsedQuery.contains(indexed.catCollapsed)) {
            return 55
        }
        if (matchesLooseSynonym(
                collapsedQuery,
                indexed.catCollapsed,
                indexed.seriesCollapsed,
                indexed.titleCollapsed,
                indexed.tagCollapsedList,
            )
        ) {
            return 45
        }
        return 0
    }

    private fun matchesExactSynonym(
        queryKey: String,
        catCollapsed: String,
        seriesCollapsed: String,
        titleCollapsed: String,
        tagCollapsedList: List<String>,
    ): Boolean {
        if (queryKey.length < 2) return false
        return ANIME_SYNONYM_GROUPS.any { group ->
            val queryInGroup = group.any { alias -> queryKey == alias }
            if (!queryInGroup) return@any false
            group.any { alias ->
                catCollapsed.contains(alias) ||
                    seriesCollapsed.contains(alias) ||
                    titleCollapsed.contains(alias) ||
                    tagCollapsedList.any { it.contains(alias) }
            }
        }
    }

    private fun matchesLooseSynonym(
        queryKey: String,
        catCollapsed: String,
        seriesCollapsed: String,
        titleCollapsed: String,
        tagCollapsedList: List<String>,
    ): Boolean {
        if (queryKey.length < 4) return false
        return ANIME_SYNONYM_GROUPS.any { group ->
            val queryInGroup = group.any { alias ->
                alias.length >= 4 && (queryKey.contains(alias) || alias.contains(queryKey))
            }
            if (!queryInGroup) return@any false
            group.any { alias ->
                catCollapsed.contains(alias) ||
                    seriesCollapsed.contains(alias) ||
                    titleCollapsed.contains(alias) ||
                    tagCollapsedList.any { it.contains(alias) }
            }
        }
    }

    private fun stripDiacritics(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(normalized, "")
            .replace('đ', 'd')
            .replace('Đ', 'D')
    }

    private fun tokenize(value: String): List<String> {
        val ascii = stripDiacritics(value).lowercase()
        val rawTokens = ascii.split(TOKEN_SPLIT_REGEX).filter { it.isNotEmpty() }
        if (rawTokens.size <= 1) return rawTokens
        return rawTokens.filter { it.length >= 2 }.ifEmpty { rawTokens }
    }

    private fun normalize(value: String): String {
        return stripDiacritics(value).lowercase().replace(TOKEN_SPLIT_REGEX, "")
    }
}
