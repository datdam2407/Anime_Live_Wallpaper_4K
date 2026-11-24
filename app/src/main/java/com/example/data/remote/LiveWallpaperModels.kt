package com.example.data.remote

import com.example.domain.model.AppWallpaper
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class LiveCatalogResponse(
    @Json(name = "version") val version: Int? = 1,
    @Json(name = "updatedAt") val updatedAt: String? = "",
    @Json(name = "total") val total: Int? = 0,
    @Json(name = "wallpapers") val wallpapers: List<LiveWallpaperItem>? = emptyList(),
)

@JsonClass(generateAdapter = true)
data class LiveWallpaperItem(
    @Json(name = "id") val id: String? = null,
    @Json(name = "slug") val slug: String? = "",
    @Json(name = "title") val title: String? = "",
    @Json(name = "category") val category: String? = "",
    @Json(name = "series") val series: String? = null,
    @Json(name = "tags") val tags: List<String>? = emptyList(),
    @Json(name = "thumbUrl") val thumbUrl: String? = null,
    @Json(name = "previewUrl") val previewUrl: String? = null,
    @Json(name = "fullUrl") val fullUrl: String? = null,
    @Json(name = "sizeMb") val sizeMb: Double? = 0.0,
) {
    /**
     * Maps the 3-tier R2 media URLs to their exact UI roles:
     * - Grid thumbnail: [thumbUrl] (~35 KB)
     * - Detail preview loop: [previewUrl] (~350 KB)
     * - Apply / offline download: [fullUrl] (~1–8 MB)
     */
    fun toAppWallpaper(): AppWallpaper {
        val safeId = id?.trim().orEmpty().ifEmpty { slug?.trim().orEmpty().ifEmpty { "live_${hashCode()}" } }
        val safeThumb = normalizeMediaUrl(thumbUrl).orEmpty()
        val safeFull = normalizeMediaUrl(fullUrl)
        val safePreview = normalizeMediaUrl(previewUrl) ?: safeFull
        val safeCategory = category?.trim().orEmpty().ifEmpty { "Anime Live" }
        val safeSeries = series?.trim()?.takeIf { it.isNotEmpty() } ?: safeCategory
        val safeTitle = title?.trim().orEmpty().ifEmpty { safeSeries }

        return AppWallpaper(
            id = safeId,
            title = safeTitle,
            animeSeries = safeSeries,
            imageUrl = safeThumb,
            thumbnailUrl = safeThumb,
            videoUrl = safeFull,
            previewVideoUrl = safePreview,
            isLive = true,
            width = 1080,
            height = 1920,
            source = "R2 Live",
            tags = tags.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
            sizeMb = sizeMb,
        )
    }

    companion object {
        internal fun normalizeMediaUrl(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val withScheme = if (trimmed.startsWith("http://", ignoreCase = true)) {
                "https://" + trimmed.substring(7)
            } else {
                trimmed
            }
            val schemeIdx = withScheme.indexOf("://")
            if (schemeIdx <= 0) return withScheme
            val scheme = withScheme.substring(0, schemeIdx + 3)
            val rest = withScheme.substring(schemeIdx + 3).replace(Regex("/{2,}"), "/")
            return scheme + rest
        }
    }
}
