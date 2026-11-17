package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_wallpapers")
data class FavoriteWallpaperEntity(
    @PrimaryKey val id: String,
    val title: String,
    val animeSeries: String,
    val imageUrl: String,
    val thumbnailUrl: String,
    val videoUrl: String?,
    val isLive: Boolean,
    val width: Int,
    val height: Int,
    val source: String,
    val savedAt: Long = System.currentTimeMillis(),
)
