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
    UHD_4K("4K 2160p+", "2160x3840", 2160, 3840),
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

enum class RatioFilter(
    val label: String,
    val wallhavenRatios: String?,
) {
    ALL("All Ratios", null),
    PORTRAIT_MOBILE("Portrait (9:16, 10:16)", "9x16,10x16,portrait"),
    TALL_9_16("9:16 Only", "9x16"),
    CLASSIC_10_16("10:16 Only", "10x16"),
}

data class WallpaperFilter(
    val resolution: ResolutionTier = ResolutionTier.ALL,
    val sort: SortOption = SortOption.RELEVANCE,
    val ratio: RatioFilter = RatioFilter.ALL,
)

enum class WallpaperTarget(val label: String) {
    HOME("Home Screen"),
    LOCK("Lock Screen"),
    BOTH("Home & Lock Screen"),
}
