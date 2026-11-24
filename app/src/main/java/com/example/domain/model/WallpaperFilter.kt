package com.example.domain.model

enum class ResolutionTier(
    val label: String,
    val atleastParam: String?,
    val minWidth: Int,
    val minHeight: Int,
) {
    ALL("All Quality", null, 0, 0),
    FHD_1080P("FHD 1080p+", "1080x1920", 1080, 1920),
    QHD_2K("2K 1440p+", "1440x2560", 1440, 2560),
    UHD_4K("4K 2160p+", "2160x3840", 2160, 3840);

    /**
     * Wallhaven's `atleast=WxH` requires BOTH width >= W and height >= H.
     * When [ratio] is [RatioFilter.ALL], use a square short-edge threshold (e.g. `1440x1440`)
     * so BOTH portrait (1440x2560) and landscape (2560x1440) high-res artworks match cleanly.
     */
    fun wallhavenAtleast(ratio: RatioFilter): String? = when (this) {
        ALL -> null
        FHD_1080P -> if (ratio == RatioFilter.ALL) "1080x1080" else atleastParam
        QHD_2K -> if (ratio == RatioFilter.ALL) "1440x1440" else atleastParam
        UHD_4K -> if (ratio == RatioFilter.ALL) "2160x2160" else atleastParam
    }
}

enum class SortOption(
    val label: String,
    val wallhavenSorting: String,
    val redditSort: String,
) {
    RELEVANCE("Best Match", "relevance", "relevance"),
    TOPLIST("Toplist", "toplist", "top"),
    VIEWS("Most Viewed", "views", "hot"),
    FAVORITES("Most Saved", "favorites", "top"),
    RECENT("Latest", "date_added", "new"),
}

private const val DEFAULT_MOBILE_PORTRAIT_RATIOS = "9x16,10x16,9x18,18x39,9x20,9x21"

enum class RatioFilter(
    val label: String,
    val wallhavenRatios: String?,
) {
    PORTRAIT_MOBILE("Mobile Portrait (9:16–9:21)", DEFAULT_MOBILE_PORTRAIT_RATIOS),
    TALL_9_16("9:16 Only", "9x16"),
    CLASSIC_10_16("10:16 Only", "10x16"),
    ALL("All Ratios", null);

    companion object {
        /**
         * Wallhaven integer WxH aspect ratios matching modern mobile screens:
         * 9:16, 10:16, 9:18 (1:2), 9:19.5 (18:39), 9:20, 9:21.
         */
        const val MOBILE_PORTRAIT_RATIOS = DEFAULT_MOBILE_PORTRAIT_RATIOS
        const val FALLBACK_PORTRAIT_RATIO = "portrait"
    }
}

data class WallpaperFilter(
    val resolution: ResolutionTier = ResolutionTier.ALL,
    val sort: SortOption = SortOption.RELEVANCE,
    val ratio: RatioFilter = RatioFilter.PORTRAIT_MOBILE,
)

enum class WallpaperTarget(val label: String) {
    HOME("Home Screen"),
    LOCK("Lock Screen"),
    BOTH("Home & Lock Screen"),
}
