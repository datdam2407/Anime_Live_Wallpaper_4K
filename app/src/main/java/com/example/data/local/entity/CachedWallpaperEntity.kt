package com.example.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "cached_wallpapers",
    primaryKeys = ["cacheKey", "wallpaperId"],
)
data class CachedWallpaperEntity(
    val cacheKey: String,
    val wallpaperId: String,
    val title: String,
    val animeSeries: String,
    val imageUrl: String,
    val thumbnailUrl: String,
    val videoUrl: String?,
    val isLive: Boolean,
    val width: Int,
    val height: Int,
    val source: String,
    val positionIndex: Int,
)
