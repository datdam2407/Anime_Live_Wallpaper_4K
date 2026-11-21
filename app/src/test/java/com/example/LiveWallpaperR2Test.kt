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
}
