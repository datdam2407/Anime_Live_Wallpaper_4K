package com.example.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class AppWallpaper(
    val id: String,
    val title: String,
    val animeSeries: String,
    val imageUrl: String,
    val thumbnailUrl: String,
    val videoUrl: String? = null,
    val isLive: Boolean = false,
    val width: Int = 1080,
    val height: Int = 1920,
    val source: String,
    val isFavorite: Boolean = false,
) {
    val resolutionLabel: String
        get() {
            val maxDim = maxOf(width, height)
            val minDim = minOf(width, height)
            return when {
                isLive -> "MP4 LIVE"
                maxDim >= 3840 || minDim >= 2160 -> "4K UHD"
                maxDim >= 2560 || minDim >= 1440 -> "2K QHD"
                maxDim >= 1920 && minDim >= 1080 -> "FHD"
                else -> "HD"
            }
        }

    val aspectRatio: Float
        get() {
            if (width <= 0 || height <= 0) return 0.65f
            return (width.toFloat() / height.toFloat()).coerceIn(0.48f, 1.15f)
        }
}
