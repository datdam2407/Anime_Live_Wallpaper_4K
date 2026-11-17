package com.example

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
import com.example.domain.model.WallpaperFilter
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
        private val cache = mutableMapOf<String, List<CachedWallpaperEntity>>()
        private val meta = mutableMapOf<String, Long>()

        override fun observeFavorites(): Flow<List<FavoriteWallpaperEntity>> = favorites
        override fun observeFavoriteIds(): Flow<List<String>> = favorites.map { list -> list.map { it.id } }
        override suspend fun isFavorite(id: String): Boolean = favorites.value.any { it.id == id }
        override suspend fun insertFavorite(entity: FavoriteWallpaperEntity) {
            favorites.value = favorites.value.filterNot { it.id == entity.id } + entity
        }
        override suspend fun deleteFavoriteById(id: String) {
            favorites.value = favorites.value.filterNot { it.id == id }
        }
        override suspend fun getCachedWallpapers(cacheKey: String): List<CachedWallpaperEntity> =
            cache[cacheKey].orEmpty()
        override suspend fun getCacheTimestamp(cacheKey: String): Long? = meta[cacheKey]
        override fun observeCacheCount(): Flow<Int> = MutableStateFlow(cache.values.sumOf { it.size })
        override suspend fun insertCacheMeta(meta: QueryCacheMetaEntity) {
            this.meta[meta.cacheKey] = meta.fetchedAtMillis
        }
        override suspend fun insertCachedWallpapers(items: List<CachedWallpaperEntity>) {
            val key = items.firstOrNull()?.cacheKey ?: return
            cache[key] = items
        }
        override suspend fun deleteCacheForKey(cacheKey: String) {
            cache.remove(cacheKey)
        }
        override suspend fun clearAllCachedWallpapers() {
            cache.clear()
        }
        override suspend fun clearAllCacheMeta() {
            meta.clear()
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
        val capturedQueries = mutableListOf<String?>()
        val capturedPages = mutableListOf<Int>()
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
            listOf("Frieren: Beyond Journey's End", "Jujutsu Kaisen", "JoJo's Bizarre Adventure"),
            capturedQueries,
        )
        assertEquals(listOf(1, 1, 3), capturedPages)
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
            listOf("Re:Zero", "Oshi no Ko", "Witch Hat Atelier"),
            capturedQueries.drop(3),
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
        var wallhavenCalls = 0
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
                wallhavenCalls++
                val q = query ?: "anime"
                return Response.success(
                    WallhavenResponse(
                        data = listOf(
                            WallhavenItem(
                                id = "${q.hashCode()}_$page",
                                path = "https://w.wallhaven.cc/full/01/sample_$wallhavenCalls.jpg",
                                dimensionX = 2160,
                                dimensionY = 3840,
                                thumbs = WallhavenThumbs(
                                    large = "https://th.wallhaven.cc/lg/01/sample_$wallhavenCalls.jpg",
                                    original = "https://th.wallhaven.cc/orig/01/sample_$wallhavenCalls.jpg",
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
        val callsAfterPrefetch = wallhavenCalls
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
        assertEquals(callsAfterPrefetch, wallhavenCalls)
    }
}
