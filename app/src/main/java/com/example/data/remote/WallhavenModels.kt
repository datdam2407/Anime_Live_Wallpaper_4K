package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class WallhavenResponse(
    val data: List<WallhavenItem> = emptyList(),
    val meta: WallhavenMeta? = null,
)

@JsonClass(generateAdapter = true)
data class WallhavenMeta(
    @Json(name = "current_page") val currentPage: Int = 1,
    @Json(name = "last_page") val lastPage: Int = 1,
    @Json(name = "per_page") val perPage: Int = 24,
    val total: Int = 0,
)

@JsonClass(generateAdapter = true)
data class WallhavenItem(
    val id: String,
    val url: String? = null,
    val path: String,
    @Json(name = "dimension_x") val dimensionX: Int = 1080,
    @Json(name = "dimension_y") val dimensionY: Int = 1920,
    val category: String? = null,
    val purity: String? = null,
    val ratio: String? = null,
    val thumbs: WallhavenThumbs,
)

@JsonClass(generateAdapter = true)
data class WallhavenThumbs(
    val large: String? = null,
    val original: String,
    val small: String? = null,
)
