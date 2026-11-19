package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "cached_wallpapers",
    primaryKeys = ["cacheKey", "wallpaperId"],
    indices = [
        Index(value = ["cacheKey"]),
        Index(value = ["category"]),
        Index(value = ["cachedAt"]),
    ],
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
    val category: String = "",
    val cachedAt: Long = System.currentTimeMillis(),
)
