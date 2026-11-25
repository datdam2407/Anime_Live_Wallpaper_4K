package com.example

import com.example.data.local.CacheExpiryPolicy
import com.example.data.local.LiveWallpaperDownloader
import com.example.data.local.dao.WallpaperDao
import com.example.data.local.entity.CachedWallpaperEntity
import com.example.data.local.entity.FavoriteWallpaperEntity
import com.example.data.local.entity.QueryCacheMetaEntity
import com.example.data.remote.LiveCatalogApi
import com.example.data.remote.LiveCatalogResponse
import com.example.data.remote.LiveWallpaperItem
import com.example.data.remote.RedditChild
import com.example.data.remote.RedditImageSource
import com.example.data.remote.RedditListingData
import com.example.data.remote.RedditPost
import com.example.data.remote.RedditPreview
import com.example.data.remote.RedditPreviewImage
import com.example.data.remote.RedditResponse
import com.example.data.remote.RedditService
import com.example.data.remote.WallhavenItem
import com.example.data.remote.WallhavenResponse
import com.example.data.remote.WallhavenService
import com.example.data.remote.WallhavenThumbs
import com.example.data.repository.LiveWallpaperRepository
import com.example.data.repository.WallpaperRepository
import com.example.domain.model.RatioFilter
import com.example.domain.model.ResolutionTier
import com.example.domain.model.SortOption
import com.example.domain.model.WallpaperFilter
import java.io.File
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class WallpaperRepositoryTest {

    private class FakeWallpaperDao : WallpaperDao {
        private val favorites = MutableStateFlow<List<FavoriteWallpaperEntity>>(emptyList())
        private val cacheFlow = MutableStateFlow<Map<String, List<CachedWallpaperEntity>>>(emptyMap())
        private val meta = mutableMapOf<String, QueryCacheMetaEntity>()

        override fun observeFavorites(): Flow<List<FavoriteWallpaperEntity>> = favorites
        override fun observeFavoriteIds(): Flow<List<String>> = favorites.map { list -> list.map { it.id } }
        override suspend fun isFavorite(id: String): Boolean = favorites.value.any { it.id == id }
        override suspend fun insertFavorite(entity: FavoriteWallpaperEntity) {
            favorites.value = favorites.value.filterNot { it.id == entity.id } + entity
        }
        override suspend fun deleteFavoriteById(id: String) {
            favorites.value = favorites.value.filterNot { it.id == id }
        }
        override fun observeCachedWallpapers(cacheKey: String): Flow<List<CachedWallpaperEntity>> =
            cacheFlow.map { map -> map[cacheKey].orEmpty() }
        override fun observeCachedWallpapersByCategory(category: String): Flow<List<CachedWallpaperEntity>> =
            cacheFlow.map { map ->
                map.values.flatten()
                    .filter { it.category == category }
                    .sortedWith(compareByDescending<CachedWallpaperEntity> { it.cachedAt }.thenBy { it.positionIndex })
            }
        override suspend fun getCachedWallpapers(cacheKey: String): List<CachedWallpaperEntity> =
            cacheFlow.value[cacheKey].orEmpty()
        override suspend fun getCachedWallpapersByCategorySync(category: String): List<CachedWallpaperEntity> =
            cacheFlow.value.values.flatten()
                .filter { it.category == category }
                .sortedWith(compareByDescending<CachedWallpaperEntity> { it.cachedAt }.thenBy { it.positionIndex })
        override suspend fun getCacheMeta(cacheKey: String): QueryCacheMetaEntity? = meta[cacheKey]
        override suspend fun getCacheTimestamp(cacheKey: String): Long? = meta[cacheKey]?.fetchedAtMillis
        override fun observeCacheCount(): Flow<Int> =
            cacheFlow.map { map -> map.values.sumOf { it.size } }
        override suspend fun insertCacheMeta(meta: QueryCacheMetaEntity) {
            this.meta[meta.cacheKey] = meta
        }
        override suspend fun insertCachedWallpapers(items: List<CachedWallpaperEntity>) {
            val key = items.firstOrNull()?.cacheKey ?: return
            cacheFlow.value = cacheFlow.value + (key to items)
        }
        override suspend fun deleteCacheForKey(cacheKey: String) {
            cacheFlow.value = cacheFlow.value - cacheKey
        }
        override suspend fun deleteCacheByCategory(category: String) {
            cacheFlow.value = cacheFlow.value.mapValues { (_, list) ->
                list.filterNot { it.category == category }
            }.filterValues { it.isNotEmpty() }
        }
        override suspend fun deleteCacheMetaByCategory(category: String) {
            meta.entries.removeAll { it.value.category == category }
        }
        override suspend fun clearAllCachedWallpapers() {
            cacheFlow.value = emptyMap()
        }
        override suspend fun clearAllCacheMeta() {
            meta.clear()
        }
        override suspend fun deleteExpiredCachedWallpapers(cutoffMillis: Long) {
            val validKeys = meta.filterValues { it.fetchedAtMillis >= cutoffMillis }.keys
            cacheFlow.value = cacheFlow.value.filterKeys { it in validKeys }
        }
        override suspend fun deleteExpiredCacheMeta(cutoffMillis: Long) {
            meta.entries.removeAll { it.value.fetchedAtMillis < cutoffMillis }
        }
    }

    @Test
    fun `includes non-FHD reddit images when resolution tier is ALL and caches 24h`() = runTest {
        var wallhavenCalls = 0
        val fakeWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                wallhavenCalls++
                return Response.success(
                    WallhavenResponse(
                        data = listOf(
                            WallhavenItem(
                                id = "wh1",
                                path = "https://w.wallhaven.cc/full/wh1.png",
                                dimensionX = 2160,
                                dimensionY = 3840,
                                thumbs = WallhavenThumbs(original = "https://th.wallhaven.cc/orig/wh1.jpg"),
                            ),
                        ),
                    ),
                )
            }
        }

        val fakeReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(
                RedditResponse(
                    data = RedditListingData(
                        children = listOf(
                            RedditChild(
                                RedditPost(
                                    id = "hd720",
                                    title = "720p Portrait",
                                    url = "https://i.redd.it/hd720.jpg",
                                    thumbnail = "https://b.thumbs.redditmedia.com/hd720.jpg",
                                    preview = RedditPreview(
                                        images = listOf(
                                            RedditPreviewImage(
                                                source = RedditImageSource("https://i.redd.it/hd720.jpg", 720, 1280),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                            RedditChild(
                                RedditPost(
                                    id = "qhd1",
                                    title = "Gojo 2K",
                                    url = "https://i.redd.it/qhd1.png",
                                    thumbnail = "https://b.thumbs.redditmedia.com/qhd1.jpg",
                                    preview = RedditPreview(
                                        images = listOf(
                                            RedditPreviewImage(
                                                source = RedditImageSource("https://i.redd.it/qhd1.png", 1440, 2560),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        val repo = WallpaperRepository(fakeWallhaven, fakeReddit, FakeWallpaperDao())
        val firstResult = repo.getWallpapers(
            query = "Jujutsu Kaisen",
            isLive = false,
            filter = WallpaperFilter(resolution = ResolutionTier.ALL),
        )
        assertFalse(firstResult.isFromCache)
        assertEquals(3, firstResult.wallpapers.size)
        assertTrue(firstResult.wallpapers.any { it.id == "rd_hd720" && it.resolutionLabel == "HD" })

        // Second call within 24h returns straight from Room cache without hitting Wallhaven again
        val cachedResult = repo.getWallpapers(
            query = "Jujutsu Kaisen",
            isLive = false,
            filter = WallpaperFilter(resolution = ResolutionTier.ALL),
        )
        assertTrue(cachedResult.isFromCache)
        assertEquals(1, wallhavenCalls)
    }

    @Test
    fun `home trending fetches initial 3 hot anime and queues remaining series on scroll`() = runTest {
        val capturedQueries = Collections.synchronizedList(mutableListOf<String?>())
        val capturedPages = Collections.synchronizedList(mutableListOf<Int>())
        val fakeWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                capturedQueries.add(query)
                capturedPages.add(page)
                val safeId = (query ?: "top").replace(Regex("[^a-zA-Z0-9]"), "")
                return Response.success(
                    WallhavenResponse(
                        data = listOf(
                            WallhavenItem(
                                id = "${safeId}_p$page",
                                path = "https://w.wallhaven.cc/full/${safeId}_p$page.png",
                                dimensionX = 2160,
                                dimensionY = 3840,
                                thumbs = WallhavenThumbs(original = "https://th.wallhaven.cc/orig/${safeId}_p$page.jpg"),
                            ),
                        ),
                    ),
                )
            }
        }

        val emptyReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(RedditResponse())
        }

        val repo = WallpaperRepository(
            wallhavenApi = fakeWallhaven,
            redditApi = emptyReddit,
            wallpaperDao = FakeWallpaperDao(),
            wallhavenRateLimiter = com.example.data.remote.WallhavenRateLimiter(minSpacingMillis = 0L),
        )
        val page1 = repo.getWallpapers(
            query = WallpaperRepository.HOME_TRENDING_KEY,
            isLive = false,
            filter = WallpaperFilter(),
            page = 1,
        )

        assertEquals(
            setOf("Frieren: Beyond Journey's End", "Jujutsu Kaisen", "JoJo's Bizarre Adventure"),
            capturedQueries.toSet(),
        )
        assertEquals(listOf(1, 1, 3), capturedPages.sorted())
        assertEquals(3, page1.wallpapers.size)
        assertTrue(page1.hasMore)
        assertTrue(page1.wallpapers.none { it.animeSeries == "Trending Anime" })

        val page2 = repo.getWallpapers(
            query = WallpaperRepository.HOME_TRENDING_KEY,
            isLive = false,
            filter = WallpaperFilter(),
            page = 2,
        )
        assertEquals(6, capturedQueries.size)
        assertEquals(
            setOf("Re:Zero", "Oshi no Ko", "Witch Hat Atelier"),
            capturedQueries.drop(3).toSet(),
        )
        assertEquals(3, page2.wallpapers.size)
    }

    @Test
    fun `handles wallhaven 429 rate limit gracefully and preserves reddit results`() = runTest {
        val rateLimitedWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> = Response.error(429, "Too Many Requests".toResponseBody())
        }

        val fakeReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(
                RedditResponse(
                    data = RedditListingData(
                        children = listOf(
                            RedditChild(
                                RedditPost(
                                    id = "rd99",
                                    title = "Frieren 4K",
                                    url = "https://i.redd.it/rd99.jpg",
                                    thumbnail = "https://b.thumbs.redditmedia.com/rd99.jpg",
                                    preview = RedditPreview(
                                        images = listOf(
                                            RedditPreviewImage(
                                                source = RedditImageSource("https://i.redd.it/rd99.jpg", 2160, 3840),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }

        val repo = WallpaperRepository(rateLimitedWallhaven, fakeReddit, FakeWallpaperDao())
        val result = repo.getWallpapers("Frieren", isLive = false, filter = WallpaperFilter())

        assertTrue(result.rateLimited)
        assertEquals(1, result.wallpapers.size)
    }

    @Test
    fun `prefetchTrendingQueueBatch warms page 2 into cache so scroll uses cache without extra API calls`() = runTest {
        val wallhavenCalls = AtomicInteger(0)
        val wallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                val callNum = wallhavenCalls.incrementAndGet()
                val q = query ?: "anime"
                return Response.success(
                    WallhavenResponse(
                        data = listOf(
                            WallhavenItem(
                                id = "${q.hashCode()}_$page",
                                path = "https://w.wallhaven.cc/full/01/sample_$callNum.jpg",
                                dimensionX = 2160,
                                dimensionY = 3840,
                                thumbs = WallhavenThumbs(
                                    large = "https://th.wallhaven.cc/lg/01/sample_$callNum.jpg",
                                    original = "https://th.wallhaven.cc/orig/01/sample_$callNum.jpg",
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
        val unusedReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(RedditResponse())
        }
        val repo = WallpaperRepository(
            wallhavenApi = wallhaven,
            redditApi = unusedReddit,
            wallpaperDao = FakeWallpaperDao(),
        )

        repo.warmupLiveCatalog()
        repo.prefetchTrendingQueueBatch(filter = WallpaperFilter(), page = 2)
        val callsAfterPrefetch = wallhavenCalls.get()
        assertEquals(3, callsAfterPrefetch)

        // Scrolling down to page 2 should read from Room cache with 0 additional Wallhaven calls
        val page2Result = repo.getWallpapers(
            query = WallpaperRepository.HOME_TRENDING_KEY,
            isLive = false,
            filter = WallpaperFilter(),
            page = 2,
        )
        assertTrue(page2Result.isFromCache)
        assertEquals(3, page2Result.wallpapers.size)
        assertEquals(callsAfterPrefetch, wallhavenCalls.get())
    }

    @Test
    fun `pruneExpiredCache deletes entries older than 7-day retention while keeping offline fallback entries`() = runTest {
        val dao = FakeWallpaperDao()
        val now = 10_000_000_000L
        val retentionMillis = CacheExpiryPolicy.MAX_RETENTION_MILLIS
        val expiredTimestamp = now - retentionMillis - 60_000L
        val staleButRetainedTimestamp = now - TimeUnit.HOURS.toMillis(16) // Expired for refresh, kept for Offline Mode

        dao.replaceCacheForKey(
            cacheKey = "expired_key",
            items = listOf(
                CachedWallpaperEntity(
                    cacheKey = "expired_key",
                    wallpaperId = "old_1",
                    positionIndex = 0,
                    title = "Old",
                    animeSeries = "Old",
                    imageUrl = "https://w.wallhaven.cc/full/old.jpg",
                    thumbnailUrl = "https://th.wallhaven.cc/orig/old.jpg",
                    videoUrl = null,
                    isLive = false,
                    width = 1920,
                    height = 1080,
                    source = "Wallhaven",
                    category = "old_relevance",
                    cachedAt = expiredTimestamp,
                ),
            ),
            timestampMillis = expiredTimestamp,
            category = "old_relevance",
            expiresAtMillis = expiredTimestamp + CacheExpiryPolicy.DEFAULT_FEED_TTL_MILLIS,
        )
        dao.replaceCacheForKey(
            cacheKey = "offline_fallback_key",
            items = listOf(
                CachedWallpaperEntity(
                    cacheKey = "offline_fallback_key",
                    wallpaperId = "offline_1",
                    positionIndex = 0,
                    title = "Offline Safe",
                    animeSeries = "Frieren",
                    imageUrl = "https://w.wallhaven.cc/full/new.jpg",
                    thumbnailUrl = "https://th.wallhaven.cc/orig/new.jpg",
                    videoUrl = null,
                    isLive = false,
                    width = 1920,
                    height = 1080,
                    source = "Wallhaven",
                    category = "frieren_toplist",
                    cachedAt = staleButRetainedTimestamp,
                ),
            ),
            timestampMillis = staleButRetainedTimestamp,
            category = "frieren_toplist",
            expiresAtMillis = staleButRetainedTimestamp + CacheExpiryPolicy.TOPLIST_FEED_TTL_MILLIS,
        )

        val unusedWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> = Response.success(WallhavenResponse())
        }
        val unusedReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(RedditResponse())
        }

        val repo = WallpaperRepository(unusedWallhaven, unusedReddit, dao, clock = { now })
        repo.pruneExpiredCache(nowMillis = now)

        assertTrue(dao.getCachedWallpapers("expired_key").isEmpty())
        assertNull(dao.getCacheTimestamp("expired_key"))
        assertEquals(1, dao.getCachedWallpapers("offline_fallback_key").size)
        assertEquals(staleButRetainedTimestamp, dao.getCacheTimestamp("offline_fallback_key"))
    }

    @Test
    fun `cache expiry policy enforces 12h default and toplist TTL with offline fallback`() = runTest {
        var currentTimeMillis = 1_000_000_000L
        val wallhavenCalls = AtomicInteger(0)
        var failNetwork = false

        val fakeWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                val callNum = wallhavenCalls.incrementAndGet()
                if (failNetwork) throw IOException("Device offline")
                return Response.success(
                    WallhavenResponse(
                        data = listOf(
                            WallhavenItem(
                                id = "item_$callNum",
                                path = "https://w.wallhaven.cc/full/wh_$callNum.png",
                                dimensionX = 2160,
                                dimensionY = 3840,
                                thumbs = WallhavenThumbs(original = "https://th.wallhaven.cc/orig/wh_$callNum.jpg"),
                            ),
                        ),
                    ),
                )
            }
        }
        val emptyReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(RedditResponse())
        }

        val dao = FakeWallpaperDao()
        val repo = WallpaperRepository(
            wallhavenApi = fakeWallhaven,
            redditApi = emptyReddit,
            wallpaperDao = dao,
            clock = { currentTimeMillis },
        )

        val latestFilter = WallpaperFilter(sort = SortOption.RECENT)
        val toplistFilter = WallpaperFilter(sort = SortOption.TOPLIST)

        // 1. Initial fetch writes to Room (1 Wallhaven call for latest, 1 for toplist)
        val latestFirst = repo.refreshWallpapersIfNeeded("Naruto", isLive = false, filter = latestFilter)
        assertFalse(latestFirst.isFromCache)
        assertEquals(1, wallhavenCalls.get())
        assertEquals(1, repo.observeCachedWallpapers("Naruto", isLive = false, filter = latestFilter).first().size)

        val toplistFirst = repo.refreshWallpapersIfNeeded("Naruto", isLive = false, filter = toplistFilter)
        assertFalse(toplistFirst.isFromCache)
        assertEquals(2, wallhavenCalls.get())

        // 2. Advance 11h 59m: both Latest (12h TTL) and Toplist (12h TTL) must serve from Room with 0 API calls
        currentTimeMillis += TimeUnit.HOURS.toMillis(11) + TimeUnit.MINUTES.toMillis(59)
        assertTrue(repo.isFeedCacheFresh("Naruto", isLive = false, filter = latestFilter))
        assertTrue(repo.isFeedCacheFresh("Naruto", isLive = false, filter = toplistFilter))
        assertTrue(repo.refreshWallpapersIfNeeded("Naruto", isLive = false, filter = latestFilter).isFromCache)
        assertTrue(repo.refreshWallpapersIfNeeded("Naruto", isLive = false, filter = toplistFilter).isFromCache)
        assertEquals(2, wallhavenCalls.get())

        // 3. Advance past 12h (at 12h 01m total): Latest expires & fetches fresh data
        currentTimeMillis += TimeUnit.MINUTES.toMillis(2)
        assertFalse(repo.isFeedCacheFresh("Naruto", isLive = false, filter = latestFilter))
        assertFalse(repo.isFeedCacheFresh("Naruto", isLive = false, filter = toplistFilter))

        val latestRefreshed = repo.refreshWallpapersIfNeeded("Naruto", isLive = false, filter = latestFilter)
        assertFalse(latestRefreshed.isFromCache)
        assertEquals(3, wallhavenCalls.get())
        assertEquals("wh_item_3", latestRefreshed.wallpapers.first().id)

        // 4. While offline past 12h: expired Toplist refresh fails network -> preserves & returns Room cache
        failNetwork = true
        val offlineResult = repo.refreshWallpapersIfNeeded("Naruto", isLive = false, filter = toplistFilter)
        assertTrue(offlineResult.isFromCache)
        assertEquals(1, offlineResult.wallpapers.size)
        assertEquals("wh_item_2", offlineResult.wallpapers.first().id)
        assertEquals(1, repo.getCachedWallpapersSnapshot("Naruto", isLive = false, filter = toplistFilter).size)
    }

    @Test
    fun `live R2 pagination page 2 beyond 14 items returns clean end-of-list without error banner`() = runTest {
        val sampleItems = (1..14).map { idx ->
            val id = "kuro_${idx.toString().padStart(4, '0')}"
            LiveWallpaperItem(
                id = id,
                title = "Anime Live $idx",
                category = "Frieren",
                thumbUrl = "https://pub-demo.r2.dev/$id/thumb.jpg",
                previewUrl = "https://pub-demo.r2.dev/$id/preview.mp4",
                fullUrl = "https://pub-demo.r2.dev/$id/full.mp4",
            )
        }
        val fakeLiveApi = object : LiveCatalogApi {
            override suspend fun getLiveCatalog(): LiveCatalogResponse {
                return LiveCatalogResponse(version = 1, total = sampleItems.size, wallpapers = sampleItems)
            }

            override suspend fun getLiveCatalogByUrl(url: String): LiveCatalogResponse = getLiveCatalog()
            override suspend fun fetchStaticCatalogCdn() = Response.success(com.example.data.remote.StaticCatalogResponse())
            override suspend fun fetchStaticCatalogRaw() = Response.success(com.example.data.remote.StaticCatalogResponse())
        }
        val liveRepo = LiveWallpaperRepository(
            api = fakeLiveApi,
            downloader = LiveWallpaperDownloader(
                storageRootDir = File(System.getProperty("java.io.tmpdir"), "live_page2_test"),
                downloadClient = OkHttpClient(),
            ),
        )
        val emptyWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> = Response.success(WallhavenResponse(emptyList()))
        }
        val emptyReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(RedditResponse())
        }
        val repo = WallpaperRepository(
            wallhavenApi = emptyWallhaven,
            redditApi = emptyReddit,
            wallpaperDao = FakeWallpaperDao(),
            liveWallpaperRepository = liveRepo,
        )

        val page1 = repo.getWallpapers(
            query = WallpaperRepository.HOME_TRENDING_KEY,
            isLive = true,
            filter = WallpaperFilter(),
            page = 1,
        )
        assertEquals(14, page1.wallpapers.size)
        assertFalse(page1.hasMore)
        assertNull(page1.warningMessage)

        val page2 = repo.getWallpapers(
            query = WallpaperRepository.HOME_TRENDING_KEY,
            isLive = true,
            filter = WallpaperFilter(),
            page = 2,
        )
        assertTrue(page2.wallpapers.isEmpty())
        assertFalse(page2.hasMore)
        assertNull(page2.warningMessage)
    }

    @Test
    fun `pull-to-refresh rotates static artworks and enforces 45 req per minute guard under rapid refreshes`() = runTest {
        var nowMs = 5_000_000L
        val wallhavenCalls = AtomicInteger(0)

        val fakeWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                val callNum = wallhavenCalls.incrementAndGet()
                val safeKey = (query ?: "top").replace(Regex("[^a-zA-Z0-9]"), "")
                val items = (1..8).map { idx ->
                    WallhavenItem(
                        id = "${safeKey}_p${page}_c${callNum}_$idx",
                        path = "https://w.wallhaven.cc/full/01/${safeKey}_p${page}_$idx.jpg",
                        dimensionX = 2160,
                        dimensionY = 3840,
                        thumbs = WallhavenThumbs(
                            large = "https://th.wallhaven.cc/lg/01/${safeKey}_p${page}_$idx.jpg",
                            original = "https://th.wallhaven.cc/orig/01/${safeKey}_p${page}_$idx.jpg",
                        ),
                    )
                }
                return Response.success(WallhavenResponse(data = items))
            }
        }
        val emptyReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(RedditResponse())
        }

        val limiter = com.example.data.remote.WallhavenRateLimiter(
            minSpacingMillis = 0L,
            clock = { nowMs },
        )
        val repo = WallpaperRepository(
            wallhavenApi = fakeWallhaven,
            redditApi = emptyReddit,
            wallpaperDao = FakeWallpaperDao(),
            wallhavenRateLimiter = limiter,
            clock = { nowMs },
        )

        // 1. Initial Home Trending load (3 calls)
        val initial = repo.getWallpapers(
            query = WallpaperRepository.HOME_TRENDING_KEY,
            isLive = false,
            filter = WallpaperFilter(),
            page = 1,
            forceRefresh = false,
        )
        assertEquals(3, wallhavenCalls.get())
        val initialIds = initial.wallpapers.map { it.id }
        assertTrue(initialIds.isNotEmpty())

        // 2. First pull-to-refresh rotates to a fresh trending batch with at most 2 Wallhaven calls
        nowMs += 2_000L
        val firstRefresh = repo.getWallpapers(
            query = WallpaperRepository.HOME_TRENDING_KEY,
            isLive = false,
            filter = WallpaperFilter(),
            page = 1,
            forceRefresh = true,
        )
        val callsAfterFirstRefresh = wallhavenCalls.get()
        assertEquals(5, callsAfterFirstRefresh)
        assertTrue(firstRefresh.wallpapers.map { it.id } != initialIds)

        // 3. Rapid spam pull-to-refresh 10 times within 10 seconds -> 0 extra Wallhaven calls, fresh shuffled cache!
        var lastIds = firstRefresh.wallpapers.map { it.id }
        var orderChangedCount = 0
        repeat(10) {
            nowMs += 1_000L
            val rapidRefresh = repo.getWallpapers(
                query = WallpaperRepository.HOME_TRENDING_KEY,
                isLive = false,
                filter = WallpaperFilter(),
                page = 1,
                forceRefresh = true,
            )
            assertTrue(rapidRefresh.isFromCache)
            assertTrue(rapidRefresh.wallpapers.isNotEmpty())
            val nextIds = rapidRefresh.wallpapers.map { it.id }
            if (nextIds != lastIds) orderChangedCount++
            lastIds = nextIds
        }
        assertEquals(callsAfterFirstRefresh, wallhavenCalls.get())
        assertTrue("Expected rapid refreshes to shuffle visible artworks", orderChangedCount >= 8)

        // 4. Even across a full 60-second window with repeated refreshes every 16 seconds,
        //    total Wallhaven calls remain strictly capped well below 45 req/min!
        repeat(4) {
            nowMs += 16_000L
            val periodicRefresh = repo.getWallpapers(
                query = WallpaperRepository.HOME_TRENDING_KEY,
                isLive = false,
                filter = WallpaperFilter(),
                page = 1,
                forceRefresh = true,
            )
            assertTrue(periodicRefresh.wallpapers.isNotEmpty())
        }
        assertTrue(
            "Total Wallhaven calls (${wallhavenCalls.get()}) must stay well below 45 req/min",
            wallhavenCalls.get() <= 13,
        )
    }

    @Test
    fun `wallhaven 200 with zero matches plus reddit 403 does not trigger false server unreachable error`() = runTest {
        val capturedAtleastList = mutableListOf<String?>()
        val capturedRatiosList = mutableListOf<String?>()
        val emptyWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                capturedAtleastList.add(atleast)
                capturedRatiosList.add(ratios)
                return Response.success(WallhavenResponse(data = emptyList()))
            }
        }
        val forbiddenReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.error(403, "Forbidden".toResponseBody())
        }

        val repo = WallpaperRepository(
            wallhavenApi = emptyWallhaven,
            redditApi = forbiddenReddit,
            wallpaperDao = FakeWallpaperDao(),
            wallhavenRateLimiter = com.example.data.remote.WallhavenRateLimiter(minSpacingMillis = 0L),
        )

        val resultAllRatio2K = repo.getWallpapers(
            query = "ObscureAnimeQuery",
            isLive = false,
            filter = WallpaperFilter(resolution = ResolutionTier.QHD_2K, ratio = RatioFilter.ALL),
        )
        assertEquals(listOf("1440x1440"), capturedAtleastList)
        assertEquals(listOf<String?>(null), capturedRatiosList)
        assertTrue(resultAllRatio2K.wallpapers.isEmpty())
        assertNull(
            "Expected clean empty result without false 'Unable to reach wallpaper servers' banner",
            resultAllRatio2K.warningMessage,
        )

        capturedAtleastList.clear()
        capturedRatiosList.clear()

        repo.getWallpapers(
            query = "ObscureAnimeQuery2",
            isLive = false,
            filter = WallpaperFilter(resolution = ResolutionTier.UHD_4K, ratio = RatioFilter.PORTRAIT_MOBILE),
        )
        assertEquals("2160x3840", capturedAtleastList.first())
        assertEquals(
            listOf(RatioFilter.MOBILE_PORTRAIT_RATIOS, RatioFilter.FALLBACK_PORTRAIT_RATIO),
            capturedRatiosList,
        )
    }

    @Test
    fun `mobile optimization sends portrait ratios, filters oversized raw files, and caps static pages to 18`() = runTest {
        var capturedRatios: String? = null
        val oversizedBytes = 12L * 1024L * 1024L
        val normalBytes = 1_600_000L

        val fakeWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                capturedRatios = ratios
                val oversizedDesktop8K = WallhavenItem(
                    id = "heavy8k",
                    path = "https://w.wallhaven.cc/full/01/heavy8k.png",
                    dimensionX = 7680,
                    dimensionY = 4320,
                    fileSize = oversizedBytes,
                    thumbs = WallhavenThumbs(original = "https://th.wallhaven.cc/orig/01/heavy8k.jpg"),
                )
                val mobilePortraitItems = (1..22).map { idx ->
                    WallhavenItem(
                        id = "mob_$idx",
                        path = "https://w.wallhaven.cc/full/01/mob_$idx.jpg",
                        dimensionX = 1080,
                        dimensionY = 2400,
                        fileSize = normalBytes,
                        thumbs = WallhavenThumbs(
                            large = "https://th.wallhaven.cc/lg/01/mob_$idx.jpg",
                            original = "https://th.wallhaven.cc/orig/01/mob_$idx.jpg",
                        ),
                    )
                }
                return Response.success(
                    WallhavenResponse(data = listOf(oversizedDesktop8K) + mobilePortraitItems),
                )
            }
        }
        val emptyReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(RedditResponse())
        }

        val repo = WallpaperRepository(
            wallhavenApi = fakeWallhaven,
            redditApi = emptyReddit,
            wallpaperDao = FakeWallpaperDao(),
            wallhavenRateLimiter = com.example.data.remote.WallhavenRateLimiter(minSpacingMillis = 0L),
        )

        val result = repo.getWallpapers(
            query = "Solo Leveling",
            isLive = false,
            filter = WallpaperFilter(),
            page = 1,
        )

        assertEquals("9x16,10x16,9x18,18x39,9x20,9x21", capturedRatios)
        assertEquals(WallpaperRepository.STATIC_PAGE_SIZE, result.wallpapers.size)
        assertTrue(
            "Oversized 8K raw file (>8MB) must be filtered out when browsing default mobile feed",
            result.wallpapers.none { it.id == "wh_heavy8k" },
        )
        assertTrue(
            "Grid thumbnails must stay lightweight Wallhaven thumb URLs",
            result.wallpapers.all { it.thumbnailUrl.startsWith("https://th.wallhaven.cc/") },
        )
    }

    @Test
    fun `bitmap cropper calculateInSampleSize downsamples 4K and 8K sources safely for mobile screens`() {
        // 8K vertical artwork (4320x7680) targeting 1080x2400 phone display -> power-of-2 sample size 2
        val sample8K = com.example.util.BitmapCropper.calculateInSampleSize(
            srcWidth = 4320,
            srcHeight = 7680,
            reqWidth = 1080,
            reqHeight = 2400,
        )
        assertEquals(2, sample8K)

        // Exact FHD+ source (1080x2400) targeting 1080x2400 -> no downsampling needed (1)
        val sampleExact = com.example.util.BitmapCropper.calculateInSampleSize(
            srcWidth = 1080,
            srcHeight = 2400,
            reqWidth = 1080,
            reqHeight = 2400,
        )
        assertEquals(1, sampleExact)

        // Ultra-massive 16K image -> sample size >= 4
        val sample16K = com.example.util.BitmapCropper.calculateInSampleSize(
            srcWidth = 8640,
            srcHeight = 15360,
            reqWidth = 1080,
            reqHeight = 2400,
        )
        assertTrue("Expected inSampleSize >= 4 for 16K image, got $sample16K", sample16K >= 4)
    }

    @Test
    fun `home trending prefers uncropped wallhaven thumbs original and suppresses partial failure banner`() = runTest {
        val fakeWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                if (query?.contains("JoJo", ignoreCase = true) == true) {
                    return Response.error(503, "Temporary topic failure".toResponseBody())
                }
                val safeId = (query ?: "top").replace(Regex("[^a-zA-Z0-9]"), "")
                return Response.success(
                    WallhavenResponse(
                        data = listOf(
                            WallhavenItem(
                                id = safeId,
                                path = "https://w.wallhaven.cc/full/01/$safeId.jpg",
                                dimensionX = 2160,
                                dimensionY = 3840,
                                thumbs = WallhavenThumbs(
                                    large = "https://th.wallhaven.cc/lg/01/$safeId.jpg",
                                    original = "https://th.wallhaven.cc/orig/01/$safeId.jpg",
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
        val forbiddenReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.error(403, "Forbidden".toResponseBody())
        }

        val repo = WallpaperRepository(
            wallhavenApi = fakeWallhaven,
            redditApi = forbiddenReddit,
            wallpaperDao = FakeWallpaperDao(),
            wallhavenRateLimiter = com.example.data.remote.WallhavenRateLimiter(minSpacingMillis = 0L),
        )

        val result = repo.getWallpapers(
            query = WallpaperRepository.HOME_TRENDING_KEY,
            isLive = false,
            filter = WallpaperFilter(),
            page = 1,
        )
        assertEquals(2, result.wallpapers.size)
        assertNull(
            "Partial topic failure must not display an error banner when other topics populated the feed",
            result.warningMessage,
        )
        assertTrue(
            "Wallhaven thumbnail must prefer uncropped thumbs.original over 3:2 cropped thumbs.large",
            result.wallpapers.all { it.thumbnailUrl.contains("/orig/") },
        )
    }

    @Test
    fun `static filter combines max 1 category and max 1 tag and sends combined API parameters`() = runTest {
        val initial = com.example.domain.model.StaticFilterState()
        assertTrue(initial.isHome)
        assertFalse(initial.isChipFilterActive)

        val animeOnly = initial.toggleCategory("Anime")
        assertEquals("Anime", animeOnly.selectedCategory)
        assertNull(animeOnly.selectedTag)
        assertTrue(animeOnly.isChipFilterActive)
        assertFalse(animeOnly.isSearchQueryOnly)

        // Combining Category + Tag keeps Anime and adds #Kawaii
        val animeKawaii = animeOnly.toggleTag("Kawaii")
        assertEquals("Anime", animeKawaii.selectedCategory)
        assertEquals("Kawaii", animeKawaii.selectedTag)
        assertTrue(animeKawaii.isChipFilterActive)
        assertEquals("Anime · #Kawaii", animeKawaii.summaryLabel())

        // Tapping another Tag replaces Kawaii while keeping Anime (strictly max 1 Tag)
        val animeCyberpunk = animeKawaii.toggleTag("Cyberpunk")
        assertEquals("Anime", animeCyberpunk.selectedCategory)
        assertEquals("Cyberpunk", animeCyberpunk.selectedTag)

        // Switching Category to Cars clears Anime-specific tag
        val carsOnly = animeCyberpunk.toggleCategory("Cars")
        assertEquals("Cars", carsOnly.selectedCategory)
        assertNull(carsOnly.selectedTag)

        // Verify Wallhaven API receives both category bitmask and combined tag query
        val capturedCalls = mutableListOf<Pair<String, String?>>()
        val fakeWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                capturedCalls.add(categories to query)
                return Response.success(
                    WallhavenResponse(
                        data = listOf(
                            WallhavenItem(
                                id = "comb_${capturedCalls.size}",
                                path = "https://w.wallhaven.cc/full/01/comb_${capturedCalls.size}.jpg",
                                dimensionX = 1080,
                                dimensionY = 2400,
                                thumbs = WallhavenThumbs(original = "https://th.wallhaven.cc/orig/01/comb_${capturedCalls.size}.jpg"),
                            ),
                        ),
                    ),
                )
            }
        }
        val emptyReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(RedditResponse())
        }
        val repo = WallpaperRepository(
            wallhavenApi = fakeWallhaven,
            redditApi = emptyReddit,
            wallpaperDao = FakeWallpaperDao(),
            wallhavenRateLimiter = com.example.data.remote.WallhavenRateLimiter(minSpacingMillis = 0L),
        )

        repo.getWallpapers(
            query = animeKawaii.toQueryKey(),
            isLive = false,
            filter = WallpaperFilter(),
            staticFilter = animeKawaii,
        )
        val carsJdm = carsOnly.toggleTag("JDM")
        repo.getWallpapers(
            query = carsJdm.toQueryKey(),
            isLive = false,
            filter = WallpaperFilter(),
            staticFilter = carsJdm,
        )

        assertEquals("010" to "Kawaii", capturedCalls[0])
        assertEquals("100" to "car JDM", capturedCalls[1])
    }

    @Test
    fun `probeExploreCategoryStaticCovers detects confirmed empty categories and contextual static tags vary by category`() = runTest {
        val fakeWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                // Simulate "Cute" returning 0 items (HTTP 200) while "Anime" returns items
                if (query?.contains("cute", ignoreCase = true) == true) {
                    return Response.success(WallhavenResponse(data = emptyList()))
                }
                return Response.success(
                    WallhavenResponse(
                        data = listOf(
                            WallhavenItem(
                                id = "cat_ok",
                                path = "https://w.wallhaven.cc/full/01/cat_ok.jpg",
                                dimensionX = 1080,
                                dimensionY = 2400,
                                thumbs = WallhavenThumbs(original = "https://th.wallhaven.cc/orig/01/cat_ok.jpg"),
                            ),
                        ),
                    ),
                )
            }
        }
        val emptyReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(RedditResponse())
        }
        val repo = WallpaperRepository(
            wallhavenApi = fakeWallhaven,
            redditApi = emptyReddit,
            wallpaperDao = FakeWallpaperDao(),
            wallhavenRateLimiter = com.example.data.remote.WallhavenRateLimiter(minSpacingMillis = 0L),
        )

        val cuteTopic = com.example.domain.model.AnimeCatalog.exploreCategories.first { it.title == "Cute" }
        val animeTopic = com.example.domain.model.AnimeCatalog.exploreCategories.first { it.title == "Anime" }

        val cuteProbe = repo.probeExploreCategoryStaticCovers(cuteTopic)
        assertTrue("Empty category from HTTP 200 API response must be flagged confirmedEmpty", cuteProbe.confirmedEmpty)
        assertTrue(cuteProbe.coverUrls.isEmpty())

        val animeProbe = repo.probeExploreCategoryStaticCovers(animeTopic)
        assertFalse(animeProbe.confirmedEmpty)
        assertEquals(1, animeProbe.coverUrls.size)
        // Cached lookup immediately hits the probed category covers without extra API calls
        assertEquals(animeProbe.coverUrls, repo.getCachedExploreCategoryStaticCovers(animeTopic))

        // Dynamic contextual tag suggestions per Category
        val animeTags = com.example.ui.components.getRotatedStaticTags(selectedCategory = "Anime").map { it.query }.toSet()
        val gamesTags = com.example.ui.components.getRotatedStaticTags(selectedCategory = "Games").map { it.query }.toSet()
        val carsTags = com.example.ui.components.getRotatedStaticTags(selectedCategory = "Cars").map { it.query }.toSet()
        val natureTags = com.example.ui.components.getRotatedStaticTags(selectedCategory = "Nature").map { it.query }.toSet()

        assertTrue(animeTags.contains("Kawaii"))
        assertTrue(gamesTags.contains("RPG"))
        assertTrue(gamesTags.contains("Knight"))
        assertTrue(carsTags.contains("JDM"))
        assertTrue(natureTags.contains("Sunset"))
        assertFalse(carsTags.contains("Kawaii"))
        assertFalse(natureTags.contains("JDM"))
    }

    @Test
    fun `probeStaticTagAvailability filters out confirmed empty hashtags like Aesthetic and caches verified tags`() = runTest {
        val apiCalls = AtomicInteger(0)
        val fakeWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                apiCalls.incrementAndGet()
                // Simulate #Aesthetic returning 0 items (HTTP 200) while #Cyberpunk returns 42 matches
                if (query.equals("Aesthetic", ignoreCase = true)) {
                    return Response.success(
                        WallhavenResponse(
                            data = emptyList(),
                            meta = com.example.data.remote.WallhavenMeta(total = 0),
                        ),
                    )
                }
                return Response.success(
                    WallhavenResponse(
                        data = listOf(
                            WallhavenItem(
                                id = "cyber_1",
                                path = "https://w.wallhaven.cc/full/01/cyber_1.jpg",
                                dimensionX = 1080,
                                dimensionY = 2400,
                                thumbs = WallhavenThumbs(original = "https://th.wallhaven.cc/orig/01/cyber_1.jpg"),
                            ),
                        ),
                        meta = com.example.data.remote.WallhavenMeta(total = 42),
                    ),
                )
            }
        }
        val emptyReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(RedditResponse())
        }
        val repo = WallpaperRepository(
            wallhavenApi = fakeWallhaven,
            redditApi = emptyReddit,
            wallpaperDao = FakeWallpaperDao(),
            wallhavenRateLimiter = com.example.data.remote.WallhavenRateLimiter(minSpacingMillis = 0L),
        )

        val aestheticProbe = repo.probeStaticTagAvailability(tag = "Aesthetic", selectedCategory = null)
        assertTrue("Empty tag must be flagged confirmedEmpty", aestheticProbe.confirmedEmpty)
        assertEquals(0, aestheticProbe.matchCount)

        val cyberpunkProbe = repo.probeStaticTagAvailability(tag = "Cyberpunk", selectedCategory = null)
        assertFalse(cyberpunkProbe.confirmedEmpty)
        assertEquals(42, cyberpunkProbe.matchCount)
        val callsAfterProbe = apiCalls.get()

        // Subsequent cache lookup & probe hit Room with 0 extra network calls
        assertEquals(42, repo.getCachedStaticTagCount(tag = "Cyberpunk", selectedCategory = null))
        val cachedProbe = repo.probeStaticTagAvailability(tag = "Cyberpunk", selectedCategory = null)
        assertEquals(42, cachedProbe.matchCount)
        assertEquals(callsAfterProbe, apiCalls.get())

        // Rotating verified tags only cycles through verified chips
        val verified = listOf(
            com.example.domain.model.LiveTagChip("#Cyberpunk", "Cyberpunk", count = 42),
            com.example.domain.model.LiveTagChip("#Dark", "Dark", count = 18),
            com.example.domain.model.LiveTagChip("#Samurai", "Samurai", count = 25),
            com.example.domain.model.LiveTagChip("#Sakura", "Sakura", count = 31),
        )
        val rotated0 = com.example.ui.components.rotateVerifiedTagChips(verified, rotationSeed = 0)
        val rotated1 = com.example.ui.components.rotateVerifiedTagChips(verified, rotationSeed = 1)
        assertEquals(listOf("Cyberpunk", "Dark", "Samurai", "Sakura"), rotated0.map { it.query })
        assertEquals(listOf("Sakura", "Cyberpunk", "Dark", "Samurai"), rotated1.map { it.query })
    }

    @Test
    fun `WallhavenRateLimiter blocks BACKGROUND_PREFETCH when remaining quota is at or below 15 while allowing USER_INTERACTIVE`() = kotlinx.coroutines.runBlocking {
        var nowMs = 100_000L
        val limiter = com.example.data.remote.WallhavenRateLimiter(
            maxRequestsPerWindow = 43,
            minSpacingMillis = 0L,
            clock = { nowMs },
        )

        // Default feed TTL is 12 hours to maximize Room cache hit rate
        assertEquals(
            java.util.concurrent.TimeUnit.HOURS.toMillis(12),
            CacheExpiryPolicy.DEFAULT_FEED_TTL_MILLIS,
        )

        // Consume 27 requests -> remaining = 16 (> 15) -> BACKGROUND_PREFETCH still allowed
        repeat(27) {
            val result = limiter.tryAcquire(com.example.data.remote.WallhavenRateLimiter.RequestPriority.USER_INTERACTIVE)
            assertTrue(result is com.example.data.remote.WallhavenRateLimiter.AcquireResult.Allowed)
        }
        assertEquals(16, limiter.remainingQuota())
        val bgAllowed = limiter.tryAcquire(com.example.data.remote.WallhavenRateLimiter.RequestPriority.BACKGROUND_PREFETCH)
        assertTrue(bgAllowed is com.example.data.remote.WallhavenRateLimiter.AcquireResult.Allowed)

        // Now remaining = 15 (<= 15) -> BACKGROUND_PREFETCH must be blocked, USER_INTERACTIVE still allowed
        assertEquals(15, limiter.remainingQuota())
        val bgBlocked = limiter.tryAcquire(com.example.data.remote.WallhavenRateLimiter.RequestPriority.BACKGROUND_PREFETCH)
        assertTrue(bgBlocked is com.example.data.remote.WallhavenRateLimiter.AcquireResult.RateLimited)
        assertEquals(5_000L, (bgBlocked as com.example.data.remote.WallhavenRateLimiter.AcquireResult.RateLimited).retryAfterMs)
        assertFalse(bgBlocked.fromServer429)

        val userAllowed = limiter.tryAcquire(com.example.data.remote.WallhavenRateLimiter.RequestPriority.USER_INTERACTIVE)
        assertTrue(userAllowed is com.example.data.remote.WallhavenRateLimiter.AcquireResult.Allowed)
        assertEquals(14, limiter.remainingQuota())
    }

    @Test
    fun `AI 4K Artworks is removed and Home Screen prioritizes GitHub Raw R2 JSON with Disk cache and Wallhaven expansion`() = runTest {
        assertTrue(
            com.example.domain.model.AnimeCatalog.exploreCategories.none {
                it.title.contains("AI 4K", ignoreCase = true)
            },
        )

        val wallhavenCalls = AtomicInteger(0)
        val rawCalls = AtomicInteger(0)
        val cdnCalls = AtomicInteger(0)
        val sampleDtos = (1..30).map { idx ->
            com.example.data.remote.StaticCatalogItemDto(
                id = "r2_static_$idx",
                imageUrl = "https://pub-demo.r2.dev/static/r2_static_$idx.jpg",
                thumbnailUrl = "https://pub-demo.r2.dev/static/thumbs/r2_static_$idx.jpg",
                width = 1080,
                height = 1920,
            )
        }
        val fakeWallhaven = object : WallhavenService {
            override suspend fun searchAnime(
                query: String?,
                categories: String,
                purity: String,
                ratios: String?,
                atleast: String?,
                sorting: String,
                order: String?,
                topRange: String?,
                page: Int,
            ): Response<WallhavenResponse> {
                val n = wallhavenCalls.incrementAndGet()
                return Response.success(
                    WallhavenResponse(
                        data = listOf(
                            WallhavenItem(
                                id = "wh_exp_$n",
                                path = "https://w.wallhaven.cc/full/wh_exp_$n.jpg",
                                dimensionX = 1080,
                                dimensionY = 1920,
                                thumbs = WallhavenThumbs(
                                    large = "https://th.wallhaven.cc/lg/wh_exp_$n.jpg",
                                    original = "https://th.wallhaven.cc/orig/wh_exp_$n.jpg",
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
        val emptyReddit = object : RedditService {
            override suspend fun searchSubreddit(
                subreddit: String,
                query: String,
                restrictSr: String,
                sort: String,
                time: String,
                limit: Int,
            ): Response<RedditResponse> = Response.success(RedditResponse())
        }
        val fakeCatalogApi = object : LiveCatalogApi {
            override suspend fun getLiveCatalog(): LiveCatalogResponse = LiveCatalogResponse()
            override suspend fun getLiveCatalogByUrl(url: String): LiveCatalogResponse = LiveCatalogResponse()
            override suspend fun fetchStaticCatalogCdn(): Response<com.example.data.remote.StaticCatalogResponse> {
                cdnCalls.incrementAndGet()
                return Response.success(
                    com.example.data.remote.StaticCatalogResponse(
                        version = 1,
                        total = sampleDtos.size,
                        items = sampleDtos,
                    ),
                )
            }
            override suspend fun fetchStaticCatalogRaw(): Response<com.example.data.remote.StaticCatalogResponse> {
                rawCalls.incrementAndGet()
                return Response.success(com.example.data.remote.StaticCatalogResponse())
            }
        }

        val tempCacheFile = java.io.File.createTempFile("static_catalog_test_", ".json")
        tempCacheFile.delete()
        try {
            val staticRepo = com.example.data.repository.StaticCatalogRepository(
                api = fakeCatalogApi,
                catalogCacheFile = tempCacheFile,
            )
            val repo = WallpaperRepository(
                wallhavenApi = fakeWallhaven,
                redditApi = emptyReddit,
                wallpaperDao = FakeWallpaperDao(),
                staticCatalogRepository = staticRepo,
            )

            // Page 1 & 2 come directly from jsDelivr CDN R2 JSON with 0 GitHub Raw or Wallhaven calls
            val page1 = repo.getWallpapers(
                query = WallpaperRepository.HOME_TRENDING_KEY,
                isLive = false,
                filter = WallpaperFilter(),
                page = 1,
                forceRefresh = false,
            )
            assertEquals(24, page1.wallpapers.size)
            assertTrue(page1.hasMore)
            assertEquals("r2_static_1", page1.wallpapers.first().id)
            assertEquals("R2", page1.wallpapers.first().source)
            assertEquals(1, cdnCalls.get())
            assertEquals(0, rawCalls.get())
            assertEquals(0, wallhavenCalls.get())
            assertTrue(tempCacheFile.exists() && tempCacheFile.length() > 0L)

            val page2 = repo.getWallpapers(
                query = WallpaperRepository.HOME_TRENDING_KEY,
                isLive = false,
                filter = WallpaperFilter(),
                page = 2,
                forceRefresh = false,
            )
            assertEquals(6, page2.wallpapers.size)
            assertEquals("r2_static_25", page2.wallpapers.first().id)
            assertEquals(1, cdnCalls.get())
            assertEquals(0, wallhavenCalls.get())

            // Page 3 expands smoothly into Wallhaven after all 30 R2 items are shown
            val page3 = repo.getWallpapers(
                query = WallpaperRepository.HOME_TRENDING_KEY,
                isLive = false,
                filter = WallpaperFilter(),
                page = 3,
                forceRefresh = false,
            )
            assertTrue(page3.wallpapers.isNotEmpty())
            assertTrue(wallhavenCalls.get() > 0)

            // Cold start with offline API hydrates instantaneously from LocalCache (0 network calls)
            val offlineApi = object : LiveCatalogApi {
                override suspend fun getLiveCatalog(): LiveCatalogResponse = throw IOException("Offline")
                override suspend fun getLiveCatalogByUrl(url: String): LiveCatalogResponse = throw IOException("Offline")
                override suspend fun fetchStaticCatalogCdn(): Response<com.example.data.remote.StaticCatalogResponse> =
                    throw IOException("Offline")
                override suspend fun fetchStaticCatalogRaw(): Response<com.example.data.remote.StaticCatalogResponse> =
                    throw IOException("Offline")
            }
            val coldStaticRepo = com.example.data.repository.StaticCatalogRepository(
                api = offlineApi,
                catalogCacheFile = tempCacheFile,
            )
            val coldRepo = WallpaperRepository(
                wallhavenApi = fakeWallhaven,
                redditApi = emptyReddit,
                wallpaperDao = FakeWallpaperDao(),
                staticCatalogRepository = coldStaticRepo,
            )
            val instantSnapshot = coldRepo.getCachedWallpapersSnapshot(
                query = WallpaperRepository.HOME_TRENDING_KEY,
                isLive = false,
                filter = WallpaperFilter(),
                page = 1,
            )
            assertEquals(24, instantSnapshot.size)
            assertEquals("r2_static_1", instantSnapshot.first().id)
            assertTrue(
                coldRepo.isFeedCacheFresh(
                    query = WallpaperRepository.HOME_TRENDING_KEY,
                    isLive = false,
                    filter = WallpaperFilter(),
                    page = 1,
                ),
            )
        } finally {
            tempCacheFile.delete()
        }
    }

    @Test
    fun `StaticCatalogItemDto resolves WebP R2 URLs across alias keys and derives series cleanly`() = runTest {
        val webpUrl = "https://pub-d22f9bea4689411daf3cd7670bb7f7ff.r2.dev/wallpapers/black_clover/009e635cb5537fc28da5d5c5.webp"
        val rawJson = """
            {
              "version": 1,
              "wallpapers": [
                {
                  "id": "bc_01",
                  "url": "  $webpUrl  "
                }
              ]
            }
        """.trimIndent()
        val moshi = com.squareup.moshi.Moshi.Builder()
            .addLast(com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory())
            .build()
        val parsed = moshi.adapter(com.example.data.remote.StaticCatalogResponse::class.java).fromJson(rawJson)
        val items = parsed?.resolvedItems().orEmpty()
        assertEquals(1, items.size)
        assertEquals(webpUrl, items.first().resolvedImageUrl())
        assertEquals(webpUrl, items.first().resolvedThumbnailUrl())

        val fakeApi = object : LiveCatalogApi {
            override suspend fun getLiveCatalog(): LiveCatalogResponse = LiveCatalogResponse()
            override suspend fun getLiveCatalogByUrl(url: String): LiveCatalogResponse = LiveCatalogResponse()
            override suspend fun fetchStaticCatalogCdn() = Response.success(parsed!!)
            override suspend fun fetchStaticCatalogRaw() = Response.success(com.example.data.remote.StaticCatalogResponse())
        }
        val staticRepo = com.example.data.repository.StaticCatalogRepository(api = fakeApi)
        val page = staticRepo.getHomePage(filter = WallpaperFilter(), page = 1)
        assertEquals(1, page.items.size)
        val wp = page.items.first()
        assertEquals(webpUrl, wp.imageUrl)
        assertEquals(webpUrl, wp.thumbnailUrl)
        assertEquals("Black Clover", wp.animeSeries)

        val candidates = com.example.ui.components.resolveCardImageCandidates(wp)
        assertEquals(
            listOf(webpUrl to false, webpUrl to true),
            candidates,
        )
    }

    @Test
    fun `fetchWallpapersJson tries jsDelivr CDN first and fails over to GitHub Raw with cache buster on forceRefresh`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val validJson = """
            {
              "version": 1,
              "total": 1,
              "items": [
                {
                  "id": "cdn_fallback_1",
                  "imageUrl": "https://pub-d22f9bea4689411daf3cd7670bb7f7ff.r2.dev/wallpapers/naruto/img1.webp",
                  "thumbnailUrl": "https://pub-d22f9bea4689411daf3cd7670bb7f7ff.r2.dev/wallpapers/naruto/img1.webp",
                  "width": 2160,
                  "height": 3840
                }
              ]
            }
        """.trimIndent()
        val fakeClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                requestedUrls += url
                if (url.startsWith(com.example.data.repository.StaticCatalogRepository.CDN_URL)) {
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(503)
                        .message("CDN Unavailable")
                        .body("".toResponseBody("text/plain".toMediaType()))
                        .build()
                } else {
                    okhttp3.Response.Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(validJson.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            }
            .build()

        val localCache = com.example.data.local.InMemoryWallpaperLocalCache()
        val fixedTime = 1_732_500_000_000L
        val staticRepo = com.example.data.repository.StaticCatalogRepository(
            localCache = localCache,
            okHttpClient = fakeClient,
            clock = { fixedTime },
        )

        val fetched = staticRepo.fetchWallpapersJson(forceRefresh = true)
        assertEquals(validJson, fetched)
        assertEquals(validJson, localCache.getJson())
        assertEquals(2, requestedUrls.size)
        assertEquals(com.example.data.repository.StaticCatalogRepository.CDN_URL, requestedUrls[0])
        assertEquals(
            "${com.example.data.repository.StaticCatalogRepository.FALLBACK_URL}?t=$fixedTime",
            requestedUrls[1],
        )
        assertEquals(1, staticRepo.catalogUpdates.value.size)
        assertEquals("cdn_fallback_1", staticRepo.catalogUpdates.value.first().id)

        // Verify default fast client timeout is configured to 5 seconds for rapid CDN failover
        val defaultClient = com.example.data.repository.StaticCatalogRepository.defaultFastCatalogClient()
        assertEquals(5_000, defaultClient.connectTimeoutMillis)
        assertEquals(5_000, defaultClient.readTimeoutMillis)
    }

    @Test
    fun `BitmapCropper preserves full 4K native resolution and computes exact screen-aspect visibleCropHint`() {
        // 4K portrait artwork stays 1:1 native (inSampleSize = 1), 8K steps down safely to 2
        assertEquals(1, com.example.util.BitmapCropper.calculateNativeCeilingSampleSize(2160, 3840))
        assertEquals(1, com.example.util.BitmapCropper.calculateNativeCeilingSampleSize(1440, 3200))
        assertEquals(2, com.example.util.BitmapCropper.calculateNativeCeilingSampleSize(7680, 4320))
    }
}
