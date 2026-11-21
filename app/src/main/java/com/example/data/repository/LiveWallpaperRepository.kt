package com.example.data.repository

import android.content.Context
import android.util.Log
import com.example.data.local.LiveWallpaperDownloader
import com.example.data.remote.LiveCatalogApi
import com.example.data.remote.LiveCatalogResponse
import com.example.data.remote.LiveWallpaperItem
import com.example.data.remote.NetworkClient
import com.example.domain.model.AppWallpaper
import com.example.domain.model.LiveCatalogFacets
import com.example.domain.model.LiveSearchSuggestion
import com.example.domain.model.LiveTagChip
import com.example.domain.model.SearchSuggestionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

data class LivePageResult(
    val items: List<AppWallpaper>,
    val hasMore: Boolean,
    val totalCount: Int,
)

/**
 * Coordinates the jsDelivr + Cloudflare R2 Live Wallpaper pipeline:
 * 1. Grid: [LiveWallpaperItem.thumbUrl] (~35 KB)
 * 2. Detail Preview: [LiveWallpaperItem.previewUrl] (~350 KB)
 * 3. Apply / Save Offline: [LiveWallpaperItem.fullUrl] (~1–8 MB) cached once in filesDir/live_wallpapers/
 * 4. Dynamic Facets & Multi-Keyword Search across title, tags, category, and slug from live_wallpapers_v1.json.
 */
class LiveWallpaperRepository(
    private val api: LiveCatalogApi,
    private val downloader: LiveWallpaperDownloader,
) {
    constructor(context: Context) : this(
        api = NetworkClient.createLiveCatalogApi(context),
        downloader = LiveWallpaperDownloader(context),
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
    }

    private val catalogMutex = Mutex()
    @Volatile
    private var memoryCatalog: List<LiveWallpaperItem> = emptyList()
    private val liveCoverUrlCache = ConcurrentHashMap<String, List<String>>()

    private val _catalogFacets = MutableStateFlow(LiveCatalogFacets())
    val catalogFacets: StateFlow<LiveCatalogFacets> = _catalogFacets.asStateFlow()

    suspend fun fetchLiveWallpapers(forceRefresh: Boolean = false): Result<List<LiveWallpaperItem>> =
        withContext(Dispatchers.IO) {
            if (!forceRefresh && memoryCatalog.isNotEmpty()) {
                if (_catalogFacets.value.totalWallpapers != memoryCatalog.size) {
                    _catalogFacets.value = extractCatalogFacets(memoryCatalog)
                }
                return@withContext Result.success(memoryCatalog)
            }
            catalogMutex.withLock {
                if (!forceRefresh && memoryCatalog.isNotEmpty()) {
                    if (_catalogFacets.value.totalWallpapers != memoryCatalog.size) {
                        _catalogFacets.value = extractCatalogFacets(memoryCatalog)
                    }
                    return@withLock Result.success(memoryCatalog)
                }
                try {
                    val response = fetchCatalogWithFallback()
                    val rawList = response.wallpapers.orEmpty()
                    val items = rawList.filter { item ->
                        !item.id.isNullOrBlank() &&
                                (!item.thumbUrl.isNullOrBlank() || !item.previewUrl.isNullOrBlank()) &&
                                !item.fullUrl.isNullOrBlank()
                    }
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
                        memoryCatalog = items
                        liveCoverUrlCache.clear()
                        _catalogFacets.value = extractCatalogFacets(items)
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

    private suspend fun fetchCatalogWithFallback(): LiveCatalogResponse {
        return try {
            api.getLiveCatalog()
        } catch (e: CancellationException) {
            throw e
        } catch (primaryError: Exception) {
            runCatching {
                Log.e(
                    DEBUG_TAG,
                    "jsDelivr getLiveCatalog failed (${primaryError.javaClass.simpleName}): ${primaryError.message}. Retrying GitHub Raw fallback...",
                    primaryError,
                )
            }
            try {
                api.getLiveCatalogByUrl(LiveCatalogApi.GITHUB_RAW_FALLBACK_URL)
            } catch (e: CancellationException) {
                throw e
            } catch (fallbackError: Exception) {
                runCatching {
                    Log.e(
                        DEBUG_TAG,
                        "GitHub Raw fallback failed (${fallbackError.javaClass.simpleName}): ${fallbackError.message}",
                        fallbackError,
                    )
                }
                throw primaryError
            }
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

        val filtered = filterAndRankItems(items, query)

        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 50)
        val totalCount = filtered.size
        val fromIndex = (safePage - 1) * safeSize
        if (fromIndex >= totalCount) {
            return Result.success(
                LivePageResult(
                    items = emptyList(),
                    hasMore = false,
                    totalCount = totalCount,
                ),
            )
        }
        val toIndex = minOf(fromIndex + safeSize, totalCount)
        val pageItems = filtered.subList(fromIndex, toIndex).map { it.toAppWallpaper() }
        return Result.success(
            LivePageResult(
                items = pageItems,
                hasMore = toIndex < totalCount,
                totalCount = totalCount,
            ),
        )
    }

    fun getCachedCatalogSnapshot(
        query: String,
        page: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): LivePageResult? {
        val items = memoryCatalog
        if (items.isEmpty()) return null
        val filtered = filterAndRankItems(items, query)
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

        // 2. Matching #tags second
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

        // 3. Matching wallpaper titles third
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

    internal fun extractCatalogFacets(items: List<LiveWallpaperItem>): LiveCatalogFacets {
        if (items.isEmpty()) return LiveCatalogFacets()

        val categoryCounts = LinkedHashMap<String, Triple<String, Int, String?>>()
        val tagCounts = LinkedHashMap<String, Pair<String, Int>>()

        items.forEach { item ->
            val rawCat = item.category?.trim().orEmpty()
            val catKey = normalize(rawCat)
            val thumb = item.toAppWallpaper().thumbnailUrl.takeIf { it.isNotBlank() }
            if (rawCat.isNotEmpty() && catKey.isNotEmpty()) {
                val existing = categoryCounts[catKey]
                categoryCounts[catKey] = if (existing == null) {
                    Triple(rawCat, 1, thumb)
                } else {
                    Triple(existing.first, existing.second + 1, existing.third ?: thumb)
                }
            }

            // Deduplicate tags within a single item so counts represent distinct wallpapers
            val seenTagsInItem = HashSet<String>()
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
                }
            }
        }

        val categoryChips = categoryCounts.values
            .sortedWith(compareByDescending<Triple<String, Int, String?>> { it.second }.thenBy { it.first.lowercase() })
            .map { (label, count, coverUrl) ->
                LiveTagChip(
                    label = label,
                    query = label,
                    count = count,
                    isCategory = true,
                    coverUrl = coverUrl,
                )
            }

        val tagChips = tagCounts.entries
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
                    isCategory = false,
                )
            }

        return LiveCatalogFacets(
            categories = categoryChips,
            tags = tagChips,
            totalWallpapers = items.size,
        )
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

    internal fun filterAndRankItems(
        items: List<LiveWallpaperItem>,
        rawQuery: String,
    ): List<LiveWallpaperItem> {
        val cleaned = rawQuery.trim().removePrefix("#").trim()
        val collapsedQuery = normalize(cleaned)
        val isAllQuery = collapsedQuery.isEmpty() ||
                collapsedQuery == "hometrending" ||
                collapsedQuery == "anime" ||
                collapsedQuery == "all"

        if (isAllQuery) return items

        val queryTokens = tokenize(cleaned)

        // Pass 1: Strict match (exact category/tag, full phrase, multi-keyword AND across title+tags+category+slug, or exact alias)
        val primaryMatches = items.mapNotNull { item ->
            val score = scoreMatch(item, collapsedQuery, queryTokens)
            if (score > 0) item to score else null
        }
            .sortedByDescending { it.second }
            .map { it.first }

        if (primaryMatches.isNotEmpty()) {
            return primaryMatches
        }

        // Pass 2: Official long series-title fallback (e.g. "Frieren: Beyond Journey's End" -> category "Frieren")
        // Only runs when strict pass returns 0 results so multi-keyword AND queries stay precise.
        return items.mapNotNull { item ->
            val score = scoreSeriesFallback(item, collapsedQuery)
            if (score > 0) item to score else null
        }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    /**
     * Scores how well [item] strictly matches [collapsedQuery] and multi-keyword [queryTokens]
     * across title, tags, category, slug, and id.
     * Returns 0 if not a match; higher score means higher relevance.
     */
    internal fun scoreMatch(
        item: LiveWallpaperItem,
        collapsedQuery: String,
        queryTokens: List<String>,
    ): Int {
        val rawCategory = item.category.orEmpty()
        val rawTitle = item.title.orEmpty()
        val rawSlug = item.slug.orEmpty()
        val rawId = item.id.orEmpty()
        val rawTags = item.tags.orEmpty()

        val catCollapsed = normalize(rawCategory)
        val titleCollapsed = normalize(rawTitle)
        val slugCollapsed = normalize(rawSlug)
        val idCollapsed = normalize(rawId)
        val tagCollapsedList = rawTags.map { normalize(it) }.filter { it.isNotEmpty() }

        // 1. Exact category or exact tag match (highest priority)
        if (catCollapsed == collapsedQuery || tagCollapsedList.any { it == collapsedQuery }) {
            return 100
        }

        // 2. Direct full-query substring match in title, category, tags, slug, or id
        if (titleCollapsed.contains(collapsedQuery)) return 90
        if (catCollapsed.contains(collapsedQuery)) return 85
        if (tagCollapsedList.any { it.contains(collapsedQuery) }) return 80
        if (slugCollapsed.contains(collapsedQuery) || idCollapsed.contains(collapsedQuery)) return 75

        // 3. Multi-keyword AND match across combined fields (title + category + tags + slug)
        if (queryTokens.isNotEmpty()) {
            val fieldTokens = HashSet<String>().apply {
                addAll(tokenize(rawTitle))
                addAll(tokenize(rawCategory))
                addAll(tokenize(rawSlug))
                rawTags.forEach { addAll(tokenize(it)) }
            }
            val combinedCollapsed = buildString {
                append(titleCollapsed)
                append(' ')
                append(catCollapsed)
                append(' ')
                append(slugCollapsed)
                tagCollapsedList.forEach {
                    append(' ')
                    append(it)
                }
            }

            val allTokensMatched = queryTokens.all { qTok ->
                fieldTokens.any { fTok -> fTok.contains(qTok) } ||
                        combinedCollapsed.contains(qTok) ||
                        matchesExactSynonym(qTok, catCollapsed, titleCollapsed, tagCollapsedList)
            }
            if (allTokensMatched) {
                return 65
            }
        }

        // 4. Exact full-phrase synonym/abbreviation match (e.g., "Attack on Titan" -> "aot", "Kimetsu no Yaiba" -> "Demon Slayer")
        if (matchesExactSynonym(collapsedQuery, catCollapsed, titleCollapsed, tagCollapsedList)) {
            return 60
        }

        return 0
    }

    private fun scoreSeriesFallback(
        item: LiveWallpaperItem,
        collapsedQuery: String,
    ): Int {
        val catCollapsed = normalize(item.category.orEmpty())
        val titleCollapsed = normalize(item.title.orEmpty())
        val tagCollapsedList = item.tags.orEmpty().map { normalize(it) }.filter { it.isNotEmpty() }

        if (catCollapsed.length >= 4 && collapsedQuery.contains(catCollapsed)) {
            return 55
        }
        if (matchesLooseSynonym(collapsedQuery, catCollapsed, titleCollapsed, tagCollapsedList)) {
            return 45
        }
        return 0
    }

    private fun matchesExactSynonym(
        queryKey: String,
        catCollapsed: String,
        titleCollapsed: String,
        tagCollapsedList: List<String>,
    ): Boolean {
        if (queryKey.length < 2) return false
        return ANIME_SYNONYM_GROUPS.any { group ->
            val queryInGroup = group.any { alias -> queryKey == alias }
            if (!queryInGroup) return@any false
            group.any { alias ->
                catCollapsed.contains(alias) ||
                        titleCollapsed.contains(alias) ||
                        tagCollapsedList.any { it.contains(alias) }
            }
        }
    }

    private fun matchesLooseSynonym(
        queryKey: String,
        catCollapsed: String,
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
