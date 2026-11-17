package com.example.data.local

import android.content.Context
import com.example.domain.model.AppWallpaper
import kotlinx.serialization.json.Json

data class LivePageResult(
    val items: List<AppWallpaper>,
    val hasMore: Boolean,
    val totalCount: Int,
)

class LiveWallpaperDataSource(
    private val jsonProvider: () -> String,
) {
    constructor(context: Context) : this(
        jsonProvider = {
            context.assets.open("live_wallpapers.json").bufferedReader().use { it.readText() }
        },
    )

    companion object {
        const val DEFAULT_PAGE_SIZE = 45
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val cachedItems: List<LiveWallpaperItem> by lazy {
        runCatching {
            json.decodeFromString<List<LiveWallpaperItem>>(jsonProvider())
        }.getOrDefault(emptyList())
    }

    fun getAllParsedItems(): List<LiveWallpaperItem> = cachedItems

    fun getLiveWallpapersPage(
        query: String,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): LivePageResult {
        val normalizedQuery = normalize(query)
        val isAllQuery = normalizedQuery.isEmpty() ||
                normalizedQuery == "hometrending" ||
                normalizedQuery == "anime"

        val filtered = if (isAllQuery) {
            cachedItems
        } else {
            cachedItems.filter { item ->
                matchesQuery(item, normalizedQuery)
            }
        }

        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 50)
        val totalCount = filtered.size
        val fromIndex = (safePage - 1) * safeSize
        if (fromIndex >= totalCount) {
            return LivePageResult(
                items = emptyList(),
                hasMore = false,
                totalCount = totalCount,
            )
        }
        val toIndex = minOf(fromIndex + safeSize, totalCount)
        val pageItems = filtered.subList(fromIndex, toIndex).map { it.toAppWallpaper() }
        return LivePageResult(
            items = pageItems,
            hasMore = toIndex < totalCount,
            totalCount = totalCount,
        )
    }

    private fun matchesQuery(item: LiveWallpaperItem, normalizedQuery: String): Boolean {
        val cat = normalize(item.category)
        val desc = normalize(item.description)
        val id = normalize(item.id)
        if (cat.contains(normalizedQuery) || desc.contains(normalizedQuery) || id.contains(normalizedQuery)) {
            return true
        }
        // Alias matching for common anime titles
        return when {
            normalizedQuery.contains("kimetsunoyaiba") && cat.contains("demonslayer") -> true
            normalizedQuery.contains("rezero") && cat.contains("zerokara") -> true
            normalizedQuery.contains("totoro") && (cat.contains("torotoro") || desc.contains("totoro")) -> true
            normalizedQuery == "jjk" && cat.contains("jujutsukaisen") -> true
            else -> false
        }
    }

    private fun normalize(value: String): String {
        return value.lowercase().replace(Regex("[^a-z0-9]"), "")
    }
}
