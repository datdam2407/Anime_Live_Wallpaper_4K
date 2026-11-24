package com.example.data.repository

import android.util.Log
import com.example.data.local.CacheExpiryPolicy
import com.example.data.local.dao.WallpaperDao
import com.example.data.local.entity.CachedWallpaperEntity
import com.example.data.local.entity.FavoriteWallpaperEntity
import com.example.data.remote.RedditPost
import com.example.data.remote.RedditService
import com.example.data.remote.WallhavenRateLimiter
import com.example.data.remote.WallhavenResponse
import com.example.data.remote.WallhavenService
import com.example.domain.model.AnimeCatalog
import com.example.domain.model.AnimeCategory
import com.example.domain.model.AnimeSeriesPreview
import com.example.domain.model.AppWallpaper
import com.example.domain.model.LiveFilterState
import com.example.domain.model.RatioFilter
import com.example.domain.model.ResolutionTier
import com.example.domain.model.SortOption
import com.example.domain.model.StaticFilterState
import com.example.domain.model.WallpaperFilter
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response
import kotlin.random.Random

data class WallpaperFetchResult(
    val wallpapers: List<AppWallpaper>,
    val isFromCache: Boolean = false,
    val rateLimited: Boolean = false,
    val hasMore: Boolean = true,
    val warningMessage: String? = null,
    val totalCount: Int? = null,
)

data class CollectionPageResult(
    val wallpapers: List<AppWallpaper>,
    val totalCount: Int,
    val hasMore: Boolean,
)

data class ExploreCategoryProbeResult(
    val coverUrls: List<String>,
    val confirmedEmpty: Boolean,
)

data class StaticTagProbeResult(
    val tag: String,
    val matchCount: Int,
    val confirmedEmpty: Boolean,
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
    private val wallhavenRateLimiter: WallhavenRateLimiter = WallhavenRateLimiter(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val liveWallpaperRepository: LiveWallpaperRepository? = null,
) {
    companion object {
        private const val TAG = "WallpaperRepository"
        const val HOME_TRENDING_KEY = "Home_Trending"
        const val STATIC_PAGE_SIZE = 18
        const val MAX_MOBILE_OPTIMAL_EDGE_PX = 3840
        const val MAX_MOBILE_FILE_SIZE_BYTES = 8L * 1024L * 1024L // 8 MB mobile safety cap
    }

    // Repository-owned scope decouples shared in-flight requests from individual caller cancellation.
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightMutex = Mutex()
    private val inFlightStaticRequests = mutableMapOf<String, Deferred<WallpaperFetchResult>>()
    private val seriesTotalCounts = ConcurrentHashMap<String, Int>()
    @Volatile
    private var staticRefreshEpoch: Int = 0
    @Volatile
    private var staticTrendingBatchOffset: Int = 0

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

    suspend fun clearCategoryCache(
        query: String,
        sort: SortOption,
        isLive: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val category = CacheExpiryPolicy.resolveCategory(query = query, sort = sort, isLive = isLive)
        wallpaperDao.clearCategoryCache(category)
    }

    suspend fun pruneExpiredCache(nowMillis: Long = clock()) = withContext(Dispatchers.IO) {
        try {
            val cutoffMillis = nowMillis - CacheExpiryPolicy.MAX_RETENTION_MILLIS
            wallpaperDao.pruneExpiredCache(cutoffMillis)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Failed to prune expired cache: ${e.message}", e) }
        }
    }

    /**
     * Reactive Offline-First stream backed directly by Room SQLite.
     * Emits cached wallpapers immediately and updates automatically whenever a refresh writes to Room.
     */
    fun observeCachedWallpapers(
        query: String,
        isLive: Boolean,
        filter: WallpaperFilter,
        page: Int = 1,
    ): Flow<List<AppWallpaper>> {
        val normalizedQuery = query.trim().ifEmpty { HOME_TRENDING_KEY }
        if (!isLive && normalizedQuery == HOME_TRENDING_KEY) {
            val (batchTopics, topicPage) = resolveTrendingBatch(page) ?: return flowOf(emptyList())
            val topicFlows = batchTopics.map { topic ->
                val topicCacheKey = buildCacheKey(topic.title, isLive = false, filter = filter, page = topicPage)
                wallpaperDao.observeCachedWallpapers(topicCacheKey).map { list -> list.map { it.toDomain() } }
            }
            return combine(topicFlows) { lists ->
                interleaveSeriesWallpapers(lists.toList())
            }
        }
        val cacheKey = buildCacheKey(normalizedQuery, isLive, filter, page)
        return wallpaperDao.observeCachedWallpapers(cacheKey).map { list -> list.map { it.toDomain() } }
    }

    /**
     * Instant local-only Room snapshot (0 network calls) so UI can render cached wallpapers
     * immediately even when the cache is expired and refreshing in the background.
     */
    suspend fun getCachedWallpapersSnapshot(
        query: String,
        isLive: Boolean,
        filter: WallpaperFilter,
        page: Int = 1,
        liveFilter: LiveFilterState? = null,
        staticFilter: StaticFilterState? = null,
    ): List<AppWallpaper> = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().ifEmpty { HOME_TRENDING_KEY }
        if (isLive) {
            val snapshot = if (liveFilter != null) {
                liveWallpaperRepository?.getCachedCatalogSnapshot(
                    filterState = liveFilter,
                    page = page,
                    pageSize = LiveWallpaperRepository.DEFAULT_PAGE_SIZE,
                )
            } else {
                liveWallpaperRepository?.getCachedCatalogSnapshot(
                    query = normalizedQuery,
                    page = page,
                    pageSize = LiveWallpaperRepository.DEFAULT_PAGE_SIZE,
                )
            }
            if (snapshot != null && snapshot.items.isNotEmpty()) {
                return@withContext snapshot.items
            }
        }
        val effectiveStatic = if (!isLive) staticFilter?.takeIf { it.isChipFilterActive } else null
        if (!isLive && effectiveStatic == null && normalizedQuery == HOME_TRENDING_KEY) {
            val (batchTopics, topicPage) = resolveTrendingBatch(page) ?: return@withContext emptyList()
            val perTopicLists = batchTopics.map { topic ->
                val topicCacheKey = buildCacheKey(topic.title, isLive = false, filter = filter, page = topicPage)
                wallpaperDao.getCachedWallpapers(topicCacheKey).map { it.toDomain() }
            }
            return@withContext interleaveSeriesWallpapers(perTopicLists)
        }
        val effectiveQueryKey = when {
            isLive && liveFilter != null -> liveFilter.cacheKey()
            effectiveStatic != null -> effectiveStatic.cacheKeyPart() ?: normalizedQuery
            else -> normalizedQuery
        }
        val cacheKey = buildCacheKey(effectiveQueryKey, isLive, filter, page)
        wallpaperDao.getCachedWallpapers(cacheKey).map { it.toDomain() }
    }

    /**
     * Returns true if the local Room cache for this query/filter/page is populated and
     * still within its CacheExpiryPolicy freshness window (4h–12h depending on category/sort).
     */
    suspend fun isFeedCacheFresh(
        query: String,
        isLive: Boolean,
        filter: WallpaperFilter,
        page: Int = 1,
        liveFilter: LiveFilterState? = null,
        staticFilter: StaticFilterState? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().ifEmpty { HOME_TRENDING_KEY }
        if (isLive) {
            val snapshot = if (liveFilter != null) {
                liveWallpaperRepository?.getCachedCatalogSnapshot(
                    filterState = liveFilter,
                    page = page,
                    pageSize = LiveWallpaperRepository.DEFAULT_PAGE_SIZE,
                )
            } else {
                liveWallpaperRepository?.getCachedCatalogSnapshot(
                    query = normalizedQuery,
                    page = page,
                    pageSize = LiveWallpaperRepository.DEFAULT_PAGE_SIZE,
                )
            }
            if (snapshot != null && snapshot.items.isNotEmpty()) return@withContext true
        }
        val effectiveStatic = if (!isLive) staticFilter?.takeIf { it.isChipFilterActive } else null
        if (!isLive && effectiveStatic == null && normalizedQuery == HOME_TRENDING_KEY) {
            val (batchTopics, topicPage) = resolveTrendingBatch(page) ?: return@withContext false
            return@withContext batchTopics.all { topic ->
                val topicCacheKey = buildCacheKey(topic.title, isLive = false, filter = filter, page = topicPage)
                isCacheFreshAndEligible(
                    cacheKey = topicCacheKey,
                    query = topic.title,
                    sort = filter.sort,
                    isLive = false,
                    forceRefresh = false,
                ) && wallpaperDao.getCachedWallpapers(topicCacheKey).isNotEmpty()
            }
        }
        val effectiveQueryKey = when {
            isLive && liveFilter != null -> liveFilter.cacheKey()
            effectiveStatic != null -> effectiveStatic.cacheKeyPart() ?: normalizedQuery
            else -> normalizedQuery
        }
        val cacheKey = buildCacheKey(effectiveQueryKey, isLive, filter, page)
        isCacheFreshAndEligible(
            cacheKey = cacheKey,
            query = effectiveQueryKey,
            sort = filter.sort,
            isLive = isLive,
            forceRefresh = false,
        ) && wallpaperDao.getCachedWallpapers(cacheKey).isNotEmpty()
    }

    /**
     * Offline-First coordinator: returns Room cache immediately with 0 API requests while valid,
     * or fetches fresh data from Wallhaven/Reddit when empty, expired, or force-refreshed.
     */
    suspend fun refreshWallpapersIfNeeded(
        query: String,
        isLive: Boolean,
        filter: WallpaperFilter,
        page: Int = 1,
        forceRefresh: Boolean = false,
        liveFilter: LiveFilterState? = null,
        staticFilter: StaticFilterState? = null,
    ): WallpaperFetchResult = getWallpapers(
        query = query,
        isLive = isLive,
        filter = filter,
        page = page,
        forceRefresh = forceRefresh,
        liveFilter = liveFilter,
        staticFilter = staticFilter,
    )

    suspend fun warmupLiveCatalog() = withContext(Dispatchers.IO) {
        liveWallpaperRepository?.fetchLiveWallpapers(forceRefresh = false)
    }

    /**
     * Instant local-only Room lookup (0 network calls) for a SearchScreen Trending Category card.
     */
    suspend fun getCachedExploreCategoryStaticCovers(
        topic: com.example.domain.model.TrendingAnimeTopic,
        filter: WallpaperFilter = WallpaperFilter(),
        maxCount: Int = 10,
    ): List<String> = withContext(Dispatchers.IO) {
        val categoryFilter = StaticFilterState(selectedCategory = topic.title)
        val effectiveKey = categoryFilter.cacheKeyPart() ?: topic.title
        val cacheKey = buildCacheKey(effectiveKey, isLive = false, filter = filter, page = 1)
        val legacyCacheKey = buildCacheKey(topic.title, isLive = false, filter = filter, page = 1)
        (wallpaperDao.getCachedWallpapers(cacheKey) + wallpaperDao.getCachedWallpapers(legacyCacheKey))
            .mapNotNull { it.thumbnailUrl.takeIf(String::isNotBlank) }
            .distinct()
            .take(maxCount)
    }

    /**
     * Returns up to [maxCount] distinct static wallpaper thumbnail URLs for a SearchScreen
     * Trending Category card. Prefers local Room cache (0 network calls); when empty, fetches
     * page 1 via Wallhaven (if rate-limit quota allows, also caching all ~24 wallpapers for
     * instant browsing later) or falls back to Reddit r/MobileWallpaper.
     */
    suspend fun getExploreCategoryStaticCovers(
        topic: com.example.domain.model.TrendingAnimeTopic,
        filter: WallpaperFilter = WallpaperFilter(),
        maxCount: Int = 10,
    ): List<String> = probeExploreCategoryStaticCovers(topic, filter, maxCount).coverUrls

    /**
     * Probes an explore category against local Room cache and Wallhaven API so the UI can:
     * 1. Pre-warm cover thumbnails for SearchScreen Trending Categories.
     * 2. Hide any static 4K Artworks category confirmed empty (`confirmedEmpty = true`) by the API.
     */
    suspend fun probeExploreCategoryStaticCovers(
        topic: com.example.domain.model.TrendingAnimeTopic,
        filter: WallpaperFilter = WallpaperFilter(),
        maxCount: Int = 10,
    ): ExploreCategoryProbeResult = withContext(Dispatchers.IO) {
        val categoryFilter = StaticFilterState(selectedCategory = topic.title)
        val effectiveKey = categoryFilter.cacheKeyPart() ?: topic.title
        val cacheKey = buildCacheKey(effectiveKey, isLive = false, filter = filter, page = 1)
        val legacyCacheKey = buildCacheKey(topic.title, isLive = false, filter = filter, page = 1)
        val cachedUrls = (wallpaperDao.getCachedWallpapers(cacheKey) + wallpaperDao.getCachedWallpapers(legacyCacheKey))
            .mapNotNull { it.thumbnailUrl.takeIf(String::isNotBlank) }
            .distinct()
        if (cachedUrls.isNotEmpty()) {
            return@withContext ExploreCategoryProbeResult(
                coverUrls = cachedUrls.take(maxCount),
                confirmedEmpty = false,
            )
        }

        if (wallhavenRateLimiter.remainingQuota() > 10) {
            val fetched = coalesceStaticFetch(
                query = topic.title,
                cacheKey = cacheKey,
                staleCache = emptyList(),
                filter = filter,
                page = 1,
                skipRedditUnlessNeeded = true,
                staticFilter = categoryFilter,
            )
            val fetchedUrls = fetched.wallpapers
                .mapNotNull { it.thumbnailUrl.takeIf(String::isNotBlank) }
                .distinct()
            if (fetchedUrls.isNotEmpty()) {
                return@withContext ExploreCategoryProbeResult(
                    coverUrls = fetchedUrls.take(maxCount),
                    confirmedEmpty = false,
                )
            }
            if (!fetched.rateLimited && fetched.warningMessage == null) {
                return@withContext ExploreCategoryProbeResult(
                    coverUrls = emptyList(),
                    confirmedEmpty = true,
                )
            }
        }

        val redditOutcome = safeApiCall("Reddit/MobileWallpaper-cover") {
            redditApi.searchSubreddit(
                subreddit = "MobileWallpaper",
                query = topic.query,
                sort = "top",
                limit = 20,
            )
        }
        val posts = (redditOutcome as? ApiCallOutcome.Success)?.response?.body()?.data?.children.orEmpty()
        val redditUrls = posts.mapNotNull { child ->
            val post = child.data
            if (post.over18 || post.isSelf || post.isVideo) return@mapNotNull null
            extractStaticRedditImage(post)?.thumbnailUrl?.takeIf(String::isNotBlank)
        }
            .distinct()
            .take(maxCount)
        ExploreCategoryProbeResult(
            coverUrls = redditUrls,
            confirmedEmpty = false,
        )
    }

    /**
     * Instant Room-only check (0 network calls) returning how many wallpapers are already cached
     * for a static 4K Artworks hashtag within [selectedCategory] and [filter].
     */
    suspend fun getCachedStaticTagCount(
        tag: String,
        selectedCategory: String?,
        filter: WallpaperFilter = WallpaperFilter(),
    ): Int = withContext(Dispatchers.IO) {
        val cleanTag = tag.trim().removePrefix("#").trim()
        if (cleanTag.isEmpty()) return@withContext 0
        val tagFilter = StaticFilterState(
            selectedCategory = selectedCategory,
            selectedTag = cleanTag,
        )
        val effectiveKey = tagFilter.cacheKeyPart() ?: tagFilter.toQueryKey(HOME_TRENDING_KEY)
        val cacheKey = buildCacheKey(effectiveKey, isLive = false, filter = filter, page = 1)
        val cachedSize = wallpaperDao.getCachedWallpapers(cacheKey).size
        if (cachedSize > 0) {
            val recordedTotal = seriesTotalCounts[effectiveKey.lowercase()] ?: 0
            maxOf(cachedSize, recordedTotal)
        } else {
            0
        }
    }

    /**
     * Verifies a candidate static 4K Artworks hashtag against local Room cache and the real
     * Wallhaven API (while strictly preserving the 45 req/min rate limit budget).
     * Any fetched wallpapers are stored in Room so tapping the verified hashtag loads in 0ms.
     */
    suspend fun probeStaticTagAvailability(
        tag: String,
        selectedCategory: String?,
        filter: WallpaperFilter = WallpaperFilter(),
    ): StaticTagProbeResult = withContext(Dispatchers.IO) {
        val cleanTag = tag.trim().removePrefix("#").trim()
        if (cleanTag.isEmpty()) {
            return@withContext StaticTagProbeResult(tag = tag, matchCount = 0, confirmedEmpty = true)
        }
        val cachedCount = getCachedStaticTagCount(cleanTag, selectedCategory, filter)
        if (cachedCount > 0) {
            return@withContext StaticTagProbeResult(
                tag = cleanTag,
                matchCount = cachedCount,
                confirmedEmpty = false,
            )
        }
        // Protect Wallhaven's 45 req/min quota so user interactions always have headroom.
        if (wallhavenRateLimiter.remainingQuota() <= 14) {
            return@withContext StaticTagProbeResult(
                tag = cleanTag,
                matchCount = 0,
                confirmedEmpty = false,
            )
        }

        val tagFilter = StaticFilterState(
            selectedCategory = selectedCategory,
            selectedTag = cleanTag,
        )
        val effectiveKey = tagFilter.cacheKeyPart() ?: tagFilter.toQueryKey(HOME_TRENDING_KEY)
        val cacheKey = buildCacheKey(effectiveKey, isLive = false, filter = filter, page = 1)
        val fetched = coalesceStaticFetch(
            query = tagFilter.toQueryKey(HOME_TRENDING_KEY),
            cacheKey = cacheKey,
            staleCache = emptyList(),
            filter = filter,
            page = 1,
            skipRedditUnlessNeeded = true,
            staticFilter = tagFilter,
        )
        if (fetched.wallpapers.isNotEmpty()) {
            val count = maxOf(fetched.totalCount ?: 0, fetched.wallpapers.size)
            seriesTotalCounts[effectiveKey.lowercase()] = count
            StaticTagProbeResult(
                tag = cleanTag,
                matchCount = count,
                confirmedEmpty = false,
            )
        } else {
            val confirmedEmpty = !fetched.rateLimited && fetched.warningMessage == null
            StaticTagProbeResult(
                tag = cleanTag,
                matchCount = 0,
                confirmedEmpty = confirmedEmpty,
            )
        }
    }

    /**
     * Instant local-only preview lookup (0 network calls) for an Anime 200+ collection row.
     * Combines Room-cached static wallpapers and in-memory R2 live wallpapers in deterministic order
     * so each of the 4 strip tiles maps 1-to-1 to its exact index in DetailPreviewScreen.
     */
    suspend fun getCachedAnimeCategoryPreview(
        category: AnimeCategory,
        filter: WallpaperFilter = WallpaperFilter(),
    ): AnimeSeriesPreview? = withContext(Dispatchers.IO) {
        val queryCacheKey = buildCacheKey(category.query, isLive = false, filter = filter, page = 1)
        val titleCacheKey = buildCacheKey(category.title, isLive = false, filter = filter, page = 1)
        val cachedEntities = wallpaperDao.getCachedWallpapers(queryCacheKey).ifEmpty {
            if (!titleCacheKey.equals(queryCacheKey, ignoreCase = true)) {
                wallpaperDao.getCachedWallpapers(titleCacheKey)
            } else {
                emptyList()
            }
        }
        val staticWallpapers = cachedEntities
            .map { it.toDomain() }
            .filter { it.thumbnailUrl.isNotBlank() }
        val liveWallpapers = liveWallpaperRepository
            ?.getCachedCatalogSnapshot(category.title, page = 1, pageSize = 14)
            ?.items
            .orEmpty()
            .filter { it.thumbnailUrl.isNotBlank() }
        val combinedWallpapers = (staticWallpapers + liveWallpapers).distinctBy { it.id }
        if (combinedWallpapers.isEmpty()) return@withContext null

        val rememberedTotal = seriesTotalCounts[category.query.lowercase()]
            ?: seriesTotalCounts[category.title.lowercase()]
            ?: 0
        val estimatedTotal = maxOf(rememberedTotal, combinedWallpapers.size)

        AnimeSeriesPreview(
            categoryId = category.id,
            thumbnailUrls = combinedWallpapers.take(4).map { it.thumbnailUrl },
            wallpapers = combinedWallpapers,
            totalCount = estimatedTotal,
            isLoading = false,
        )
    }

    /**
     * Loads up to 4 real anime thumbnails, the ordered Page-1 wallpapers, and total wallpaper count
     * for an Anime 200+ row. Uses Room cache first (0 network calls), then Wallhaven (when quota
     * headroom > 12, caching Page 1 in Room), falling back to Reddit r/Animewallpaper.
     */
    suspend fun fetchAnimeCategoryPreview(
        category: AnimeCategory,
        filter: WallpaperFilter = WallpaperFilter(),
    ): AnimeSeriesPreview = withContext(Dispatchers.IO) {
        val cached = getCachedAnimeCategoryPreview(category, filter)
        val hasKnownTotal = seriesTotalCounts.containsKey(category.query.lowercase()) ||
                seriesTotalCounts.containsKey(category.title.lowercase())
        if (cached != null && cached.wallpapers.size >= 4 && (hasKnownTotal || cached.totalCount >= 12)) {
            return@withContext cached
        }

        val collectedById = LinkedHashMap<String, AppWallpaper>()
        cached?.wallpapers?.forEach { collectedById.putIfAbsent(it.id, it) }
        var resolvedTotal = cached?.totalCount ?: 0

        val cacheKey = buildCacheKey(category.query, isLive = false, filter = filter, page = 1)

        if (wallhavenRateLimiter.remainingQuota() > 12) {
            val pageResult = fetchWallhavenCategoryPage(
                category = category,
                filter = filter,
                page = 1,
                cacheKey = cacheKey,
            )
            pageResult.wallpapers.forEach { wp -> collectedById.putIfAbsent(wp.id, wp) }
            resolvedTotal = maxOf(resolvedTotal, pageResult.totalCount, collectedById.size)
        }

        if (collectedById.isEmpty()) {
            val redditOutcome = safeApiCall("Reddit/CategoryPreview") {
                redditApi.searchSubreddit(
                    subreddit = "Animewallpaper",
                    query = category.query,
                    sort = "top",
                    limit = 25,
                )
            }
            val posts = (redditOutcome as? ApiCallOutcome.Success)?.response?.body()?.data?.children.orEmpty()
            val redditWallpapers = posts.mapNotNull { child ->
                val post = child.data
                if (post.over18 || post.isSelf || post.isVideo) return@mapNotNull null
                val extracted = extractStaticRedditImage(post) ?: return@mapNotNull null
                AppWallpaper(
                    id = "rd_${post.id}",
                    title = post.title.ifBlank { category.title },
                    animeSeries = category.title,
                    imageUrl = extracted.originalUrl,
                    thumbnailUrl = extracted.thumbnailUrl,
                    videoUrl = null,
                    isLive = false,
                    width = extracted.width,
                    height = extracted.height,
                    source = "Reddit",
                )
            }
            redditWallpapers.forEach { wp -> collectedById.putIfAbsent(wp.id, wp) }
            resolvedTotal = maxOf(resolvedTotal, redditWallpapers.size, collectedById.size)
        }

        val orderedWallpapers = collectedById.values.toList()
        val liveCount = liveWallpaperRepository?.getCachedCatalogSnapshot(category.title)?.totalCount ?: 0
        if (liveCount > 0 && resolvedTotal <= orderedWallpapers.size) {
            resolvedTotal = maxOf(resolvedTotal, orderedWallpapers.size + liveCount)
        }

        AnimeSeriesPreview(
            categoryId = category.id,
            thumbnailUrls = orderedWallpapers.take(4).map { it.thumbnailUrl },
            wallpapers = orderedWallpapers,
            totalCount = maxOf(resolvedTotal, orderedWallpapers.size),
            isLoading = false,
        )
    }

    /**
     * Fetches subsequent pages (page 2, 3, ...) of an Anime 200+ collection using the exact same
     * Wallhaven query parameters as the preview strip so users can swipe smoothly through all
     * remaining (+N) wallpapers directly inside DetailPreviewScreen.
     */
    suspend fun fetchAnimeCategoryDetailPage(
        category: AnimeCategory,
        filter: WallpaperFilter = WallpaperFilter(),
        page: Int,
    ): CollectionPageResult = withContext(Dispatchers.IO) {
        val safePage = page.coerceAtLeast(1)
        val cacheKey = buildCacheKey(category.query, isLive = false, filter = filter, page = safePage)
        val cached = wallpaperDao.getCachedWallpapers(cacheKey).map { it.toDomain() }
        val rememberedTotal = seriesTotalCounts[category.query.lowercase()]
            ?: seriesTotalCounts[category.title.lowercase()]
            ?: 0
        if (cached.isNotEmpty() && isCacheFreshAndEligible(
                cacheKey = cacheKey,
                query = category.query,
                sort = filter.sort,
                isLive = false,
                forceRefresh = false,
            )
        ) {
            val estimatedTotal = maxOf(rememberedTotal, safePage * cached.size)
            val hasMore = if (rememberedTotal > 0) safePage * 24 < rememberedTotal else cached.size >= 18
            return@withContext CollectionPageResult(
                wallpapers = cached,
                totalCount = estimatedTotal,
                hasMore = hasMore,
            )
        }

        val result = fetchWallhavenCategoryPage(
            category = category,
            filter = filter,
            page = safePage,
            cacheKey = cacheKey,
        )
        if (result.wallpapers.isNotEmpty()) {
            return@withContext result
        }
        if (cached.isNotEmpty()) {
            return@withContext CollectionPageResult(
                wallpapers = cached,
                totalCount = maxOf(rememberedTotal, cached.size),
                hasMore = false,
            )
        }
        CollectionPageResult(
            wallpapers = emptyList(),
            totalCount = rememberedTotal,
            hasMore = false,
        )
    }

    private suspend fun fetchWallhavenCategoryPage(
        category: AnimeCategory,
        filter: WallpaperFilter,
        page: Int,
        cacheKey: String,
    ): CollectionPageResult {
        return when (wallhavenRateLimiter.tryAcquire()) {
            is WallhavenRateLimiter.AcquireResult.RateLimited -> {
                CollectionPageResult(
                    wallpapers = emptyList(),
                    totalCount = seriesTotalCounts[category.query.lowercase()] ?: 0,
                    hasMore = false,
                )
            }

            WallhavenRateLimiter.AcquireResult.Allowed -> {
                var outcome = safeApiCall("Wallhaven/CategoryPage") {
                    wallhavenApi.searchAnime(
                        query = category.query,
                        categories = "010",
                        purity = "100",
                        ratios = filter.ratio.wallhavenRatios,
                        atleast = filter.resolution.wallhavenAtleast(filter.ratio),
                        sorting = "toplist",
                        order = "desc",
                        topRange = "1y",
                        page = page,
                    )
                }
                if (outcome is ApiCallOutcome.HttpFailure && outcome.code == 429) {
                    wallhavenRateLimiter.recordServerRateLimit(outcome.retryAfterSeconds)
                    return CollectionPageResult(
                        wallpapers = emptyList(),
                        totalCount = seriesTotalCounts[category.query.lowercase()] ?: 0,
                        hasMore = false,
                    )
                }
                var body = (outcome as? ApiCallOutcome.Success)?.response?.body()
                var items = body?.data.orEmpty()
                if (items.isEmpty() &&
                    page == 1 &&
                    filter.ratio == RatioFilter.PORTRAIT_MOBILE &&
                    wallhavenRateLimiter.remainingQuota() > 12 &&
                    wallhavenRateLimiter.tryAcquire() is WallhavenRateLimiter.AcquireResult.Allowed
                ) {
                    val broaderOutcome = safeApiCall("Wallhaven/CategoryPage-portrait") {
                        wallhavenApi.searchAnime(
                            query = category.query,
                            categories = "010",
                            purity = "100",
                            ratios = RatioFilter.FALLBACK_PORTRAIT_RATIO,
                            atleast = null,
                            sorting = "toplist",
                            order = "desc",
                            topRange = "1y",
                            page = page,
                        )
                    }
                    if (broaderOutcome is ApiCallOutcome.Success) {
                        val broaderBody = broaderOutcome.response.body()
                        val broaderItems = broaderBody?.data.orEmpty()
                        if (broaderItems.size > items.size) {
                            outcome = broaderOutcome
                            body = broaderBody
                            items = broaderItems
                        }
                    }
                }
                val metaTotal = body?.meta?.total ?: 0
                val lastPage = body?.meta?.lastPage ?: page
                if (metaTotal > 0) {
                    seriesTotalCounts[category.query.lowercase()] = metaTotal
                    seriesTotalCounts[category.title.lowercase()] = metaTotal
                }
                val optimizedItems = prioritizeMobileWallhavenItems(items, filter.resolution)
                val mappedWallpapers = optimizedItems.mapNotNull { item ->
                    val safeImageUrl = sanitizeHttpsUrl(item.path) ?: return@mapNotNull null
                    val safeThumbUrl = resolveLightweightWallhavenThumb(item) ?: safeImageUrl
                    AppWallpaper(
                        id = "wh_${item.id}",
                        title = "${category.title} • ${item.dimensionX}×${item.dimensionY}",
                        animeSeries = category.title,
                        imageUrl = safeImageUrl,
                        thumbnailUrl = safeThumbUrl,
                        videoUrl = null,
                        isLive = false,
                        width = item.dimensionX,
                        height = item.dimensionY,
                        source = "Wallhaven",
                    )
                }.take(STATIC_PAGE_SIZE)
                if (mappedWallpapers.isNotEmpty()) {
                    val now = clock()
                    val cacheCat = CacheExpiryPolicy.resolveCategory(
                        query = category.query,
                        sort = filter.sort,
                        isLive = false,
                    )
                    val ttlMillis = CacheExpiryPolicy.ttlMillisFor(
                        query = category.query,
                        sort = filter.sort,
                        isLive = false,
                    )
                    wallpaperDao.replaceCacheForKey(
                        cacheKey = cacheKey,
                        items = mappedWallpapers.mapIndexed { idx, w ->
                            w.toCacheEntity(cacheKey, idx, cacheCat, now)
                        },
                        timestampMillis = now,
                        category = cacheCat,
                        expiresAtMillis = now + ttlMillis,
                    )
                }
                val resolvedTotal = maxOf(
                    metaTotal,
                    seriesTotalCounts[category.query.lowercase()] ?: 0,
                    mappedWallpapers.size,
                )
                val hasMore = when {
                    body?.meta != null -> page < lastPage
                    else -> mappedWallpapers.size >= 18
                }
                CollectionPageResult(
                    wallpapers = mappedWallpapers,
                    totalCount = resolvedTotal,
                    hasMore = hasMore,
                )
            }
        }
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
        liveFilter: LiveFilterState? = null,
        staticFilter: StaticFilterState? = null,
    ): WallpaperFetchResult = withContext(Dispatchers.IO) {
        val normalizedQuery = query.trim().ifEmpty { HOME_TRENDING_KEY }
        val effectiveStatic = if (!isLive) staticFilter?.takeIf { it.isChipFilterActive } else null
        val effectiveQueryKey = when {
            isLive && liveFilter != null -> liveFilter.cacheKey()
            effectiveStatic != null -> effectiveStatic.cacheKeyPart() ?: normalizedQuery
            else -> normalizedQuery
        }
        val cacheKey = buildCacheKey(effectiveQueryKey, isLive, filter, page)
        val cachedEntities = wallpaperDao.getCachedWallpapers(cacheKey)
        if (isLive) {
            return@withContext fetchLiveWallpapers(
                query = normalizedQuery,
                cacheKey = cacheKey,
                staleCache = cachedEntities,
                filter = filter,
                page = page,
                forceRefresh = forceRefresh,
                liveFilter = liveFilter,
            )
        }

        if (forceRefresh && page == 1) {
            return@withContext refreshStaticFeedWithQuotaGuard(
                normalizedQuery = normalizedQuery,
                effectiveQueryKey = effectiveQueryKey,
                cacheKey = cacheKey,
                staleCache = cachedEntities,
                filter = filter,
                staticFilter = effectiveStatic,
            )
        }

        if (effectiveStatic == null && normalizedQuery == HOME_TRENDING_KEY) {
            return@withContext fetchQueuedTrendingBatch(
                filter = filter,
                page = page,
                forceRefresh = false,
            )
        }

        if (isCacheFreshAndEligible(
                cacheKey = cacheKey,
                query = effectiveQueryKey,
                sort = filter.sort,
                isLive = false,
                forceRefresh = false,
            ) && cachedEntities.isNotEmpty()
        ) {
            return@withContext WallpaperFetchResult(
                wallpapers = cachedEntities.map { it.toDomain() },
                isFromCache = true,
                hasMore = true,
            )
        }

        coalesceStaticFetch(
            query = normalizedQuery,
            cacheKey = cacheKey,
            staleCache = cachedEntities,
            filter = filter,
            page = page,
            staticFilter = effectiveStatic,
        )
    }

    /**
     * Handles Pull-to-Refresh / Refresh button (↻) on the 4K Artworks tab:
     * - Always refreshes the visible wallpaper order/content (not just #Tags).
     * - Enforces Wallhaven's 45 req/min budget via [WallhavenRateLimiter.tryAcquireRefreshSlot]:
     *   - When within the 15s cooldown or quota headroom is low: consumes 0 network requests
     *     and serves a freshly shuffled mix from all locally cached Room pages/topics.
     *   - When network refresh is allowed: fetches a rotated batch (max 2 topics for Home Trending,
     *     1 rotated page for search/tag/category) so Wallhaven returns brand-new artworks.
     */
    private suspend fun refreshStaticFeedWithQuotaGuard(
        normalizedQuery: String,
        effectiveQueryKey: String,
        cacheKey: String,
        staleCache: List<CachedWallpaperEntity>,
        filter: WallpaperFilter,
        staticFilter: StaticFilterState? = null,
    ): WallpaperFetchResult = coroutineScope {
        val nextEpoch = ++staticRefreshEpoch
        val isHomeTrending = staticFilter == null && normalizedQuery == HOME_TRENDING_KEY
        val estimatedRequests = if (isHomeTrending) 2 else 1
        val localPool = collectLocalShufflePool(effectiveQueryKey, filter, staleCache)

        when (val decision = wallhavenRateLimiter.tryAcquireRefreshSlot(estimatedRequests = estimatedRequests)) {
            is WallhavenRateLimiter.RefreshSlotDecision.UseLocalShuffle -> {
                if (localPool.isNotEmpty()) {
                    val rng = Random(nextEpoch * 7919L + normalizedQuery.hashCode().toLong() * 31L)
                    val shuffled = localPool.shuffled(rng).take(36)
                    val warning = if (decision.quotaProtected) {
                        "Refreshed from cache — 45 req/min API guard active (~${decision.cooldownRemainingSec}s)"
                    } else {
                        null
                    }
                    return@coroutineScope WallpaperFetchResult(
                        wallpapers = shuffled,
                        isFromCache = true,
                        rateLimited = decision.quotaProtected,
                        hasMore = true,
                        warningMessage = warning,
                    )
                }
                // Cold empty cache on refresh: fall through to standard rate-limited fetch below
            }

            WallhavenRateLimiter.RefreshSlotDecision.NetworkAllowed -> {
                if (isHomeTrending) {
                    val topics = AnimeCatalog.trendingTopics
                    if (topics.isNotEmpty()) {
                        val queueBatches = buildTrendingQueueBatches(topics)
                        if (queueBatches.isNotEmpty()) {
                            staticTrendingBatchOffset = (staticTrendingBatchOffset + 1) % queueBatches.size
                            val targetBatch = queueBatches[staticTrendingBatchOffset].first.take(2)
                            val rotatedPage = 1 + ((nextEpoch / queueBatches.size) % 3)
                            val perTopicResults = targetBatch.map { topic ->
                                async {
                                    val topicKey = buildCacheKey(topic.title, isLive = false, filter = filter, page = rotatedPage)
                                    val topicCached = wallpaperDao.getCachedWallpapers(topicKey)
                                    coalesceStaticFetch(
                                        query = topic.title,
                                        cacheKey = topicKey,
                                        staleCache = topicCached,
                                        filter = filter,
                                        page = rotatedPage,
                                        skipRedditUnlessNeeded = true,
                                    )
                                }
                            }.awaitAll()

                            val interleaved = interleaveSeriesWallpapers(perTopicResults.map { it.wallpapers })
                            val anyRateLimited = perTopicResults.any { it.rateLimited }
                            val rng = Random(nextEpoch * 7919L + rotatedPage * 97L)

                            val combined = if (interleaved.size >= 6) {
                                interleaved.shuffled(rng)
                            } else {
                                (interleaved + localPool).distinctBy { it.id }.shuffled(rng).take(36)
                            }
                            if (combined.isNotEmpty()) {
                                val warning = perTopicResults.firstNotNullOfOrNull { res ->
                                    res.warningMessage?.takeIf { res.rateLimited }
                                }
                                return@coroutineScope WallpaperFetchResult(
                                    wallpapers = combined,
                                    isFromCache = interleaved.isEmpty(),
                                    rateLimited = anyRateLimited,
                                    hasMore = true,
                                    warningMessage = warning,
                                )
                            }
                        }
                    }
                } else {
                    val rotatedPage = 1 + (nextEpoch % 4)
                    val rotatedCacheKey = buildCacheKey(effectiveQueryKey, isLive = false, filter = filter, page = rotatedPage)
                    val rotatedCached = wallpaperDao.getCachedWallpapers(rotatedCacheKey)
                    val fetched = coalesceStaticFetch(
                        query = normalizedQuery,
                        cacheKey = rotatedCacheKey,
                        staleCache = rotatedCached,
                        filter = filter,
                        page = rotatedPage,
                        skipRedditUnlessNeeded = true,
                        staticFilter = staticFilter,
                    )
                    val rng = Random(nextEpoch * 7919L + effectiveQueryKey.hashCode().toLong() * 31L + rotatedPage)
                    if (fetched.wallpapers.isNotEmpty()) {
                        return@coroutineScope fetched.copy(
                            wallpapers = fetched.wallpapers.shuffled(rng),
                            hasMore = true,
                        )
                    }
                    if (localPool.isNotEmpty()) {
                        return@coroutineScope WallpaperFetchResult(
                            wallpapers = localPool.shuffled(rng).take(36),
                            isFromCache = true,
                            rateLimited = fetched.rateLimited,
                            hasMore = true,
                            warningMessage = fetched.warningMessage,
                        )
                    }
                }
            }
        }

        if (isHomeTrending) {
            fetchQueuedTrendingBatch(filter = filter, page = 1, forceRefresh = false)
        } else {
            coalesceStaticFetch(
                query = normalizedQuery,
                cacheKey = cacheKey,
                staleCache = staleCache,
                filter = filter,
                page = 1,
                staticFilter = staticFilter,
            )
        }
    }

    private suspend fun collectLocalShufflePool(
        normalizedQuery: String,
        filter: WallpaperFilter,
        fallbackEntities: List<CachedWallpaperEntity>,
    ): List<AppWallpaper> {
        val collected = LinkedHashMap<String, AppWallpaper>()
        if (normalizedQuery == HOME_TRENDING_KEY) {
            for (topic in AnimeCatalog.trendingTopics) {
                for (p in 1..2) {
                    val key = buildCacheKey(topic.title, isLive = false, filter = filter, page = p)
                    wallpaperDao.getCachedWallpapers(key).forEach { entity ->
                        val wp = entity.toDomain()
                        collected.putIfAbsent(wp.id, wp)
                    }
                }
            }
        } else {
            for (p in 1..4) {
                val key = buildCacheKey(normalizedQuery, isLive = false, filter = filter, page = p)
                wallpaperDao.getCachedWallpapers(key).forEach { entity ->
                    val wp = entity.toDomain()
                    collected.putIfAbsent(wp.id, wp)
                }
            }
            val category = CacheExpiryPolicy.resolveCategory(
                query = normalizedQuery,
                sort = filter.sort,
                isLive = false,
            )
            wallpaperDao.getCachedWallpapersByCategorySync(category).forEach { entity ->
                val wp = entity.toDomain()
                collected.putIfAbsent(wp.id, wp)
            }
        }
        fallbackEntities.forEach { entity ->
            val wp = entity.toDomain()
            collected.putIfAbsent(wp.id, wp)
        }
        return collected.values.toList()
    }

    private fun buildTrendingQueueBatches(
        topics: List<com.example.domain.model.TrendingAnimeTopic>,
    ): List<Pair<List<com.example.domain.model.TrendingAnimeTopic>, Int>> {
        return listOf(
            topics.slice(0 until minOf(3, topics.size)) to 1,
            (if (topics.size > 3) topics.slice(3 until minOf(6, topics.size)) else emptyList()) to 1,
            (if (topics.size > 6) topics.slice(6 until minOf(8, topics.size)) else emptyList()) to 1,
            (if (topics.size > 8) topics.slice(8 until topics.size) else emptyList()) to 1,
        ).filter { it.first.isNotEmpty() }
    }

    private suspend fun isCacheFreshAndEligible(
        cacheKey: String,
        query: String,
        sort: SortOption,
        isLive: Boolean,
        forceRefresh: Boolean,
    ): Boolean {
        val meta = wallpaperDao.getCacheMeta(cacheKey)
        val cachedTimestamp = meta?.fetchedAtMillis ?: wallpaperDao.getCacheTimestamp(cacheKey) ?: return false
        val expiresAtMillis = meta?.expiresAtMillis ?: 0L
        val now = clock()
        val ttlMillis = CacheExpiryPolicy.ttlMillisFor(query = query, sort = sort, isLive = isLive)
        val isCacheFresh = CacheExpiryPolicy.isFresh(
            fetchedAtMillis = cachedTimestamp,
            expiresAtMillis = expiresAtMillis,
            ttlMillis = ttlMillis,
            nowMillis = now,
        )
        val isRecentlyRefreshed = CacheExpiryPolicy.isWithinForceRefreshCooldown(
            fetchedAtMillis = cachedTimestamp,
            nowMillis = now,
        )
        return (!forceRefresh || isRecentlyRefreshed) && isCacheFresh
    }

    private fun resolveTrendingBatch(page: Int): Pair<List<com.example.domain.model.TrendingAnimeTopic>, Int>? {
        val topics = AnimeCatalog.trendingTopics
        if (topics.isEmpty()) return null
        val queueBatches = buildTrendingQueueBatches(topics)
        if (queueBatches.isEmpty()) return null
        val offset = staticTrendingBatchOffset % queueBatches.size

        return if (page <= queueBatches.size) {
            queueBatches[(page - 1 + offset) % queueBatches.size]
        } else {
            val overflowIndex = page - queueBatches.size - 1
            val chunked = topics.chunked(3)
            val roundTopics = chunked[(overflowIndex + offset) % chunked.size]
            val extraPage = 2 + (overflowIndex / chunked.size)
            roundTopics to extraPage
        }
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

        val queueBatches = buildTrendingQueueBatches(topics)
        val (batchTopics, topicPage) = resolveTrendingBatch(page)
            ?: return@coroutineScope WallpaperFetchResult(wallpapers = emptyList(), hasMore = false)

        val perTopicResults = batchTopics.map { topic ->
            async {
                val topicCacheKey = buildCacheKey(topic.title, isLive = false, filter = filter, page = topicPage)
                val topicCached = wallpaperDao.getCachedWallpapers(topicCacheKey)
                val result = if (isCacheFreshAndEligible(
                        cacheKey = topicCacheKey,
                        query = topic.title,
                        sort = filter.sort,
                        isLive = false,
                        forceRefresh = forceRefresh,
                    ) && topicCached.isNotEmpty()
                ) {
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
            .take(STATIC_PAGE_SIZE)
        val anyRateLimited = perTopicResults.any { it.second.rateLimited }
        val allFromCache = perTopicResults.isNotEmpty() && perTopicResults.all { it.second.isFromCache }
        val warning = if (interleaved.isNotEmpty()) {
            perTopicResults.firstNotNullOfOrNull { pair ->
                pair.second.warningMessage?.takeIf { pair.second.rateLimited }
            }
        } else {
            perTopicResults.firstNotNullOfOrNull { it.second.warningMessage }
        }
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
        staticFilter: StaticFilterState? = null,
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
                            staticFilter = staticFilter,
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
        staticFilter: StaticFilterState? = null,
    ): WallpaperFetchResult = coroutineScope {
        val activeStaticChip = staticFilter?.takeIf { it.isChipFilterActive }
        val isHomeTrending = activeStaticChip == null && query == HOME_TRENDING_KEY
        val selectedCategoryTopic = activeStaticChip?.selectedCategory?.let { AnimeCatalog.findTrendingTopic(it) }
        val selectedTag = activeStaticChip?.selectedTag?.trim()?.removePrefix("#")?.trim()?.takeIf { it.isNotEmpty() }
        val trendingTopic = when {
            isHomeTrending -> null
            selectedCategoryTopic != null -> selectedCategoryTopic
            selectedTag != null -> null
            else -> AnimeCatalog.findTrendingTopic(query)
        }

        val wallhavenQuery = when {
            isHomeTrending -> null
            selectedCategoryTopic != null && selectedTag != null ->
                selectedCategoryTopic.buildCombinedTagQuery(selectedTag)
            selectedCategoryTopic != null -> selectedCategoryTopic.query
            selectedTag != null -> selectedTag
            trendingTopic != null -> trendingTopic.query
            else -> query
        }
        val wallhavenCategories = when {
            selectedCategoryTopic != null -> selectedCategoryTopic.categories
            selectedTag != null -> "110"
            trendingTopic != null -> trendingTopic.categories
            else -> "010"
        }
        val wallhavenPurity = trendingTopic?.purity ?: "100"
        val wallhavenSorting = when {
            isHomeTrending && filter.sort == SortOption.RELEVANCE -> SortOption.TOPLIST.wallhavenSorting
            selectedCategoryTopic != null && selectedTag != null && filter.sort == SortOption.RELEVANCE ->
                SortOption.RELEVANCE.wallhavenSorting
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
            selectedCategoryTopic != null && selectedTag != null -> "${selectedCategoryTopic.title} $selectedTag"
            selectedCategoryTopic != null -> selectedCategoryTopic.title
            selectedTag != null -> selectedTag
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
                            atleast = filter.resolution.wallhavenAtleast(filter.ratio),
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

        var wallhavenOutcome = wallhavenTask.await()
        var rateLimited = localQuotaLimited ||
                (wallhavenOutcome is ApiCallOutcome.HttpFailure && wallhavenOutcome.code == 429)
        var wallhavenBody = (wallhavenOutcome as? ApiCallOutcome.Success)?.response?.body()
        var wallhavenItems = wallhavenBody?.data.orEmpty()

        // If strict mobile ratios (9:16..9:21) returned zero matches on page 1 for a niche query,
        // gracefully widen to general "portrait" so users still get crisp mobile vertical wallpapers.
        if (!rateLimited &&
            wallhavenItems.isEmpty() &&
            page == 1 &&
            filter.ratio == RatioFilter.PORTRAIT_MOBILE &&
            wallhavenRateLimiter.remainingQuota() > 10 &&
            wallhavenRateLimiter.tryAcquire() is WallhavenRateLimiter.AcquireResult.Allowed
        ) {
            val broaderOutcome = safeApiCall("Wallhaven/portrait-fallback") {
                wallhavenApi.searchAnime(
                    query = wallhavenQuery,
                    categories = wallhavenCategories,
                    purity = wallhavenPurity,
                    ratios = RatioFilter.FALLBACK_PORTRAIT_RATIO,
                    atleast = filter.resolution.wallhavenAtleast(filter.ratio),
                    sorting = wallhavenSorting,
                    order = wallhavenOrder,
                    topRange = if (wallhavenSorting == "toplist") "1y" else null,
                    page = wallhavenPage,
                )
            }
            if (broaderOutcome is ApiCallOutcome.HttpFailure && broaderOutcome.code == 429) {
                wallhavenRateLimiter.recordServerRateLimit(broaderOutcome.retryAfterSeconds)
                rateLimited = true
            } else if (broaderOutcome is ApiCallOutcome.Success) {
                val broaderBody = broaderOutcome.response.body()
                val broaderItems = broaderBody?.data.orEmpty()
                if (broaderItems.size > wallhavenItems.size) {
                    wallhavenOutcome = broaderOutcome
                    wallhavenBody = broaderBody
                    wallhavenItems = broaderItems
                }
            }
        }

        val wallhavenMetaTotal = wallhavenBody?.meta?.total ?: 0
        if (wallhavenMetaTotal > 0 && !isHomeTrending) {
            seriesTotalCounts[query.lowercase()] = wallhavenMetaTotal
            trendingTopic?.title?.lowercase()?.let { seriesTotalCounts[it] = wallhavenMetaTotal }
        }

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

        val wallhavenSucceeded = wallhavenOutcome is ApiCallOutcome.Success
        val redditSucceeded = redditResponses.isNotEmpty()
        val wallhavenHardError = (wallhavenOutcome is ApiCallOutcome.NetworkFailure) ||
                (wallhavenOutcome is ApiCallOutcome.HttpFailure && wallhavenOutcome.code != 429)
        // Only treat the request as a server/connection outage if NO upstream source succeeded.
        // Secondary Reddit 403/429 responses must never trigger a false "Unable to reach wallpaper servers"
        // error when Wallhaven already responded cleanly with HTTP 200.
        val hadNetworkOrHttpError = !wallhavenSucceeded && !redditSucceeded && (
                wallhavenHardError ||
                        redditOutcomes.any { it is ApiCallOutcome.NetworkFailure || it is ApiCallOutcome.HttpFailure }
                )

        val combined = mutableListOf<AppWallpaper>()
        val displaySeries = when {
            isHomeTrending -> "Hot Anime"
            trendingTopic != null -> trendingTopic.title
            else -> query
        }
        val optimizedWallhavenItems = prioritizeMobileWallhavenItems(wallhavenItems, filter.resolution)
        optimizedWallhavenItems.forEach { item ->
            val safeImageUrl = sanitizeHttpsUrl(item.path) ?: return@forEach
            val safeThumbUrl = resolveLightweightWallhavenThumb(item) ?: safeImageUrl
            combined.add(
                AppWallpaper(
                    id = "wh_${item.id}",
                    title = "$displaySeries • ${item.dimensionX}×${item.dimensionY}",
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

        val distinctItems = combined.distinctBy { it.id }.take(STATIC_PAGE_SIZE)

        if (distinctItems.isNotEmpty()) {
            val now = clock()
            val category = CacheExpiryPolicy.resolveCategory(
                query = query,
                sort = filter.sort,
                isLive = false,
            )
            val ttlMillis = CacheExpiryPolicy.ttlMillisFor(
                query = query,
                sort = filter.sort,
                isLive = false,
            )
            wallpaperDao.replaceCacheForKey(
                cacheKey = cacheKey,
                items = distinctItems.mapIndexed { idx, w -> w.toCacheEntity(cacheKey, idx, category, now) },
                timestampMillis = now,
                category = category,
                expiresAtMillis = now + ttlMillis,
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
                totalCount = maxOf(wallhavenMetaTotal, distinctItems.size),
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
        forceRefresh: Boolean = false,
        liveFilter: LiveFilterState? = null,
    ): WallpaperFetchResult = coroutineScope {
        val isEffectiveHome = liveFilter?.isHome ?: (query == HOME_TRENDING_KEY)
        if (liveWallpaperRepository != null) {
            val r2PageResult = if (liveFilter != null) {
                liveWallpaperRepository.getLiveWallpapersPage(
                    filterState = liveFilter,
                    page = page,
                    pageSize = LiveWallpaperRepository.DEFAULT_PAGE_SIZE,
                    forceRefresh = forceRefresh,
                )
            } else {
                liveWallpaperRepository.getLiveWallpapersPage(
                    query = query,
                    page = page,
                    pageSize = LiveWallpaperRepository.DEFAULT_PAGE_SIZE,
                    forceRefresh = forceRefresh,
                )
            }
            r2PageResult.onSuccess { livePage ->
                if (livePage.items.isNotEmpty()) {
                    val now = clock()
                    val cacheCategoryKey = liveFilter?.cacheKey() ?: query
                    val category = CacheExpiryPolicy.resolveCategory(query = cacheCategoryKey, sort = filter.sort, isLive = true)
                    val ttlMillis = CacheExpiryPolicy.ttlMillisFor(query = cacheCategoryKey, sort = filter.sort, isLive = true)
                    wallpaperDao.replaceCacheForKey(
                        cacheKey = cacheKey,
                        items = livePage.items.mapIndexed { idx, w -> w.toCacheEntity(cacheKey, idx, category, now) },
                        timestampMillis = now,
                        category = category,
                        expiresAtMillis = now + ttlMillis,
                    )
                    return@coroutineScope WallpaperFetchResult(
                        wallpapers = livePage.items,
                        isFromCache = false,
                        hasMore = livePage.hasMore,
                        totalCount = livePage.totalCount,
                    )
                }
                if (page > 1 || livePage.totalCount > 0) {
                    return@coroutineScope WallpaperFetchResult(
                        wallpapers = emptyList(),
                        isFromCache = false,
                        hasMore = false,
                        warningMessage = null,
                        totalCount = livePage.totalCount,
                    )
                }
                if (isEffectiveHome) {
                    runCatching { Log.w("LIVE_DEBUG", "R2 Live catalog returned 0 items for $query (page=$page)") }
                    return@coroutineScope WallpaperFetchResult(
                        wallpapers = staleCache.map { it.toDomain() },
                        isFromCache = staleCache.isNotEmpty(),
                        hasMore = false,
                        warningMessage = if (staleCache.isNotEmpty()) {
                            "Live catalog returned empty — showing cached live wallpapers."
                        } else {
                            "Live catalog returned 0 wallpapers. Verify live_wallpapers_v1.json on jsDelivr/GitHub."
                        },
                        totalCount = staleCache.size,
                    )
                }
                // R2 catalog loaded cleanly but 0 live wallpapers matched this specific search keyword/tag/facet.
                return@coroutineScope WallpaperFetchResult(
                    wallpapers = emptyList(),
                    isFromCache = false,
                    hasMore = false,
                    warningMessage = null,
                    totalCount = 0,
                )
            }.onFailure { err ->
                runCatching {
                    Log.e("LIVE_DEBUG", "R2 Live catalog fetch failed (query=$query, page=$page): ${err.message}", err)
                }
                if (staleCache.isNotEmpty()) {
                    return@coroutineScope WallpaperFetchResult(
                        wallpapers = staleCache.map { it.toDomain() },
                        isFromCache = true,
                        hasMore = false,
                        warningMessage = "Offline mode — showing cached live wallpapers.",
                    )
                }
                return@coroutineScope WallpaperFetchResult(
                    wallpapers = emptyList(),
                    isFromCache = false,
                    hasMore = false,
                    warningMessage = "Failed to load Live catalog (${err.javaClass.simpleName}: ${err.localizedMessage ?: "network error"}). Check Logcat LIVE_DEBUG.",
                )
            }
        }

        val isHomeTrending = query == HOME_TRENDING_KEY
        if (page > 1 || isHomeTrending) {
            return@coroutineScope WallpaperFetchResult(
                wallpapers = emptyList(),
                isFromCache = false,
                hasMore = false,
            )
        }

        if (isCacheFreshAndEligible(
                cacheKey = cacheKey,
                query = query,
                sort = filter.sort,
                isLive = true,
                forceRefresh = forceRefresh,
            ) && staleCache.isNotEmpty()
        ) {
            return@coroutineScope WallpaperFetchResult(
                wallpapers = staleCache.map { it.toDomain() },
                isFromCache = true,
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
            val now = clock()
            val category = CacheExpiryPolicy.resolveCategory(
                query = query,
                sort = filter.sort,
                isLive = true,
            )
            val ttlMillis = CacheExpiryPolicy.ttlMillisFor(
                query = query,
                sort = filter.sort,
                isLive = true,
            )
            wallpaperDao.replaceCacheForKey(
                cacheKey = cacheKey,
                items = distinctLive.mapIndexed { idx, w -> w.toCacheEntity(cacheKey, idx, category, now) },
                timestampMillis = now,
                category = category,
                expiresAtMillis = now + ttlMillis,
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

    /**
     * Resolves a lightweight Wallhaven thumbnail URL (~30–80 KB) for fast grid rendering,
     * preferring `thumbs.original` (preserves mobile portrait aspect ratio without 3:2 server crop),
     * then `thumbs.large`, then `thumbs.small` — never returning the raw full-res `item.path` unless
     * all thumbnail variants are missing.
     */
    private fun resolveLightweightWallhavenThumb(item: com.example.data.remote.WallhavenItem): String? {
        return sanitizeHttpsUrl(item.thumbs.original)
            ?: sanitizeHttpsUrl(item.thumbs.large)
            ?: sanitizeHttpsUrl(item.thumbs.small)
    }

    /**
     * Prioritizes mobile-optimal FHD+ (1080x1920..1080x2400) and 2K (<= 1440x3200) wallpapers
     * while avoiding ultra-heavy raw 4K/8K desktop files (> 8 MB or > 4096 px) unless the user
     * explicitly requested [ResolutionTier.UHD_4K].
     */
    private fun prioritizeMobileWallhavenItems(
        items: List<com.example.data.remote.WallhavenItem>,
        tier: ResolutionTier,
    ): List<com.example.data.remote.WallhavenItem> {
        if (items.isEmpty() || tier == ResolutionTier.UHD_4K) return items
        val mobileFriendly = items.filter { item ->
            val maxEdge = maxOf(item.dimensionX, item.dimensionY)
            val withinDimensionCap = maxEdge <= MAX_MOBILE_OPTIMAL_EDGE_PX
            val withinFileSizeCap = item.fileSize <= 0L || item.fileSize <= MAX_MOBILE_FILE_SIZE_BYTES
            withinDimensionCap && withinFileSizeCap
        }
        val pool = if (mobileFriendly.size >= minOf(4, items.size)) mobileFriendly else items
        return pool.sortedBy { item ->
            val maxEdge = maxOf(item.dimensionX, item.dimensionY)
            val isPortrait = item.dimensionY >= item.dimensionX
            when {
                isPortrait && maxEdge in 1920..2600 -> 0 // Ideal FHD+ / 2K mobile portrait
                isPortrait && maxEdge in 1280..3200 -> 1 // Crisp portrait up to 2K+
                isPortrait -> 2
                else -> 3
            }
        }
    }

    private fun buildCacheKey(
        query: String,
        isLive: Boolean,
        filter: WallpaperFilter,
        page: Int,
    ): String {
        val type = if (isLive) "live" else "static_v3"
        return "$type:${query.lowercase()}:${filter.resolution.name}:${filter.sort.name}:${filter.ratio.name}:p$page"
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

    private fun AppWallpaper.toCacheEntity(
        cacheKey: String,
        index: Int,
        category: String,
        cachedAt: Long,
    ): CachedWallpaperEntity =
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
            category = category,
            cachedAt = cachedAt,
        )
}
