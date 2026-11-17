package com.example.data.repository

import android.util.Log
import com.example.data.local.LiveWallpaperDataSource
import com.example.data.local.dao.WallpaperDao
import com.example.data.local.entity.CachedWallpaperEntity
import com.example.data.local.entity.FavoriteWallpaperEntity
import com.example.data.remote.RedditPost
import com.example.data.remote.RedditService
import com.example.data.remote.WallhavenRateLimiter
import com.example.data.remote.WallhavenResponse
import com.example.data.remote.WallhavenService
import com.example.domain.model.AnimeCatalog
import com.example.domain.model.AppWallpaper
import com.example.domain.model.ResolutionTier
import com.example.domain.model.SortOption
import com.example.domain.model.WallpaperFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response

data class WallpaperFetchResult(
    val wallpapers: List<AppWallpaper>,
    val isFromCache: Boolean = false,
    val rateLimited: Boolean = false,
    val hasMore: Boolean = true,
    val warningMessage: String? = null,
)

private sealed interface ApiCallOutcome<out T> {
    data class Success<T>(val response: Response<T>) : ApiCallOutcome<T>
    data class HttpFailure(
        val code: Int,
        val message: String,
        val retryAfterSeconds: Long? = null,
    ) : ApiCallOutcome<Nothing>
    data class NetworkFailure(val throwable: Throwable) : ApiCallOutcome<Nothing>
}

class WallpaperRepository(
    private val wallhavenApi: WallhavenService,
    private val redditApi: RedditService,
    private val wallpaperDao: WallpaperDao,
    private val liveWallpaperDataSource: LiveWallpaperDataSource = LiveWallpaperDataSource { "[]" },
    private val wallhavenRateLimiter: WallhavenRateLimiter = WallhavenRateLimiter(),
) {
    companion object {
        private const val TAG = "WallpaperRepository"
        const val HOME_TRENDING_KEY = "Home_Trending"
        private const val CACHE_TTL_MILLIS = 24L * 60L * 60L * 1000L // 24-hour Room cache
        private const val FORCE_REFRESH_MIN_INTERVAL_MS = 8_000L
    }

    // Repository-owned scope decouples shared in-flight requests from individual caller cancellation.
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightMutex = Mutex()
    private val inFlightStaticRequests = mutableMapOf<String, Deferred<WallpaperFetchResult>>()

    fun close() {
        repositoryScope.cancel()
    }

    fun observeFavorites(): Flow<List<AppWallpaper>> {
        return wallpaperDao.observeFavorites().map { entities ->
            entities.map { entity ->
                AppWallpaper(
                    id = entity.id,
                    title = entity.title,
                    animeSeries = entity.animeSeries,
                    imageUrl = entity.imageUrl,
                    thumbnailUrl = entity.thumbnailUrl,
                    videoUrl = entity.videoUrl,
                    isLive = entity.isLive,
                    width = entity.width,
                    height = entity.height,
                    source = entity.source,
                    isFavorite = true,
                )
            }
        }
    }

    fun observeFavoriteIds(): Flow<Set<String>> {
        return wallpaperDao.observeFavoriteIds().map { it.toSet() }
    }

    fun observeCacheCount(): Flow<Int> = wallpaperDao.observeCacheCount()

    suspend fun toggleFavorite(wallpaper: AppWallpaper): Boolean = withContext(Dispatchers.IO) {
        val exists = wallpaperDao.isFavorite(wallpaper.id)
        if (exists) {
            wallpaperDao.deleteFavoriteById(wallpaper.id)
            false
        } else {
            wallpaperDao.insertFavorite(
                FavoriteWallpaperEntity(
                    id = wallpaper.id,
                    title = wallpaper.title,
                    animeSeries = wallpaper.animeSeries,
                    imageUrl = wallpaper.imageUrl,
                    thumbnailUrl = wallpaper.thumbnailUrl,
                    videoUrl = wallpaper.videoUrl,
                    isLive = wallpaper.isLive,
                    width = wallpaper.width,
                    height = wallpaper.height,
                    source = wallpaper.source,
                ),
            )
            true
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        wallpaperDao.clearAllCache()
    }

    suspend fun warmupLiveCatalog() = withContext(Dispatchers.IO) {
        liveWallpaperDataSource.getAllParsedItems()
    }

    suspend fun prefetchTrendingQueueBatch(
        filter: WallpaperFilter,
        page: Int = 2,
    ) = withContext(Dispatchers.IO) {
        if (wallhavenRateLimiter.remainingQuota() <= 15) return@withContext
        try {
            fetchQueuedTrendingBatch(
                filter = filter,
                page = page,
                forceRefresh = false,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Prefetch trending batch failed: ${e.message}", e) }
        }
    }

    suspend fun getWallpapers(
        query: String,
        isLive: Boolean,
        filter: WallpaperFilter,
        page: Int = 1,
        forceRefresh: Boolean = false,
    ): WallpaperFetchResult = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().ifEmpty { HOME_TRENDING_KEY }
        val cacheKey = buildCacheKey(normalizedQuery, isLive, filter, page)
        val cachedEntities = wallpaperDao.getCachedWallpapers(cacheKey)
        if (isLive) {
            return@withContext fetchLiveWallpapers(
                query = normalizedQuery,
                cacheKey = cacheKey,
                staleCache = cachedEntities,
                filter = filter,
                page = page,
            )
        }

        if (normalizedQuery == HOME_TRENDING_KEY) {
            return@withContext fetchQueuedTrendingBatch(
                filter = filter,
                page = page,
                forceRefresh = forceRefresh,
            )
        }

        if (isCacheFreshAndEligible(cacheKey, forceRefresh) && cachedEntities.isNotEmpty()) {
            return@withContext WallpaperFetchResult(
                wallpapers = cachedEntities.map { it.toDomain() },
                isFromCache = true,
                hasMore = true,
            )
        }

        coalesceStaticFetch(normalizedQuery, cacheKey, cachedEntities, filter, page)
    }

    private suspend fun isCacheFreshAndEligible(
        cacheKey: String,
        forceRefresh: Boolean,
    ): Boolean {
        val cachedTimestamp = wallpaperDao.getCacheTimestamp(cacheKey) ?: return false
        val ageMillis = System.currentTimeMillis() - cachedTimestamp
        val isCacheFresh = ageMillis < CACHE_TTL_MILLIS
        val isRecentlyRefreshed = ageMillis < FORCE_REFRESH_MIN_INTERVAL_MS
        return (!forceRefresh || isRecentlyRefreshed) && isCacheFresh
    }

    /**
     * Progressive Trending Queue:
     * - Initial load (page 1): fetches first 3 hot anime (Frieren, Jujutsu Kaisen, JoJo)
     * - Scroll page 2: next 3 in queue (Re:Zero, Oshi no Ko, Witch Hat Atelier)
     * - Scroll page 3: next 2 in queue (Bleach TYBW, The Apothecary Diaries)
     * - Scroll page 4: final 2 in queue (Daemons of the Shadow Realm, Solo Leveling)
     * - Scroll page 5+: paginates subsequent Wallhaven pages across the trending queue
     * All topics within a batch execute concurrently via async/awaitAll.
     */
    private suspend fun fetchQueuedTrendingBatch(
        filter: WallpaperFilter,
        page: Int,
        forceRefresh: Boolean,
    ): WallpaperFetchResult = coroutineScope {
        val topics = AnimeCatalog.trendingTopics
        if (topics.isEmpty()) {
            return@coroutineScope WallpaperFetchResult(wallpapers = emptyList(), hasMore = false)
        }

        val queueBatches = listOf(
            topics.slice(0 until minOf(3, topics.size)) to 1,
            (if (topics.size > 3) topics.slice(3 until minOf(6, topics.size)) else emptyList()) to 1,
            (if (topics.size > 6) topics.slice(6 until minOf(8, topics.size)) else emptyList()) to 1,
            (if (topics.size > 8) topics.slice(8 until topics.size) else emptyList()) to 1,
        ).filter { it.first.isNotEmpty() }

        val (batchTopics, topicPage) = if (page <= queueBatches.size) {
            queueBatches[page - 1]
        } else {
            val overflowIndex = page - queueBatches.size - 1
            val chunked = topics.chunked(3)
            val roundTopics = chunked[overflowIndex % chunked.size]
            val extraPage = 2 + (overflowIndex / chunked.size)
            roundTopics to extraPage
        }

        val perTopicResults = batchTopics.map { topic ->
            async {
                val topicCacheKey = buildCacheKey(topic.title, isLive = false, filter = filter, page = topicPage)
                val topicCached = wallpaperDao.getCachedWallpapers(topicCacheKey)
                val result = if (isCacheFreshAndEligible(topicCacheKey, forceRefresh) && topicCached.isNotEmpty()) {
                    WallpaperFetchResult(
                        wallpapers = topicCached.map { it.toDomain() },
                        isFromCache = true,
                        hasMore = true,
                    )
                } else {
                    coalesceStaticFetch(
                        query = topic.title,
                        cacheKey = topicCacheKey,
                        staleCache = topicCached,
                        filter = filter,
                        page = topicPage,
                        skipRedditUnlessNeeded = true,
                    )
                }
                topic to result
            }
        }.awaitAll()

        val interleaved = interleaveSeriesWallpapers(perTopicResults.map { it.second.wallpapers })
        val anyRateLimited = perTopicResults.any { it.second.rateLimited }
        val allFromCache = perTopicResults.isNotEmpty() && perTopicResults.all { it.second.isFromCache }
        val warning = perTopicResults.firstNotNullOfOrNull { it.second.warningMessage }
        val canLoadMoreQueue = page < queueBatches.size ||
                (!anyRateLimited && perTopicResults.any { it.second.hasMore })

        WallpaperFetchResult(
            wallpapers = interleaved,
            isFromCache = allFromCache,
            rateLimited = anyRateLimited,
            hasMore = canLoadMoreQueue && interleaved.isNotEmpty(),
            warningMessage = warning,
        )
    }

    private fun interleaveSeriesWallpapers(lists: List<List<AppWallpaper>>): List<AppWallpaper> {
        if (lists.isEmpty()) return emptyList()
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        val merged = mutableListOf<AppWallpaper>()
        for (idx in 0 until maxLen) {
            for (seriesList in lists) {
                if (idx < seriesList.size) {
                    merged.add(seriesList[idx])
                }
            }
        }
        return merged.distinctBy { it.id }
    }

    /**
     * Coalesces concurrent requests for the same [cacheKey] on [repositoryScope].
     * Uses CoroutineStart.LAZY + NonCancellable cleanup so cancelling one caller
     * never aborts a shared in-flight request or leaks entries in [inFlightStaticRequests].
     */
    private suspend fun coalesceStaticFetch(
        query: String,
        cacheKey: String,
        staleCache: List<CachedWallpaperEntity>,
        filter: WallpaperFilter,
        page: Int,
        skipRedditUnlessNeeded: Boolean = false,
    ): WallpaperFetchResult {
        var createdRef: Deferred<WallpaperFetchResult>? = null
        val deferred = inFlightMutex.withLock {
            val existing = inFlightStaticRequests[cacheKey]
            if (existing != null && existing.isActive) {
                existing
            } else {
                repositoryScope.async(start = CoroutineStart.LAZY) {
                    try {
                        fetchStaticWallpapers(
                            query = query,
                            cacheKey = cacheKey,
                            staleCache = staleCache,
                            filter = filter,
                            page = page,
                            skipRedditUnlessNeeded = skipRedditUnlessNeeded,
                        )
                    } finally {
                        withContext(NonCancellable) {
                            inFlightMutex.withLock {
                                if (inFlightStaticRequests[cacheKey] === createdRef) {
                                    inFlightStaticRequests.remove(cacheKey)
                                }
                            }
                        }
                    }
                }.also {
                    createdRef = it
                    inFlightStaticRequests[cacheKey] = it
                }
            }
        }
        deferred.start()
        return deferred.await()
    }

    private suspend fun <T> safeApiCall(
        sourceName: String,
        block: suspend () -> Response<T>,
    ): ApiCallOutcome<T> {
        return try {
            val response = block()
            if (response.isSuccessful) {
                ApiCallOutcome.Success(response)
            } else {
                val code = response.code()
                val retryAfter = response.headers()["Retry-After"]?.toLongOrNull()
                runCatching {
                    Log.w(TAG, "$sourceName HTTP $code: ${response.message()}")
                }
                ApiCallOutcome.HttpFailure(
                    code = code,
                    message = response.message(),
                    retryAfterSeconds = retryAfter,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching {
                Log.w(TAG, "$sourceName request failed: ${e.message}", e)
            }
            ApiCallOutcome.NetworkFailure(e)
        }
    }

    private suspend fun fetchStaticWallpapers(
        query: String,
        cacheKey: String,
        staleCache: List<CachedWallpaperEntity>,
        filter: WallpaperFilter,
        page: Int,
        skipRedditUnlessNeeded: Boolean = false,
    ): WallpaperFetchResult = coroutineScope {
        val isHomeTrending = query == HOME_TRENDING_KEY
        val trendingTopic = if (isHomeTrending) null else AnimeCatalog.findTrendingTopic(query)

        val wallhavenQuery = when {
            isHomeTrending -> null
            trendingTopic != null -> trendingTopic.query
            else -> query
        }
        val wallhavenCategories = trendingTopic?.categories ?: "010"
        val wallhavenPurity = trendingTopic?.purity ?: "100"
        val wallhavenSorting = when {
            isHomeTrending && filter.sort == SortOption.RELEVANCE -> SortOption.TOPLIST.wallhavenSorting
            trendingTopic?.sorting != null && filter.sort == SortOption.RELEVANCE -> trendingTopic.sorting
            else -> filter.sort.wallhavenSorting
        }
        val wallhavenOrder = when {
            trendingTopic?.order != null && filter.sort == SortOption.RELEVANCE -> trendingTopic.order
            else -> null
        }
        val wallhavenPage = (trendingTopic?.initialPage ?: 1) + (page - 1)
        val redditQuery = when {
            isHomeTrending -> "anime"
            trendingTopic != null -> trendingTopic.title
            else -> query
        }

        var localQuotaLimited = false
        var retryAfterSec = 0L

        val wallhavenTask = async<ApiCallOutcome<WallhavenResponse>?> {
            when (val decision = wallhavenRateLimiter.tryAcquire()) {
                is WallhavenRateLimiter.AcquireResult.RateLimited -> {
                    localQuotaLimited = true
                    retryAfterSec = ((decision.retryAfterMs + 999L) / 1000L).coerceAtLeast(1L)
                    null
                }

                WallhavenRateLimiter.AcquireResult.Allowed -> {
                    val outcome = safeApiCall("Wallhaven") {
                        wallhavenApi.searchAnime(
                            query = wallhavenQuery,
                            categories = wallhavenCategories,
                            purity = wallhavenPurity,
                            ratios = filter.ratio.wallhavenRatios,
                            atleast = filter.resolution.atleastParam,
                            sorting = wallhavenSorting,
                            order = wallhavenOrder,
                            topRange = if (wallhavenSorting == "toplist") "1y" else null,
                            page = wallhavenPage,
                        )
                    }
                    if (outcome is ApiCallOutcome.HttpFailure && outcome.code == 429) {
                        wallhavenRateLimiter.recordServerRateLimit(outcome.retryAfterSeconds)
                        retryAfterSec = outcome.retryAfterSeconds ?: 45L
                    }
                    outcome
                }
            }
        }
        val redditAnimeWallpaperTask = async {
            if (page > 1 || skipRedditUnlessNeeded) return@async null
            safeApiCall("Reddit/Animewallpaper") {
                redditApi.searchSubreddit(
                    subreddit = "Animewallpaper",
                    query = redditQuery,
                    sort = filter.sort.redditSort,
                    limit = 50,
                )
            }
        }
        val redditMoescapeTask = async {
            if (page > 1 || skipRedditUnlessNeeded) return@async null
            safeApiCall("Reddit/Moescape") {
                redditApi.searchSubreddit(
                    subreddit = "Moescape",
                    query = redditQuery,
                    sort = filter.sort.redditSort,
                    limit = 35,
                )
            }
        }

        val wallhavenOutcome = wallhavenTask.await()
        val rateLimited = localQuotaLimited ||
                (wallhavenOutcome is ApiCallOutcome.HttpFailure && wallhavenOutcome.code == 429)
        val wallhavenItems = (wallhavenOutcome as? ApiCallOutcome.Success)?.response?.body()?.data.orEmpty()

        // In queued multi-topic batches, only call Reddit as a fallback if Wallhaven is empty or rate-limited
        val fallbackRedditOutcome = if (skipRedditUnlessNeeded && page == 1 && (rateLimited || wallhavenItems.size < 4)) {
            safeApiCall("Reddit/Animewallpaper-fallback") {
                redditApi.searchSubreddit(
                    subreddit = "Animewallpaper",
                    query = redditQuery,
                    sort = filter.sort.redditSort,
                    limit = 25,
                )
            }
        } else {
            null
        }

        val redditOutcomes = listOfNotNull(
            redditAnimeWallpaperTask.await(),
            redditMoescapeTask.await(),
            fallbackRedditOutcome,
        )
        val redditResponses = redditOutcomes.mapNotNull { (it as? ApiCallOutcome.Success)?.response }

        val hadNetworkOrHttpError = (wallhavenOutcome is ApiCallOutcome.NetworkFailure) ||
                (wallhavenOutcome is ApiCallOutcome.HttpFailure && wallhavenOutcome.code != 429) ||
                redditOutcomes.any { it is ApiCallOutcome.NetworkFailure || it is ApiCallOutcome.HttpFailure }

        val combined = mutableListOf<AppWallpaper>()
        val displaySeries = when {
            isHomeTrending -> "Hot Anime"
            trendingTopic != null -> trendingTopic.title
            else -> query
        }
        wallhavenItems.forEach { item ->
            val safeImageUrl = sanitizeHttpsUrl(item.path) ?: return@forEach
            val safeThumbUrl = sanitizeHttpsUrl(item.thumbs.large)
                ?: sanitizeHttpsUrl(item.thumbs.original)
                ?: safeImageUrl
            combined.add(
                AppWallpaper(
                    id = "wh_${item.id}",
                    title = "$displaySeries • item.dimensionX×{item.dimensionY}",
                    animeSeries = displaySeries,
                    imageUrl = safeImageUrl,
                    thumbnailUrl = safeThumbUrl,
                    videoUrl = null,
                    isLive = false,
                    width = item.dimensionX,
                    height = item.dimensionY,
                    source = "Wallhaven",
                ),
            )
        }

        redditResponses.forEach { response ->
            response.body()?.data?.children?.forEach { child ->
                val post = child.data
                if (post.over18 || post.isSelf || post.isVideo) return@forEach

                val extracted = extractStaticRedditImage(post) ?: return@forEach
                val (originalUrl, thumbUrl, w, h) = extracted

                if (passesResolutionFilter(w, h, filter.resolution)) {
                    combined.add(
                        AppWallpaper(
                            id = "rd_${post.id}",
                            title = post.title.ifBlank { displaySeries },
                            animeSeries = if (isHomeTrending) post.title.ifBlank { "Anime" } else displaySeries,
                            imageUrl = originalUrl,
                            thumbnailUrl = thumbUrl,
                            videoUrl = null,
                            isLive = false,
                            width = w,
                            height = h,
                            source = "Reddit",
                        ),
                    )
                }
            }
        }

        val distinctItems = combined.distinctBy { it.id }.shuffled()

        if (distinctItems.isNotEmpty()) {
            wallpaperDao.replaceCacheForKey(
                cacheKey = cacheKey,
                items = distinctItems.mapIndexed { idx, w -> w.toCacheEntity(cacheKey, idx) },
                timestampMillis = System.currentTimeMillis(),
            )
            val cooldownNote = if (retryAfterSec > 0L) " (${retryAfterSec}s cooldown)" else ""
            WallpaperFetchResult(
                wallpapers = distinctItems,
                isFromCache = false,
                rateLimited = rateLimited,
                hasMore = !rateLimited && wallhavenItems.size >= 18,
                warningMessage = if (rateLimited) {
                    "Wallhaven 45 req/min limit protected$cooldownNote — showing Reddit wallpapers."
                } else {
                    null
                },
            )
        } else if (staleCache.isNotEmpty()) {
            WallpaperFetchResult(
                wallpapers = staleCache.map { it.toDomain() },
                isFromCache = true,
                rateLimited = rateLimited,
                hasMore = false,
                warningMessage = when {
                    rateLimited -> "Wallhaven rate guard active — showing cached wallpapers."
                    hadNetworkOrHttpError -> "Network error while refreshing. Showing cached wallpapers."
                    else -> "Offline or no new results. Showing cached wallpapers."
                },
            )
        } else {
            WallpaperFetchResult(
                wallpapers = emptyList(),
                isFromCache = false,
                rateLimited = rateLimited,
                hasMore = false,
                warningMessage = when {
                    rateLimited -> {
                        val waitHint = if (retryAfterSec > 0L) " (~${retryAfterSec}s)" else ""
                        "Wallhaven rate limit reached (45 req/min)$waitHint. Please wait a moment."
                    }
                    hadNetworkOrHttpError -> {
                        "Unable to reach wallpaper servers. Check your connection and try again."
                    }
                    else -> null
                },
            )
        }
    }

    private suspend fun fetchLiveWallpapers(
        query: String,
        cacheKey: String,
        staleCache: List<CachedWallpaperEntity>,
        filter: WallpaperFilter,
        page: Int,
    ): WallpaperFetchResult = coroutineScope {
        val livePage = liveWallpaperDataSource.getLiveWallpapersPage(
            query = query,
            page = page,
            pageSize = LiveWallpaperDataSource.DEFAULT_PAGE_SIZE,
        )
        if (livePage.items.isNotEmpty()) {
            return@coroutineScope WallpaperFetchResult(
                wallpapers = livePage.items,
                isFromCache = false,
                hasMore = livePage.hasMore,
            )
        }

        val isHomeTrending = query == HOME_TRENDING_KEY
        if (page > 1 || isHomeTrending) {
            return@coroutineScope WallpaperFetchResult(
                wallpapers = emptyList(),
                isFromCache = false,
                hasMore = false,
            )
        }

        val baseQuery = query
        val searchTerm = if (baseQuery.equals("anime", ignoreCase = true)) {
            "anime"
        } else {
            "$baseQuery anime"
        }

        val animeWallpaperTask = async {
            safeApiCall("Reddit/Animewallpaper-live") {
                redditApi.searchSubreddit(
                    subreddit = "Animewallpaper",
                    query = baseQuery,
                    sort = filter.sort.redditSort,
                    limit = 50,
                )
            }
        }
        val wallpaperEngineTask = async {
            safeApiCall("Reddit/wallpaperengine-live") {
                redditApi.searchSubreddit(
                    subreddit = "wallpaperengine",
                    query = searchTerm,
                    sort = filter.sort.redditSort,
                    limit = 50,
                )
            }
        }
        val animeGifsTask = async {
            safeApiCall("Reddit/animegifs-live") {
                redditApi.searchSubreddit(
                    subreddit = "animegifs",
                    query = baseQuery,
                    sort = filter.sort.redditSort,
                    limit = 40,
                )
            }
        }
        val shortClipsTask = async {
            safeApiCall("Reddit/AnimeShortClips-live") {
                redditApi.searchSubreddit(
                    subreddit = "AnimeShortClips",
                    query = baseQuery,
                    sort = "top",
                    limit = 30,
                )
            }
        }

        val outcomes = listOf(
            animeWallpaperTask.await(),
            wallpaperEngineTask.await(),
            animeGifsTask.await(),
            shortClipsTask.await(),
        )
        val results = outcomes.mapNotNull { (it as? ApiCallOutcome.Success)?.response }
        val allFailed = outcomes.all { it is ApiCallOutcome.NetworkFailure || it is ApiCallOutcome.HttpFailure }

        val liveWallpapers = mutableListOf<AppWallpaper>()

        results.forEach { response ->
            response.body()?.data?.children?.forEach { child ->
                val post = child.data
                if (post.over18) return@forEach

                val mp4Url = extractMp4Url(post) ?: return@forEach
                val thumb = resolveRedditThumbnail(post) ?: return@forEach
                val videoMeta = post.secureMedia?.redditVideo
                    ?: post.media?.redditVideo
                    ?: post.preview?.redditVideoPreview
                val previewSource = post.preview?.images?.firstOrNull()?.source
                val w = (videoMeta?.width ?: previewSource?.width ?: 1080).coerceAtLeast(480)
                val h = (videoMeta?.height ?: previewSource?.height ?: 1920).coerceAtLeast(640)

                val displaySeries = if (isHomeTrending) post.title.ifBlank { "Live Anime" } else query
                liveWallpapers.add(
                    AppWallpaper(
                        id = "live_${post.id}",
                        title = post.title.ifBlank { "$displaySeries Live Loop" },
                        animeSeries = displaySeries,
                        imageUrl = thumb,
                        thumbnailUrl = thumb,
                        videoUrl = mp4Url,
                        isLive = true,
                        width = w,
                        height = h,
                        source = "Reddit MP4",
                    ),
                )
            }
        }

        val distinctLive = liveWallpapers.distinctBy { it.id }.shuffled()
        if (distinctLive.isNotEmpty()) {
            wallpaperDao.replaceCacheForKey(
                cacheKey = cacheKey,
                items = distinctLive.mapIndexed { idx, w -> w.toCacheEntity(cacheKey, idx) },
                timestampMillis = System.currentTimeMillis(),
            )
            WallpaperFetchResult(wallpapers = distinctLive, isFromCache = false, hasMore = false)
        } else if (staleCache.isNotEmpty()) {
            WallpaperFetchResult(
                wallpapers = staleCache.map { it.toDomain() },
                isFromCache = true,
                hasMore = false,
                warningMessage = if (allFailed) {
                    "Network error while loading live wallpapers. Showing cached results."
                } else {
                    "Showing cached live MP4 wallpapers."
                },
            )
        } else {
            WallpaperFetchResult(
                wallpapers = emptyList(),
                hasMore = false,
                warningMessage = if (allFailed) {
                    "Unable to reach live wallpaper sources. Check your connection and try again."
                } else {
                    null
                },
            )
        }
    }

    private fun sanitizeHttpsUrl(rawUrl: String?): String? {
        val cleaned = rawUrl?.replace("&amp;", "&")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val upgraded = if (cleaned.startsWith("http://", ignoreCase = true)) {
            "https://" + cleaned.substring(7)
        } else {
            cleaned
        }
        val parsed = upgraded.toHttpUrlOrNull() ?: return null
        return if (parsed.isHttps) parsed.toString() else null
    }

    private data class ExtractedRedditImage(
        val originalUrl: String,
        val thumbnailUrl: String,
        val width: Int,
        val height: Int,
    )

    private fun extractStaticRedditImage(post: RedditPost): ExtractedRedditImage? {
        val previewImage = post.preview?.images?.firstOrNull()
        val previewSource = previewImage?.source
        val cleanPostUrl = sanitizeHttpsUrl(post.url)
        val lowerUrl = cleanPostUrl?.lowercase()?.substringBefore("?").orEmpty()
        val isDirectImageHost = lowerUrl.endsWith(".jpg") ||
                lowerUrl.endsWith(".jpeg") ||
                lowerUrl.endsWith(".png") ||
                lowerUrl.endsWith(".webp") ||
                lowerUrl.contains("i.redd.it/") ||
                lowerUrl.contains("i.imgur.com/")

        val previewSourceUrl = sanitizeHttpsUrl(previewSource?.url)

        val originalUrl = when {
            isDirectImageHost && cleanPostUrl != null -> cleanPostUrl
            previewSourceUrl != null && post.postHint != "link" -> previewSourceUrl
            previewSourceUrl != null && post.postHint == "image" -> previewSourceUrl
            else -> null
        } ?: return null

        val width = previewSource?.width?.takeIf { it > 0 } ?: 1080
        val height = previewSource?.height?.takeIf { it > 0 } ?: 1440
        val thumbUrl = resolveRedditThumbnail(post) ?: originalUrl

        return ExtractedRedditImage(
            originalUrl = originalUrl,
            thumbnailUrl = thumbUrl,
            width = width,
            height = height,
        )
    }

    private fun passesResolutionFilter(
        width: Int,
        height: Int,
        tier: ResolutionTier,
    ): Boolean {
        if (tier == ResolutionTier.ALL) return true
        val minSide = minOf(width, height)
        val maxSide = maxOf(width, height)
        return minSide >= tier.minWidth && maxSide >= tier.minHeight
    }

    private fun extractMp4Url(post: RedditPost): String? {
        if (post.url.substringBefore("?").endsWith(".mp4", ignoreCase = true)) {
            sanitizeHttpsUrl(post.url)?.let { return it }
        }
        val redditVideo = post.secureMedia?.redditVideo
            ?: post.media?.redditVideo
            ?: post.preview?.redditVideoPreview
        val fallback = sanitizeHttpsUrl(redditVideo?.fallbackUrl)
        if (fallback != null && fallback.contains(".mp4", ignoreCase = true)) {
            return fallback
        }
        val mp4Variant = sanitizeHttpsUrl(
            post.preview?.images?.firstOrNull()?.variants?.mp4?.source?.url,
        )
        if (mp4Variant != null) {
            return mp4Variant
        }
        return null
    }

    private fun resolveRedditThumbnail(post: RedditPost): String? {
        val resolutions = post.preview?.images?.firstOrNull()?.resolutions.orEmpty()
        val medium = resolutions.firstOrNull { it.width in 320..720 } ?: resolutions.lastOrNull()
        sanitizeHttpsUrl(medium?.url)?.let { return it }
        sanitizeHttpsUrl(post.thumbnail)?.let { return it }
        return sanitizeHttpsUrl(post.preview?.images?.firstOrNull()?.source?.url)
    }

    private fun buildCacheKey(
        query: String,
        isLive: Boolean,
        filter: WallpaperFilter,
        page: Int,
    ): String {
        val type = if (isLive) "live" else "static"
        return "type:{query.lowercase()}:filter.resolution.name:{filter.sort.name}:filter.ratio.name:ppage"
    }

    private fun CachedWallpaperEntity.toDomain(): AppWallpaper = AppWallpaper(
        id = wallpaperId,
        title = title,
        animeSeries = animeSeries,
        imageUrl = imageUrl,
        thumbnailUrl = thumbnailUrl,
        videoUrl = videoUrl,
        isLive = isLive,
        width = width,
        height = height,
        source = source,
    )

    private fun AppWallpaper.toCacheEntity(cacheKey: String, index: Int): CachedWallpaperEntity =
        CachedWallpaperEntity(
            cacheKey = cacheKey,
            wallpaperId = id,
            title = title,
            animeSeries = animeSeries,
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            videoUrl = videoUrl,
            isLive = isLive,
            width = width,
            height = height,
            source = source,
            positionIndex = index,
        )
}

