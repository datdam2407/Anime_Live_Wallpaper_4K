package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class RedditResponse(
    val data: RedditListingData? = null,
)

@JsonClass(generateAdapter = true)
data class RedditListingData(
    val children: List<RedditChild> = emptyList(),
    val after: String? = null,
)

@JsonClass(generateAdapter = true)
data class RedditChild(
    val data: RedditPost,
)

@JsonClass(generateAdapter = true)
data class RedditPost(
    val id: String,
    val title: String = "",
    val url: String = "",
    val thumbnail: String = "",
    @Json(name = "post_hint") val postHint: String? = null,
    @Json(name = "is_self") val isSelf: Boolean = false,
    @Json(name = "over_18") val over18: Boolean = false,
    @Json(name = "is_video") val isVideo: Boolean = false,
    val preview: RedditPreview? = null,
    @Json(name = "secure_media") val secureMedia: RedditMedia? = null,
    val media: RedditMedia? = null,
)

@JsonClass(generateAdapter = true)
data class RedditPreview(
    val images: List<RedditPreviewImage> = emptyList(),
    @Json(name = "reddit_video_preview") val redditVideoPreview: RedditVideo? = null,
)

@JsonClass(generateAdapter = true)
data class RedditPreviewImage(
    val source: RedditImageSource? = null,
    val resolutions: List<RedditImageSource> = emptyList(),
    val variants: RedditVariants? = null,
)

@JsonClass(generateAdapter = true)
data class RedditImageSource(
    val url: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

@JsonClass(generateAdapter = true)
data class RedditVariants(
    val mp4: RedditMp4Variant? = null,
)

@JsonClass(generateAdapter = true)
data class RedditMp4Variant(
    val source: RedditImageSource? = null,
)

@JsonClass(generateAdapter = true)
data class RedditMedia(
    @Json(name = "reddit_video") val redditVideo: RedditVideo? = null,
)

@JsonClass(generateAdapter = true)
data class RedditVideo(
    @Json(name = "fallback_url") val fallbackUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    @Json(name = "is_gif") val isGif: Boolean = false,
)
