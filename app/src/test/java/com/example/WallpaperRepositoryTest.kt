package com.example

import com.example.data.local.CacheExpiryPolicy
import com.example.data.local.LiveWallpaperDataSource
import com.example.data.local.dao.WallpaperDao
import com.example.data.local.entity.CachedWallpaperEntity
import com.example.data.local.entity.FavoriteWallpaperEntity
import com.example.data.local.entity.QueryCacheMetaEntity
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
import com.example.data.repository.WallpaperRepository
import com.example.domain.model.ResolutionTier
import com.example.domain.model.SortOption
import com.example.domain.model.WallpaperFilter
import java.io.File
import java.io.IOException
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
    fun `parses 57 live mp4 json items and filters by category and alias`() = runTest {
        val jsonFile = listOf(
            File("src/main/assets/live_wallpapers.json"),
            File("app/src/main/assets/live_wallpapers.json"),
        ).first { it.exists() }
        val dataSource = LiveWallpaperDataSource { jsonFile.readText() }

        val parsed = dataSource.getAllParsedItems()
        assertEquals(57, parsed.size)

        val trendingPage1 = dataSource.getLiveWallpapersPage(
            query = WallpaperRepository.HOME_TRENDING_KEY,
            page = 1,
            pageSize = LiveWallpaperDataSource.DEFAULT_PAGE_SIZE,
        )
        assertEquals(45, trendingPage1.items.size)
        assertTrue(trendingPage1.hasMore)
        assertTrue(trendingPage1.items.all { it.isLive && it.videoUrl?.endsWith(".mp4") == true })
        assertTrue(trendingPage1.items.all { it.thumbnailUrl.endsWith(".jpg") })

        val trendingPage2 = dataSource.getLiveWallpapersPage(
            query = WallpaperRepository.HOME_TRENDING_KEY,
            page = 2,
            pageSize = LiveWallpaperDataSource.DEFAULT_PAGE_SIZE,
        )
        assertEquals(12, trendingPage2.items.size)
        assertFalse(trendingPage2.hasMore)

        val onePiece = dataSource.getLiveWallpapersPage(query = "One Piece", page = 1)
        assertEquals(3, onePiece.items.size)
        assertTrue(onePiece.items.all { it.animeSeries == "One Piece" })

        val demonSlayer = dataSource.getLiveWallpapersPage(query = "Demon Slayer", page = 1)
        assertEquals(2, demonSlayer.items.size)

        val reZero = dataSource.getLiveWallpapersPage(query = "Re:Zero", page = 1)
        assertEquals(1, reZero.items.size)
        assertEquals("Emilia", reZero.items.first().title)

        val totoro = dataSource.getLiveWallpapersPage(query = "Totoro", page = 1)
        assertEquals(1, totoro.items.size)
        assertEquals("My Neighbor Totoro", totoro.items.first().animeSeries)

        val frieren = dataSource.getLiveWallpapersPage(query = "Frieren", page = 1)
        assertEquals(7, frieren.items.size)

        val jjk = dataSource.getLiveWallpapersPage(query = "Jujutsu Kaisen", page = 1)
        assertEquals(8, jjk.items.size)

        val jojo = dataSource.getLiveWallpapersPage(query = "JoJo's Bizarre Adventure", page = 1)
        assertEquals(5, jojo.items.size)

        val oshiNoKo = dataSource.getLiveWallpapersPage(query = "Oshi no Ko", page = 1)
        assertEquals(4, oshiNoKo.items.size)

        val bleach = dataSource.getLiveWallpapersPage(query = "Bleach", page = 1)
        assertEquals(7, bleach.items.size)

        val soloLeveling = dataSource.getLiveWallpapersPage(query = "Solo Leveling", page = 1)
        assertEquals(9, soloLeveling.items.size)

        val apothecary = dataSource.getLiveWallpapersPage(query = "The Apothecary Diaries", page = 1)
        assertEquals(2, apothecary.items.size)
    }

    @Test
    fun `paginates live wallpapers 45 items per page through repository`() = runTest {
        val entries = (1..105).joinToString(prefix = "[", postfix = "]") { idx ->
            """{"ID":"Live_$idx","Image_live_url":"https://res.cloudinary.com/demo/video/upload/v1/sample_$idx.mp4","Category":"Naruto","Description":"Clip $idx"}"""
        }
        val liveSource = LiveWallpaperDataSource { entries }
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

        val repo = WallpaperRepository(unusedWallhaven, unusedReddit, FakeWallpaperDao(), liveSource)

        val page1 = repo.getWallpapers(WallpaperRepository.HOME_TRENDING_KEY, isLive = true, filter = WallpaperFilter(), page = 1)
        val page2 = repo.getWallpapers(WallpaperRepository.HOME_TRENDING_KEY, isLive = true, filter = WallpaperFilter(), page = 2)
        val page3 = repo.getWallpapers(WallpaperRepository.HOME_TRENDING_KEY, isLive = true, filter = WallpaperFilter(), page = 3)

        assertEquals(45, page1.wallpapers.size)
        assertTrue(page1.hasMore)
        assertEquals("mp4_Live_1", page1.wallpapers.first().id)

        assertEquals(45, page2.wallpapers.size)
        assertTrue(page2.hasMore)
        assertEquals("mp4_Live_46", page2.wallpapers.first().id)

        assertEquals(15, page3.wallpapers.size)
        assertFalse(page3.hasMore)
        assertEquals("mp4_Live_91", page3.wallpapers.first().id)
        assertEquals("mp4_Live_105", page3.wallpapers.last().id)
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
            liveWallpaperDataSource = LiveWallpaperDataSource { """[{"ID":"Gojo_1","Image_live_url":"https://res.cloudinary.com/demo/video/upload/v1/gojo.mp4","Category":"Jujutsu Kaisen","Description":"Gojo"}]""" },
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
    fun `cache expiry policy enforces 4h default TTL and 12h toplist TTL with offline fallback`() = runTest {
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

        // 2. Advance 3h 59m: both Latest (4h TTL) and Toplist (12h TTL) must serve from Room with 0 API calls
        currentTimeMillis += TimeUnit.HOURS.toMillis(3) + TimeUnit.MINUTES.toMillis(59)
        assertTrue(repo.isFeedCacheFresh("Naruto", isLive = false, filter = latestFilter))
        assertTrue(repo.refreshWallpapersIfNeeded("Naruto", isLive = false, filter = latestFilter).isFromCache)
        assertTrue(repo.refreshWallpapersIfNeeded("Naruto", isLive = false, filter = toplistFilter).isFromCache)
        assertEquals(2, wallhavenCalls.get())

        // 3. Advance past 4h (at 5h total): Latest expires & fetches fresh data, while Toplist (12h TTL) stays cached
        currentTimeMillis += TimeUnit.HOURS.toMillis(1) + TimeUnit.MINUTES.toMillis(2)
        assertFalse(repo.isFeedCacheFresh("Naruto", isLive = false, filter = latestFilter))
        assertTrue(repo.isFeedCacheFresh("Naruto", isLive = false, filter = toplistFilter))

        val latestRefreshed = repo.refreshWallpapersIfNeeded("Naruto", isLive = false, filter = latestFilter)
        assertFalse(latestRefreshed.isFromCache)
        assertEquals(3, wallhavenCalls.get())
        assertEquals("wh_item_3", latestRefreshed.wallpapers.first().id)

        val toplistStillCached = repo.refreshWallpapersIfNeeded("Naruto", isLive = false, filter = toplistFilter)
        assertTrue(toplistStillCached.isFromCache)
        assertEquals(3, wallhavenCalls.get())

        // 4. Advance past 12h while offline: expired Toplist refresh fails network -> preserves & returns Room cache
        currentTimeMillis += TimeUnit.HOURS.toMillis(8)
        failNetwork = true
        val offlineResult = repo.refreshWallpapersIfNeeded("Naruto", isLive = false, filter = toplistFilter)
        assertTrue(offlineResult.isFromCache)
        assertEquals(1, offlineResult.wallpapers.size)
        assertEquals("wh_item_2", offlineResult.wallpapers.first().id)
        assertEquals(1, repo.getCachedWallpapersSnapshot("Naruto", isLive = false, filter = toplistFilter).size)
    }
}
