package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class StaticCatalogResponse(
    val version: Int? = 1,
    val total: Int? = 0,
    val items: List<StaticCatalogItemDto>? = null,
    val wallpapers: List<StaticCatalogItemDto>? = null,
    val data: List<StaticCatalogItemDto>? = null,
) {
    fun resolvedItems(): List<StaticCatalogItemDto> {
        return items?.takeIf { it.isNotEmpty() }
            ?: wallpapers?.takeIf { it.isNotEmpty() }
            ?: data?.takeIf { it.isNotEmpty() }
            ?: emptyList()
    }
}

@JsonClass(generateAdapter = true)
data class StaticCatalogItemDto(
    val id: String? = null,
    val slug: String? = null,
    val imageUrl: String? = null,
    @Json(name = "image_url") val imageUrlSnake: String? = null,
    val fullUrl: String? = null,
    @Json(name = "full_url") val fullUrlSnake: String? = null,
    val url: String? = null,
    val path: String? = null,
    val thumbnailUrl: String? = null,
    @Json(name = "thumbnail_url") val thumbnailUrlSnake: String? = null,
    val thumbUrl: String? = null,
    @Json(name = "thumb_url") val thumbUrlSnake: String? = null,
    val previewUrl: String? = null,
    val width: Int? = 1080,
    val height: Int? = 1920,
    val title: String? = null,
    val series: String? = null,
    val category: String? = null,
) {
    fun resolvedImageUrl(): String? = normalizeR2MediaUrl(
        imageUrl ?: imageUrlSnake ?: fullUrl ?: fullUrlSnake ?: url ?: path,
    )

    fun resolvedThumbnailUrl(): String? = normalizeR2MediaUrl(
        thumbnailUrl ?: thumbnailUrlSnake ?: thumbUrl ?: thumbUrlSnake ?: previewUrl,
    ) ?: resolvedImageUrl()

    fun resolvedId(): String {
        val candidate = id?.trim().orEmpty().ifEmpty { slug?.trim().orEmpty() }
        if (candidate.isNotEmpty()) return candidate
        val fallbackUrl = resolvedImageUrl().orEmpty()
        return if (fallbackUrl.isNotEmpty()) "r2_${fallbackUrl.hashCode().toUInt()}" else ""
    }

    companion object {
        internal fun normalizeR2MediaUrl(raw: String?): String? {
            val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val upgraded = if (trimmed.startsWith("http://", ignoreCase = true)) {
                "https://" + trimmed.substring(7)
            } else {
                trimmed
            }
            val schemeIdx = upgraded.indexOf("://")
            val collapsed = if (schemeIdx > 0) {
                val scheme = upgraded.substring(0, schemeIdx + 3)
                val rest = upgraded.substring(schemeIdx + 3).replace(Regex("/{2,}"), "/")
                scheme + rest
            } else {
                upgraded
            }
            return collapsed.replace(" ", "%20")
        }
    }
}
