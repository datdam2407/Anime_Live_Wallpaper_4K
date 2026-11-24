package com.example

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.example.data.local.LiveWallpaperDownloader
import com.example.data.remote.LiveCatalogApi
import com.example.data.remote.LiveCatalogResponse
import com.example.data.remote.LiveWallpaperItem
import com.example.data.remote.WallhavenResponse
import com.example.data.repository.LiveWallpaperRepository
import com.example.domain.model.AnimeSeriesPreview
import com.example.domain.model.CategoryCoverCandidate
import com.example.domain.model.FacetType
import com.example.domain.model.LiveCatalogFacets
import com.example.domain.model.LiveFilterState
import com.example.domain.model.LiveTagChip
import com.example.domain.model.SearchSuggestionType
import com.example.service.AnimeLiveWallpaperService
import com.example.service.DelogoEffect
import com.example.ui.state.MainViewModel
import kotlin.random.Random
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveWallpaperR2Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parses jsDelivr live_wallpapers_v1 json and maps 3-tier R2 urls correctly`() {
        val sampleJson = """
            {
              "version": 1,
              "updatedAt": "2026-11-19T12:00:00Z",
              "total": 14,
              "wallpapers": [
                {
                  "id": "kuro_0001",
                  "slug": "gojo-hollow-purple",
                  "title": "Gojo Hollow Purple",
                  "category": "Jujutsu Kaisen",
                  "tags": ["gojo", "jjk", "purple"],
                  "thumbUrl": "https://cdn.example.r2.dev/kuro_0001/thumb.jpg",
                  "previewUrl": "https://cdn.example.r2.dev/kuro_0001/preview.mp4",
                  "fullUrl": "https://cdn.example.r2.dev/kuro_0001/full.mp4",
                  "sizeMb": 4.25
                }
              ]
            }
        """.trimIndent()

        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val adapter = moshi.adapter(LiveCatalogResponse::class.java)
        val parsed = adapter.fromJson(sampleJson)!!

        assertEquals(1, parsed.version)
        assertEquals(14, parsed.total)
        val wallpapers = parsed.wallpapers.orEmpty()
        assertEquals(1, wallpapers.size)

        val appWallpaper = wallpapers.first().toAppWallpaper()
        assertEquals("kuro_0001", appWallpaper.id)
        assertEquals("https://cdn.example.r2.dev/kuro_0001/thumb.jpg", appWallpaper.thumbnailUrl)
        assertEquals("https://cdn.example.r2.dev/kuro_0001/preview.mp4", appWallpaper.effectivePreviewVideoUrl)
        assertEquals("https://cdn.example.r2.dev/kuro_0001/full.mp4", appWallpaper.videoUrl)
        assertTrue(appWallpaper.isLive)
        assertEquals(4.25, appWallpaper.sizeMb!!, 0.001)
    }

    @Test
    fun `downloader saves full mp4 once and hits zero-egress cache on second apply`() = runTest {
        val networkCalls = AtomicInteger(0)
        val payloadBytes = ByteArray(32 * 1024) { idx -> (idx % 127).toByte() }

        val fakeClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    networkCalls.incrementAndGet()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(payloadBytes.toResponseBody("video/mp4".toMediaType()))
                        .build()
                },
            )
            .build()

        val storageDir = File(tempFolder.root, "live_wallpapers")
        val downloader = LiveWallpaperDownloader(storageRootDir = storageDir, downloadClient = fakeClient)

        assertFalse(downloader.isDownloaded("kuro_0001"))

        val firstProgress = mutableListOf<Int>()
        val firstResult = downloader.downloadIfNeeded(
            wallpaperId = "kuro_0001",
            fullUrl = "https://cdn.example.r2.dev/kuro_0001/full.mp4",
            onProgress = { firstProgress.add(it) },
        )

        assertTrue(firstResult.isSuccess)
        val savedFile = firstResult.getOrThrow()
        assertEquals("kuro_0001.mp4", savedFile.name)
        assertEquals(payloadBytes.size.toLong(), savedFile.length())
        assertEquals(1, networkCalls.get())
        assertEquals(100, firstProgress.last())
        assertTrue(downloader.isDownloaded("kuro_0001"))

        // Second apply must jump immediately to 100% with 0 network egress
        val secondProgress = mutableListOf<Int>()
        val secondResult = downloader.downloadIfNeeded(
            wallpaperId = "kuro_0001",
            fullUrl = "https://cdn.example.r2.dev/kuro_0001/full.mp4",
            onProgress = { secondProgress.add(it) },
        )

        assertTrue(secondResult.isSuccess)
        assertEquals(savedFile.absolutePath, secondResult.getOrThrow().absolutePath)
        assertEquals(1, networkCalls.get()) // ZERO egress on 2nd apply!
        assertEquals(listOf(100), secondProgress)

        // Clearing videos keeps the active wallpaper intact and deletes others
        val otherFile = File(storageDir, "kuro_0002.mp4").apply { writeBytes(ByteArray(1024) { 1 }) }
        val deleted = downloader.clearDownloadedVideos(keepFilePath = savedFile.absolutePath)
        assertEquals(1, deleted)
        assertTrue(savedFile.exists())
        assertFalse(otherFile.exists())
    }

    @Test
    fun `live repository serves 14 items, filters by tags and category, and preserves cache when offline`() = runTest {
        var offline = false
        val items = (1..14).map { idx ->
            val padded = idx.toString().padStart(4, '0')
            LiveWallpaperItem(
                id = "kuro_$padded",
                slug = "scene-$idx",
                title = if (idx == 1) "Frieren Moonlight" else "Anime Scene $idx",
                category = if (idx == 1) "Frieren" else "Solo Leveling",
                tags = if (idx == 1) listOf("frieren", "magic") else listOf("jinwoo", "arise"),
                thumbUrl = "https://cdn.example.r2.dev/kuro_$padded/thumb.jpg",
                previewUrl = "https://cdn.example.r2.dev/kuro_$padded/preview.mp4",
                fullUrl = "https://cdn.example.r2.dev/kuro_$padded/full.mp4",
                sizeMb = 3.5,
            )
        }
        val fakeApi = object : LiveCatalogApi {
            override suspend fun getLiveCatalog(): LiveCatalogResponse {
                if (offline) throw IOException("No internet")
                return LiveCatalogResponse(
                    version = 1,
                    updatedAt = "2026-11-19",
                    total = items.size,
                    wallpapers = items,
                )
            }

            override suspend fun getLiveCatalogByUrl(url: String): LiveCatalogResponse {
                if (offline) throw IOException("No internet")
                return getLiveCatalog()
            }
        }
        val downloader = LiveWallpaperDownloader(
            storageRootDir = File(tempFolder.root, "live_repo_videos"),
            downloadClient = OkHttpClient(),
        )
        val repository = LiveWallpaperRepository(api = fakeApi, downloader = downloader)

        val initialPage = repository.getLiveWallpapersPage(query = "Home_Trending", page = 1).getOrThrow()
        assertEquals(14, initialPage.items.size)
        assertFalse(initialPage.hasMore)

        val filteredByTag = repository.getLiveWallpapersPage(query = "magic", page = 1).getOrThrow()
        assertEquals(1, filteredByTag.items.size)
        assertEquals("kuro_0001", filteredByTag.items.first().id)

        // Go offline and force refresh: repository keeps last good catalog instead of crashing/empty
        offline = true
        val offlinePage = repository.getLiveWallpapersPage(
            query = "Home_Trending",
            page = 1,
            forceRefresh = true,
        ).getOrThrow()
        assertEquals(14, offlinePage.items.size)
    }

    @Test
    fun `tolerates missing optional json fields and normalizes duplicate slashes in R2 urls`() {
        val partialJson = """
            {
              "wallpapers": [
                {
                  "id": "kuro_0007",
                  "title": "Sung Jinwoo",
                  "thumbUrl": "http://pub-demo.r2.dev//live//kuro_0007//thumb.jpg",
                  "fullUrl": "https://pub-demo.r2.dev//live/kuro_0007/full.mp4"
                }
              ]
            }
        """.trimIndent()

        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val parsed = moshi.adapter(LiveCatalogResponse::class.java).fromJson(partialJson)!!
        val mapped = parsed.wallpapers.orEmpty().single().toAppWallpaper()

        assertEquals("kuro_0007", mapped.id)
        assertEquals("https://pub-demo.r2.dev/live/kuro_0007/thumb.jpg", mapped.thumbnailUrl)
        // previewUrl was omitted -> automatically falls back to normalized fullUrl
        assertEquals("https://pub-demo.r2.dev/live/kuro_0007/full.mp4", mapped.effectivePreviewVideoUrl)
        assertEquals("https://pub-demo.r2.dev/live/kuro_0007/full.mp4", mapped.videoUrl)
    }

    @Test
    fun `falls back to github raw mirror when jsDelivr fails`() = runTest {
        var fallbackUrlCalled: String? = null
        val fallbackItem = LiveWallpaperItem(
            id = "kuro_0002",
            title = "Tanjiro Sun Halo",
            category = "Demon Slayer",
            thumbUrl = "https://pub-demo.r2.dev/kuro_0002/thumb.jpg",
            previewUrl = "https://pub-demo.r2.dev/kuro_0002/preview.mp4",
            fullUrl = "https://pub-demo.r2.dev/kuro_0002/full.mp4",
        )
        val fakeApi = object : LiveCatalogApi {
            override suspend fun getLiveCatalog(): LiveCatalogResponse {
                throw IOException("jsDelivr timeout")
            }

            override suspend fun getLiveCatalogByUrl(url: String): LiveCatalogResponse {
                fallbackUrlCalled = url
                return LiveCatalogResponse(
                    version = 1,
                    total = 1,
                    wallpapers = listOf(fallbackItem),
                )
            }
        }
        val downloader = LiveWallpaperDownloader(
            storageRootDir = File(tempFolder.root, "live_repo_fallback"),
            downloadClient = OkHttpClient(),
        )
        val repository = LiveWallpaperRepository(api = fakeApi, downloader = downloader)

        val page = repository.getLiveWallpapersPage(query = "Home_Trending", page = 1).getOrThrow()
        assertEquals(LiveCatalogApi.GITHUB_RAW_FALLBACK_URL, fallbackUrlCalled)
        assertEquals(1, page.items.size)
        assertEquals("kuro_0002", page.items.first().id)
    }

    @OptIn(UnstableApi::class)
    @Test
    fun `runtime delogo is off by default and wallpaper service prefers newest source between DataStore and MediaDownloadManager`() {
        assertFalse(DelogoEffect.shouldEnableRuntimeDelogo())

        val oldSlotFile = File(tempFolder.root, "wallpaper_a.mp4").apply { writeBytes(ByteArray(512) { 1 }) }
        val newR2File = File(tempFolder.root, "kuro_0005.mp4").apply { writeBytes(ByteArray(1024) { 2 }) }

        // Case 1: DataStore updated more recently (e.g. Apply wrote only to DataStore) -> DataStore wins
        val pickedDataStore = AnimeLiveWallpaperService.resolvePreferredLiveFile(
            configuredPath = newR2File.absolutePath,
            configuredUpdatedAt = 2_000L,
            prefsActiveFile = oldSlotFile,
            prefsUpdatedAt = 1_000L,
        )
        assertEquals(newR2File.absolutePath, pickedDataStore?.absolutePath)

        // Case 2: SharedPreferences committed more recently (immediate broadcast hot-swap) -> Prefs wins
        val pickedPrefs = AnimeLiveWallpaperService.resolvePreferredLiveFile(
            configuredPath = oldSlotFile.absolutePath,
            configuredUpdatedAt = 1_000L,
            prefsActiveFile = newR2File,
            prefsUpdatedAt = 2_500L,
        )
        assertEquals(newR2File.absolutePath, pickedPrefs?.absolutePath)
    }

    @Test
    fun `multi-keyword search matches title, tags, category, aliases, and extracts dynamic facets and suggestions`() = runTest {
        val sampleItems = listOf(
            LiveWallpaperItem(
                id = "kuro_0001",
                slug = "gojo-hollow-purple",
                title = "Gojo Satoru Hollow Purple",
                category = "Jujutsu Kaisen",
                tags = listOf("gojo", "jjk", "purple", "magic", "4k"),
                thumbUrl = "https://cdn.example.r2.dev/kuro_0001/thumb.jpg",
                previewUrl = "https://cdn.example.r2.dev/kuro_0001/preview.mp4",
                fullUrl = "https://cdn.example.r2.dev/kuro_0001/full.mp4",
            ),
            LiveWallpaperItem(
                id = "kuro_0002",
                slug = "frieren-moonlight-staff",
                title = "Frieren Under Moonlight",
                category = "Frieren",
                tags = listOf("frieren", "magic", "fantasy", "4k"),
                thumbUrl = "https://cdn.example.r2.dev/kuro_0002/thumb.jpg",
                previewUrl = "https://cdn.example.r2.dev/kuro_0002/preview.mp4",
                fullUrl = "https://cdn.example.r2.dev/kuro_0002/full.mp4",
            ),
            LiveWallpaperItem(
                id = "kuro_0003",
                slug = "renji-bankai-crimson",
                title = "Renji Abarai Bankai Flame",
                category = "Bleach",
                tags = listOf("bleach", "sword", "dark", "4k"),
                thumbUrl = "https://cdn.example.r2.dev/kuro_0003/thumb.jpg",
                previewUrl = "https://cdn.example.r2.dev/kuro_0003/preview.mp4",
                fullUrl = "https://cdn.example.r2.dev/kuro_0003/full.mp4",
            ),
        )
        val fakeApi = object : LiveCatalogApi {
            override suspend fun getLiveCatalog(): LiveCatalogResponse = LiveCatalogResponse(
                version = 1,
                total = sampleItems.size,
                wallpapers = sampleItems,
            )

            override suspend fun getLiveCatalogByUrl(url: String): LiveCatalogResponse = getLiveCatalog()
        }
        val downloader = LiveWallpaperDownloader(
            storageRootDir = File(tempFolder.root, "live_repo_search"),
            downloadClient = OkHttpClient(),
        )
        val repository = LiveWallpaperRepository(api = fakeApi, downloader = downloader)

        // Warm catalog and populate dynamic facets
        val all = repository.getLiveWallpapersPage(query = "Home_Trending", page = 1).getOrThrow()
        assertEquals(3, all.items.size)

        // 1. Dynamic facets: 3 categories and deduplicated #tags sorted by frequency
        val facets = repository.catalogFacets.value
        assertEquals(3, facets.totalWallpapers)
        assertEquals(3, facets.categories.size)
        assertTrue(facets.categories.any { it.label == "Frieren" && it.count == 1 })
        val fourKTag = facets.tags.first()
        assertEquals("#4k", fourKTag.label)
        assertEquals(3, fourKTag.count)
        assertTrue(facets.tags.any { it.label == "#magic" && it.count == 2 })

        // 2. Multi-keyword AND across category + tag + title
        val frierenMagic = repository.getLiveWallpapersPage(query = "frieren magic", page = 1).getOrThrow()
        assertEquals(listOf("kuro_0002"), frierenMagic.items.map { it.id })

        // 3. Hashtag prefix + multi-keyword
        val jjkPurple = repository.getLiveWallpapersPage(query = "#jjk purple", page = 1).getOrThrow()
        assertEquals(listOf("kuro_0001"), jjkPurple.items.map { it.id })

        // 4. Category + tag combined
        val bleachSword = repository.getLiveWallpapersPage(query = "bleach sword", page = 1).getOrThrow()
        assertEquals(listOf("kuro_0003"), bleachSword.items.map { it.id })

        // 5. Long official title matching short JSON category ("Frieren: Beyond Journey's End" -> "Frieren")
        val longTitle = repository.getLiveWallpapersPage(query = "Frieren: Beyond Journey's End", page = 1).getOrThrow()
        assertEquals(listOf("kuro_0002"), longTitle.items.map { it.id })

        // 6. Unmatched query cleanly returns 0 items
        val noMatch = repository.getLiveWallpapersPage(query = "nonexistent_xyz_999", page = 1).getOrThrow()
        assertTrue(noMatch.items.isEmpty())

        // 7. Instant autocomplete suggestions return matching Category, #Tag, and Title
        val magicSuggestions = repository.getSearchSuggestions("mag")
        assertTrue(magicSuggestions.any { it.label == "#magic" && it.count == 2 })

        val frierenSuggestions = repository.getSearchSuggestions("frie")
        assertTrue(frierenSuggestions.any { it.label == "Frieren" })
        assertTrue(frierenSuggestions.any { it.label == "Frieren Under Moonlight" })

        // 8. Category chips expose sanitized R2 thumbUrl for SearchScreen Trending Categories grid
        val frierenCat = facets.categories.first { it.label == "Frieren" }
        assertEquals("https://cdn.example.r2.dev/kuro_0002/thumb.jpg", frierenCat.coverUrl)

        // 9. Trending color pills match Live wallpapers via color synonyms (e.g. "black" -> "dark", "red" -> "flame")
        val blackMatches = repository.getLiveWallpapersPage(query = "black", page = 1).getOrThrow()
        assertEquals(listOf("kuro_0003"), blackMatches.items.map { it.id })

        val redMatches = repository.getLiveWallpapersPage(query = "red", page = 1).getOrThrow()
        assertEquals(listOf("kuro_0003"), redMatches.items.map { it.id })

        // 10. Explore domain live cover matcher guarantees LIVE-badged cards return real matching live thumbnails
        assertEquals(3, repository.getMatchingLiveCoverUrls("Anime").size)
        assertTrue(repository.getMatchingLiveCoverUrls("Games").contains("https://cdn.example.r2.dev/kuro_0002/thumb.jpg"))
        assertTrue(repository.getMatchingLiveCoverUrls("Abstract").contains("https://cdn.example.r2.dev/kuro_0001/thumb.jpg"))
        assertTrue(repository.getMatchingLiveCoverUrls("Comics").contains("https://cdn.example.r2.dev/kuro_0003/thumb.jpg"))
    }

    @Test
    fun `search category cover rotation mixes static and live images and avoids repeating previous cover`() {
        val staticPool = listOf(
            CategoryCoverCandidate(imageUrl = "https://static.example/a.jpg", isLive = false),
            CategoryCoverCandidate(imageUrl = "https://static.example/b.jpg", isLive = false),
        )
        val livePool = listOf(
            CategoryCoverCandidate(imageUrl = "https://r2.example/live1.jpg", isLive = true, liveKeyword = "Games"),
            CategoryCoverCandidate(imageUrl = "https://r2.example/live2.jpg", isLive = true, liveKeyword = "Games"),
        )

        val rng = Random(42)
        var previous: String? = null
        var sawStatic = false
        var sawLive = false
        repeat(30) {
            val picked = MainViewModel.pickNextRandomCover(
                staticCandidates = staticPool,
                liveCandidates = livePool,
                previousUrl = previous,
                random = rng,
            )!!
            assertTrue("Expected rotated cover different from previous ($previous)", picked.imageUrl != previous)
            if (picked.isLive) {
                sawLive = true
                assertEquals("Games", picked.liveKeyword)
            } else {
                sawStatic = true
            }
            previous = picked.imageUrl
        }
        assertTrue("Expected both static and live covers to appear across revisits", sawStatic && sawLive)

        // Single-pool rotation also avoids repeating previous image when >= 2 options exist
        val nextStatic = MainViewModel.pickNextRandomCover(
            staticCandidates = staticPool,
            liveCandidates = emptyList(),
            previousUrl = "https://static.example/a.jpg",
            random = Random(7),
        )
        assertEquals("https://static.example/b.jpg", nextStatic?.imageUrl)
        assertFalse(nextStatic!!.isLive)
    }

    @Test
    fun `anime 200+ series preview computes remaining count badge and parses Wallhaven meta total`() {
        val sampleWallhavenJson = """
            {
              "data": [
                {
                  "id": "abc123",
                  "path": "https://w.wallhaven.cc/full/ab/wallhaven-abc123.jpg",
                  "dimension_x": 1440,
                  "dimension_y": 2560,
                  "thumbs": {
                    "large": "https://th.wallhaven.cc/lg/ab/abc123.jpg",
                    "original": "https://th.wallhaven.cc/orig/ab/abc123.jpg"
                  }
                }
              ],
              "meta": {
                "current_page": 1,
                "last_page": 5,
                "per_page": 24,
                "total": 120
              }
            }
        """.trimIndent()

        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val parsed = moshi.adapter(WallhavenResponse::class.java).fromJson(sampleWallhavenJson)!!
        assertEquals(120, parsed.meta?.total)

        val fourThumbs = listOf("u1", "u2", "u3", "u4")
        val onePiecePreview = AnimeSeriesPreview(
            categoryId = "0_one_piece",
            thumbnailUrls = fourThumbs,
            totalCount = 120,
        )
        assertEquals(116, onePiecePreview.remainingCount)
        assertEquals("+116", onePiecePreview.formatRemainingBadge())

        val twentySixPreview = AnimeSeriesPreview(
            categoryId = "1_naruto",
            thumbnailUrls = fourThumbs,
            totalCount = 26,
        )
        assertEquals("+22", twentySixPreview.formatRemainingBadge())

        val largePreview = AnimeSeriesPreview(
            categoryId = "2_bleach",
            thumbnailUrls = fourThumbs,
            totalCount = 1250,
        )
        assertEquals("+1.2K", largePreview.formatRemainingBadge())

        val smallPreview = AnimeSeriesPreview(
            categoryId = "3_small",
            thumbnailUrls = fourThumbs,
            totalCount = 4,
        )
        assertEquals(0, smallPreview.remainingCount)
        assertEquals(null, smallPreview.formatRemainingBadge())

        // Verify direct DetailPreviewScreen tile alignment (tile index 0..3 matches wallpapers[0..3] + remaining items)
        val fiftyEightItems = (1..58).map { idx ->
            com.example.domain.model.AppWallpaper(
                id = "wh_op_$idx",
                title = "One Piece #$idx",
                animeSeries = "One Piece",
                imageUrl = "https://w.wallhaven.cc/full/op/$idx.jpg",
                thumbnailUrl = "https://th.wallhaven.cc/lg/op/$idx.jpg",
                videoUrl = null,
                isLive = false,
                width = 1440,
                height = 2560,
                source = "Wallhaven",
            )
        }
        val directDetailPreview = AnimeSeriesPreview(
            categoryId = "0_one_piece",
            thumbnailUrls = fiftyEightItems.take(4).map { it.thumbnailUrl },
            wallpapers = fiftyEightItems.take(24),
            totalCount = 58,
        )
        assertEquals("+54", directDetailPreview.formatRemainingBadge())
        assertEquals(directDetailPreview.thumbnailUrls[1], directDetailPreview.wallpapers[1].thumbnailUrl)
        assertEquals("COLLECTION", com.example.ui.navigation.DetailRoute.FEED_COLLECTION)
    }

    @Test
    fun `faceted LiveFilterState supports series, contextual tagsByCategory, combined filters, and color synonyms`() = runTest {
        val v1Json = """
            {
              "version": 1,
              "total": 4,
              "wallpapers": [
                {
                  "id": "kuro_101",
                  "slug": "raiden-shogun-dark-lightning",
                  "title": "Raiden Shogun Storm",
                  "category": "Games",
                  "series": "Genshin Impact",
                  "tags": ["dark", "amoled", "lightning", "sword"],
                  "thumbUrl": "https://cdn.example.r2.dev/kuro_101/thumb.jpg",
                  "previewUrl": "https://cdn.example.r2.dev/kuro_101/preview.mp4",
                  "fullUrl": "https://cdn.example.r2.dev/kuro_101/full.mp4"
                },
                {
                  "id": "kuro_102",
                  "slug": "changli-flame-wuthering",
                  "title": "Changli Crimson Phoenix",
                  "category": "Games",
                  "series": "Wuthering Waves",
                  "tags": ["fire", "glowing", "dark"],
                  "thumbUrl": "https://cdn.example.r2.dev/kuro_102/thumb.jpg",
                  "previewUrl": "https://cdn.example.r2.dev/kuro_102/preview.mp4",
                  "fullUrl": "https://cdn.example.r2.dev/kuro_102/full.mp4"
                },
                {
                  "id": "kuro_103",
                  "slug": "nissan-gtr-rain-drift",
                  "title": "GTR Midnight Drift",
                  "category": "Cars",
                  "series": "Cars",
                  "tags": ["jdm", "rain", "drift"],
                  "thumbUrl": "https://cdn.example.r2.dev/kuro_103/thumb.jpg",
                  "previewUrl": "https://cdn.example.r2.dev/kuro_103/preview.mp4",
                  "fullUrl": "https://cdn.example.r2.dev/kuro_103/full.mp4"
                },
                {
                  "id": "kuro_104",
                  "slug": "berserk-guts-manga-ink",
                  "title": "Guts Berserker Armor",
                  "category": "Anime",
                  "series": "Berserk",
                  "tags": ["monochrome", "grayscale", "sword"],
                  "thumbUrl": "https://cdn.example.r2.dev/kuro_104/thumb.jpg",
                  "previewUrl": "https://cdn.example.r2.dev/kuro_104/preview.mp4",
                  "fullUrl": "https://cdn.example.r2.dev/kuro_104/full.mp4"
                }
              ]
            }
        """.trimIndent()

        val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        val parsed = moshi.adapter(LiveCatalogResponse::class.java).fromJson(v1Json)!!
        val items = parsed.wallpapers.orEmpty()
        assertEquals("Genshin Impact", items[0].series)
        assertEquals("Genshin Impact", items[0].toAppWallpaper().animeSeries)

        val fakeApi = object : LiveCatalogApi {
            override suspend fun getLiveCatalog(): LiveCatalogResponse = parsed
            override suspend fun getLiveCatalogByUrl(url: String): LiveCatalogResponse = parsed
        }
        val downloader = LiveWallpaperDownloader(
            storageRootDir = File(tempFolder.root, "live_repo_faceted"),
            downloadClient = OkHttpClient(),
        )
        val repository = LiveWallpaperRepository(api = fakeApi, downloader = downloader)
        repository.getLiveWallpapersPage(LiveFilterState(), page = 1).getOrThrow()

        // 1. Series facet excludes junk series identical to category ("Cars" == "Cars")
        val facets = repository.catalogFacets.value
        assertEquals(setOf("Games", "Cars", "Anime"), facets.categories.map { it.label }.toSet())
        assertEquals(setOf("Genshin Impact", "Wuthering Waves", "Berserk"), facets.series.map { it.label }.toSet())
        assertFalse(facets.series.any { it.label.equals("Cars", ignoreCase = true) })

        // 2. Contextual tagsByCategory scopes Row 2 tags strictly to selected category
        val carTags = facets.tagsFor("Cars").map { it.query }.toSet()
        assertEquals(setOf("jdm", "rain", "drift"), carTags)
        assertFalse(carTags.contains("sword"))
        val gameTags = facets.tagsFor("Games").map { it.query }.toSet()
        assertTrue(gameTags.contains("dark"))
        assertTrue(gameTags.contains("amoled"))
        assertFalse(gameTags.contains("jdm"))

        // 3. Combined Max 1 Category + Max 1 Tag selection:
        //    Category and Tag can be active simultaneously, while tapping another Tag replaces the previous Tag.
        val gamesOnly = LiveFilterState().toggleCategory("Games")
        assertTrue(gamesOnly.isChipFilterActive)
        assertEquals("Games", gamesOnly.displayInputText())
        assertEquals(setOf("kuro_101", "kuro_102"), repository.getLiveWallpapersPage(gamesOnly).getOrThrow().items.map { it.id }.toSet())

        // Tapping #dark keeps Games active so user gets Games + #dark
        val gamesAndDark = gamesOnly.toggleTag("dark")
        assertEquals("Games", gamesAndDark.selectedCategory)
        assertEquals(setOf("dark"), gamesAndDark.selectedTags)
        assertTrue(gamesAndDark.isChipFilterActive)
        assertEquals("Games #dark", gamesAndDark.displayInputText())
        assertEquals("Games · #dark", gamesAndDark.summaryLabel())
        assertEquals(setOf("kuro_101", "kuro_102"), repository.getLiveWallpapersPage(gamesAndDark).getOrThrow().items.map { it.id }.toSet())

        // Tapping #fire replaces #dark (strictly max 1 Tag) while preserving Category = Games
        val gamesAndFire = gamesAndDark.toggleTag("fire")
        assertEquals("Games", gamesAndFire.selectedCategory)
        assertEquals(setOf("fire"), gamesAndFire.selectedTags)
        assertEquals("Games #fire", gamesAndFire.displayInputText())
        assertEquals("Games · #fire", gamesAndFire.summaryLabel())
        assertEquals(listOf("kuro_102"), repository.getLiveWallpapersPage(gamesAndFire).getOrThrow().items.map { it.id })

        // Re-tapping #fire clears only the Tag and keeps Category = Games
        val backToGamesOnly = gamesAndFire.toggleTag("fire")
        assertEquals("Games", backToGamesOnly.selectedCategory)
        assertTrue(backToGamesOnly.selectedTags.isEmpty())
        assertEquals("Games", backToGamesOnly.displayInputText())

        // Re-tapping Games on Games + #fire clears only Category and keeps Tag = #fire
        val fireOnly = gamesAndFire.toggleCategory("Games")
        assertEquals(null, fireOnly.selectedCategory)
        assertEquals(setOf("fire"), fireOnly.selectedTags)
        assertEquals("#fire", fireOnly.displayInputText())

        // Re-tapping the remaining active Tag toggles off cleanly back to All
        val toggledOff = fireOnly.toggleTag("fire")
        assertTrue(toggledOff.isHome)
        assertFalse(toggledOff.isChipFilterActive)
        assertEquals("", toggledOff.displayInputText())

        // 4. Series facet, persistent displayInputText, and SearchSuggestionType.SERIES
        val wutheringOnly = LiveFilterState(selectedSeries = "Wuthering Waves")
        assertEquals("Wuthering Waves", wutheringOnly.displayInputText())
        assertEquals(listOf("kuro_102"), repository.getLiveWallpapersPage(wutheringOnly).getOrThrow().items.map { it.id })

        val seriesSuggestions = repository.getSearchSuggestions("wuther")
        assertTrue(seriesSuggestions.any { it.type == SearchSuggestionType.SERIES && it.query == "Wuthering Waves" })

        // 5. Color Synonym OR map: "black and white" -> monochrome/grayscale, "black" -> amoled/dark/night
        val bwFilter = LiveFilterState(query = "black and white", isColorSearch = true)
        assertEquals(listOf("kuro_104"), repository.getLiveWallpapersPage(bwFilter).getOrThrow().items.map { it.id })

        val blackFilter = LiveFilterState(query = "black", isColorSearch = true)
        assertEquals(
            setOf("kuro_101", "kuro_102", "kuro_103"),
            repository.getLiveWallpapersPage(blackFilter).getOrThrow().items.map { it.id }.toSet(),
        )
    }

    @Test
    fun `extractCatalogFacets eliminates duplicate overlapping tags like ninja-shinobi-naruto and rotates fresh tags on refresh`() = runTest {
        val overlappingCatalog = buildList {
            // 6 Naruto items all sharing #anime, #4k, #ninja, #shinobi, #naruto
            repeat(6) { i ->
                add(
                    LiveWallpaperItem(
                        id = "naruto_$i",
                        slug = "naruto-shinobi-$i",
                        title = "Naruto Shinobi Scene $i",
                        category = "Anime",
                        series = "Naruto",
                        tags = listOf("anime", "4k", "ninja", "shinobi", "naruto"),
                        thumbUrl = "https://cdn.example.r2.dev/naruto_$i/thumb.jpg",
                        previewUrl = "https://cdn.example.r2.dev/naruto_$i/preview.mp4",
                        fullUrl = "https://cdn.example.r2.dev/naruto_$i/full.mp4",
                    ),
                )
            }
            // 4 Cyberpunk items
            repeat(4) { i ->
                add(
                    LiveWallpaperItem(
                        id = "cyber_$i",
                        slug = "cyberpunk-neon-$i",
                        title = "Cyberpunk Neon City $i",
                        category = "City",
                        series = "Edgerunners",
                        tags = listOf("city", "cyberpunk", "neon"),
                        thumbUrl = "https://cdn.example.r2.dev/cyber_$i/thumb.jpg",
                        previewUrl = "https://cdn.example.r2.dev/cyber_$i/preview.mp4",
                        fullUrl = "https://cdn.example.r2.dev/cyber_$i/full.mp4",
                    ),
                )
            }
            // 3 Magic Frieren items
            repeat(3) { i ->
                add(
                    LiveWallpaperItem(
                        id = "magic_$i",
                        slug = "frieren-magic-$i",
                        title = "Frieren Magic Staff $i",
                        category = "Anime",
                        series = "Frieren",
                        tags = listOf("anime", "magic", "moonlight"),
                        thumbUrl = "https://cdn.example.r2.dev/magic_$i/thumb.jpg",
                        previewUrl = "https://cdn.example.r2.dev/magic_$i/preview.mp4",
                        fullUrl = "https://cdn.example.r2.dev/magic_$i/full.mp4",
                    ),
                )
            }
            // 3 Sword Samurai items
            repeat(3) { i ->
                add(
                    LiveWallpaperItem(
                        id = "sword_$i",
                        slug = "samurai-katana-$i",
                        title = "Samurai Katana Blade $i",
                        category = "Anime",
                        series = "Demon Slayer",
                        tags = listOf("anime", "sword", "katana"),
                        thumbUrl = "https://cdn.example.r2.dev/sword_$i/thumb.jpg",
                        previewUrl = "https://cdn.example.r2.dev/sword_$i/preview.mp4",
                        fullUrl = "https://cdn.example.r2.dev/sword_$i/full.mp4",
                    ),
                )
            }
        }

        val fakeApi = object : LiveCatalogApi {
            override suspend fun getLiveCatalog(): LiveCatalogResponse = LiveCatalogResponse(
                version = 1,
                total = overlappingCatalog.size,
                wallpapers = overlappingCatalog,
            )

            override suspend fun getLiveCatalogByUrl(url: String): LiveCatalogResponse = getLiveCatalog()
        }
        val downloader = LiveWallpaperDownloader(
            storageRootDir = File(tempFolder.root, "live_repo_distinct_tags"),
            downloadClient = OkHttpClient(),
        )
        val repository = LiveWallpaperRepository(api = fakeApi, downloader = downloader)
        repository.getLiveWallpapersPage(LiveFilterState(), page = 1).getOrThrow()

        val initialFacets = repository.catalogFacets.value
        val displayQueries = initialFacets.displayTags.map { it.query.lowercase() }

        // Category duplicates (#anime, #city) and boilerplate (#4k) are filtered out of displayTags
        assertFalse(displayQueries.contains("anime"))
        assertFalse(displayQueries.contains("city"))
        assertFalse(displayQueries.contains("4k"))

        // Only ONE of the 100%-overlapping Naruto cluster (#naruto / #ninja / #shinobi) appears in displayTags!
        val narutoClusterShown = displayQueries.count { it in setOf("ninja", "shinobi", "naruto") }
        assertEquals(1, narutoClusterShown)

        // Distinct clusters (cyberpunk/neon, magic/moonlight, sword/katana) each appear once
        assertEquals(1, displayQueries.count { it in setOf("cyberpunk", "neon") })
        assertEquals(1, displayQueries.count { it in setOf("magic", "moonlight") })
        assertEquals(1, displayQueries.count { it in setOf("sword", "katana") })

        // Rotating display tags on refresh bumps tagRotationVersion and surfaces unseen tags from the clusters
        val beforeVersion = initialFacets.tagRotationVersion
        val beforeSet = displayQueries.toSet()
        repository.rotateDisplayTags()
        val rotatedFacets = repository.catalogFacets.value
        val rotatedQueries = rotatedFacets.displayTags.map { it.query.lowercase() }

        assertEquals(beforeVersion + 1, rotatedFacets.tagRotationVersion)
        assertTrue("Expected rotated tags ($rotatedQueries) to differ from initial ($beforeSet)", rotatedQueries.toSet() != beforeSet)
        // Even after rotation, overlapping tags are NEVER shown side by side
        assertEquals(1, rotatedQueries.count { it in setOf("ninja", "shinobi", "naruto") })
    }

    @Test
    fun `rotateContentAndTags shuffles live videos immediately and throttles CDN refreshes within 60s`() = runTest {
        var nowMs = 1_000_000L
        var apiCalls = 0
        val sampleItems = (1..24).map { idx ->
            val id = "live_${idx.toString().padStart(3, '0')}"
            LiveWallpaperItem(
                id = id,
                title = "Live Video $idx",
                category = if (idx % 2 == 0) "Anime" else "Games",
                tags = listOf("tag_$idx", "theme_${idx % 6}"),
                thumbUrl = "https://cdn.example.r2.dev/$id/thumb.jpg",
                previewUrl = "https://cdn.example.r2.dev/$id/preview.mp4",
                fullUrl = "https://cdn.example.r2.dev/$id/full.mp4",
            )
        }
        val fakeApi = object : LiveCatalogApi {
            override suspend fun getLiveCatalog(): LiveCatalogResponse {
                apiCalls++
                return LiveCatalogResponse(version = 1, total = sampleItems.size, wallpapers = sampleItems)
            }

            override suspend fun getLiveCatalogByUrl(url: String): LiveCatalogResponse = getLiveCatalog()
        }
        val downloader = LiveWallpaperDownloader(
            storageRootDir = File(tempFolder.root, "live_repo_refresh_shuffle"),
            downloadClient = OkHttpClient(),
        )
        val repository = LiveWallpaperRepository(
            api = fakeApi,
            downloader = downloader,
            clock = { nowMs },
        )

        val initialIds = repository.getLiveWallpapersPage(LiveFilterState(), page = 1, pageSize = 12)
            .getOrThrow().items.map { it.id }
        assertEquals(1, apiCalls)

        // Rapid pull-to-refresh 5 seconds later: 0 CDN calls, brand-new video order, duplicate-free pagination
        nowMs += 5_000L
        repository.rotateContentAndTags()
        val refreshedPage1 = repository.getLiveWallpapersPage(
            LiveFilterState(),
            page = 1,
            pageSize = 12,
            forceRefresh = true,
        ).getOrThrow().items.map { it.id }
        val refreshedPage2 = repository.getLiveWallpapersPage(
            LiveFilterState(),
            page = 2,
            pageSize = 12,
            forceRefresh = false,
        ).getOrThrow().items.map { it.id }

        assertEquals(1, apiCalls)
        assertTrue("Expected refreshed video order to differ from initial", refreshedPage1 != initialIds)
        assertEquals(24, (refreshedPage1 + refreshedPage2).distinct().size)

        // After 61 seconds, forceRefresh is allowed to re-fetch the CDN catalog once
        nowMs += LiveWallpaperRepository.LIVE_CATALOG_REFRESH_COOLDOWN_MS + 1_000L
        repository.rotateContentAndTags()
        repository.getLiveWallpapersPage(LiveFilterState(), page = 1, pageSize = 12, forceRefresh = true).getOrThrow()
        assertEquals(2, apiCalls)
    }

    @Test
    fun `CrossTabSearchSync keeps Live and 4K Artworks synchronized and displayTagsFor never leaks zero-match tags`() {
        // 1. Selecting Popular Series "One Piece" from SearchScreen syncs BOTH Live & Static filters
        val seriesSynced = com.example.domain.model.CrossTabSearchSync.syncFromSearch(
            rawQuery = "One Piece",
            facetType = FacetType.SERIES,
        )
        assertEquals("One Piece", seriesSynced.liveFilter.selectedSeries)
        assertEquals("One Piece", seriesSynced.staticFilter.searchQuery)
        assertEquals("One Piece", seriesSynced.activeQueryForLive)
        assertEquals("One Piece", seriesSynced.activeQueryForStatic)

        // Switching from Live -> 4K Artworks even if an old static filter existed must sync to "One Piece"
        val switchedToStatic = com.example.domain.model.CrossTabSearchSync.syncOnTabSwitch(
            targetIsLive = false,
            currentLiveFilter = seriesSynced.liveFilter,
            currentStaticFilter = com.example.domain.model.StaticFilterState(searchQuery = "Jujutsu Kaisen"),
            searchInput = "One Piece",
        )
        assertEquals("One Piece", switchedToStatic.staticFilter.searchQuery)
        assertEquals("One Piece", switchedToStatic.activeQueryForStatic)

        // 2. Selecting Trending Color syncs both tabs (with isColorSearch preserved for Live)
        val colorSynced = com.example.domain.model.CrossTabSearchSync.syncFromSearch(
            rawQuery = "black and white",
            isColorSearch = true,
        )
        assertTrue(colorSynced.liveFilter.isColorSearch)
        assertEquals("black and white", colorSynced.liveFilter.query)
        assertEquals("black and white", colorSynced.staticFilter.searchQuery)

        // Switching from 4K Artworks -> Live preserves color search even if Live had stale filter
        val switchedToLive = com.example.domain.model.CrossTabSearchSync.syncOnTabSwitch(
            targetIsLive = true,
            currentLiveFilter = LiveFilterState(selectedSeries = "Naruto"),
            currentStaticFilter = colorSynced.staticFilter,
            searchInput = "black and white",
            isColorSearch = true,
        )
        assertTrue(switchedToLive.liveFilter.isColorSearch)
        assertEquals("black and white", switchedToLive.liveFilter.query)

        // 3. Selecting Popular #Tag syncs both tabs
        val tagSynced = com.example.domain.model.CrossTabSearchSync.syncFromSearch(
            rawQuery = "katana",
            facetType = FacetType.TAG,
        )
        assertEquals(setOf("katana"), tagSynced.liveFilter.selectedTags)
        assertEquals("katana", tagSynced.staticFilter.selectedTag)

        // 4. Selecting Trending Category syncs both tabs
        val catSynced = com.example.domain.model.CrossTabSearchSync.syncFromSearch(
            rawQuery = "Games",
            facetType = FacetType.CATEGORY,
            matchedExploreCategoryTitle = "Games",
        )
        assertEquals("Games", catSynced.liveFilter.selectedCategory)
        assertEquals("Games", catSynced.staticFilter.selectedCategory)

        // 5. LiveCatalogFacets.displayTagsFor never leaks global tags into a category with no matching tags
        val facets = LiveCatalogFacets(
            categories = listOf(
                LiveTagChip("Anime", "Anime", count = 10, type = FacetType.CATEGORY),
                LiveTagChip("Cars", "Cars", count = 1, type = FacetType.CATEGORY),
            ),
            tags = listOf(
                LiveTagChip("#naruto", "naruto", count = 8, type = FacetType.TAG),
                LiveTagChip("#emptytag", "emptytag", count = 0, type = FacetType.TAG),
            ),
            displayTags = listOf(
                LiveTagChip("#naruto", "naruto", count = 8, type = FacetType.TAG),
                LiveTagChip("#emptytag", "emptytag", count = 0, type = FacetType.TAG),
            ),
            tagsByCategory = mapOf(
                "anime" to listOf(LiveTagChip("#naruto", "naruto", count = 8, type = FacetType.TAG)),
            ),
            displayTagsByCategory = mapOf(
                "anime" to listOf(LiveTagChip("#naruto", "naruto", count = 8, type = FacetType.TAG)),
            ),
            totalWallpapers = 11,
        )
        assertEquals(listOf("naruto"), facets.displayTagsFor(null).map { it.query })
        assertTrue(
            "Category with no scoped tags must not leak unrelated global tags",
            facets.displayTagsFor("Cars").isEmpty(),
        )
    }
}
