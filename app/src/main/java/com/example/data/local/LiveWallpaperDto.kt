package com.example.data.local

import com.example.domain.model.AppWallpaper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LiveWallpaperItem(
    @SerialName("ID") val id: String,
    @SerialName("Image_live_url") val imageLiveUrl: String,
    @SerialName("Category") val category: String,
    @SerialName("Description") val description: String,
) {
    fun toAppWallpaper(): AppWallpaper {
        // Cloudinary automatically serves a fast JPEG poster when replacing .mp4 with .jpg
        val posterUrl = if (imageLiveUrl.endsWith(".mp4", ignoreCase = true)) {
            imageLiveUrl.dropLast(4) + ".jpg"
        } else {
            imageLiveUrl
        }
        return AppWallpaper(
            id = "mp4_$id",
            title = description,
            animeSeries = formatDisplayCategory(category),
            imageUrl = posterUrl,
            thumbnailUrl = posterUrl,
            videoUrl = imageLiveUrl,
            isLive = true,
            width = 1080,
            height = 1920,
            source = "Live MP4",
        )
    }

    private fun formatDisplayCategory(raw: String): String = when (raw.lowercase()) {
        "onepiece" -> "One Piece"
        "dragonball" -> "Dragon Ball"
        "demon-slayer" -> "Demon Slayer"
        "toro toro" -> "My Neighbor Totoro"
        "zero kara hajimeru isekai seikatsu" -> "Re:Zero"
        "solo leveling" -> "Solo Leveling"
        else -> raw
    }
}
