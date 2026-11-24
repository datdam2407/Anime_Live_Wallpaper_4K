package com.example.domain.model

import androidx.compose.runtime.Immutable

enum class FacetType {
    CATEGORY,
    SERIES,
    TAG,
}

@Immutable
data class LiveTagChip(
    val label: String,
    val query: String,
    val count: Int,
    val type: FacetType = FacetType.TAG,
    val coverUrl: String? = null,
) {
    /** Backward-compatible property for callers checking category vs tag chips. */
    val isCategory: Boolean
        get() = type == FacetType.CATEGORY

    /** Backward-compatible secondary constructor for callers passing `isCategory = true/false`. */
    constructor(
        label: String,
        query: String,
        count: Int,
        isCategory: Boolean,
        coverUrl: String? = null,
    ) : this(
        label = label,
        query = query,
        count = count,
        type = if (isCategory) FacetType.CATEGORY else FacetType.TAG,
        coverUrl = coverUrl,
    )
}

@Immutable
data class LiveCatalogFacets(
    val categories: List<LiveTagChip> = emptyList(),
    val series: List<LiveTagChip> = emptyList(),
    val tags: List<LiveTagChip> = emptyList(),
    /** Distinct, overlap-deduplicated tags ready for chip display (rotated on refresh). */
    val displayTags: List<LiveTagChip> = emptyList(),
    /** Map of lowercase(category) -> top contextual tags belonging strictly to that category. */
    val tagsByCategory: Map<String, List<LiveTagChip>> = emptyMap(),
    /** Map of lowercase(category) -> overlap-deduplicated display tags for that category. */
    val displayTagsByCategory: Map<String, List<LiveTagChip>> = emptyMap(),
    val totalWallpapers: Int = 0,
    val tagRotationVersion: Int = 0,
) {
    /** Returns contextual tags with count > 0 for [selectedCategory], never leaking tags from other categories. */
    fun tagsFor(selectedCategory: String?): List<LiveTagChip> {
        val key = selectedCategory?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return tags.filter { it.count > 0 }
        val scoped = tagsByCategory[key].orEmpty().filter { it.count > 0 }
        return if (totalWallpapers > 0) scoped else scoped.ifEmpty { tags.filter { it.count > 0 } }
    }

    /** Returns overlap-deduplicated display tags with count > 0 strictly within [selectedCategory]. */
    fun displayTagsFor(selectedCategory: String?): List<LiveTagChip> {
        val key = selectedCategory?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) {
            return displayTags.filter { it.count > 0 }
                .ifEmpty { tags.filter { it.count > 0 } }
        }
        val scoped = displayTagsByCategory[key].orEmpty().filter { it.count > 0 }
            .ifEmpty { tagsByCategory[key].orEmpty().filter { it.count > 0 } }
        return if (totalWallpapers > 0) {
            scoped
        } else {
            scoped
                .ifEmpty { displayTags.filter { it.count > 0 } }
                .ifEmpty { tags.filter { it.count > 0 } }
        }
    }
}

enum class SearchSuggestionType {
    CATEGORY,
    SERIES,
    TAG,
    TITLE,
}

@Immutable
data class LiveSearchSuggestion(
    val label: String,
    val query: String,
    val type: SearchSuggestionType,
    val count: Int = 0,
)

@Immutable
data class LiveFilterState(
    val query: String = "",
    val selectedCategory: String? = null,
    val selectedSeries: String? = null,
    /** Clean tag queries (without leading '#'), stored in lowercase (capped at 1 active tag). */
    val selectedTags: Set<String> = emptySet(),
    val isColorSearch: Boolean = false,
) {
    val isHome: Boolean
        get() = query.isBlank() &&
                selectedCategory == null &&
                selectedSeries == null &&
                selectedTags.isEmpty()

    /** True whenever the user is filtering by Category and/or Tag chips (used to hide paginated match counts). */
    val isChipFilterActive: Boolean
        get() = selectedCategory != null || selectedTags.isNotEmpty()

    /**
     * Allows combining at most 1 Category + 1 Tag.
     * - Re-tapping the active Category toggles off ONLY the Category (preserving any active Tag).
     * - Selecting a new Category preserves a single active Tag unless [clearTagIfSwitchingCategory] is true.
     */
    fun toggleCategory(
        category: String,
        clearTagIfSwitchingCategory: Boolean = false,
    ): LiveFilterState {
        val cleaned = category.trim()
        if (cleaned.isEmpty()) return LiveFilterState()
        val isSameCategory = selectedCategory.equals(cleaned, ignoreCase = true)
        return if (isSameCategory) {
            LiveFilterState(selectedTags = selectedTags.take(1).toSet())
        } else {
            val nextTags = if (clearTagIfSwitchingCategory && selectedCategory != null) {
                emptySet()
            } else {
                selectedTags.take(1).toSet()
            }
            LiveFilterState(
                selectedCategory = cleaned,
                selectedTags = nextTags,
            )
        }
    }

    fun toggleSeries(series: String): LiveFilterState {
        val cleaned = series.trim()
        if (cleaned.isEmpty()) return LiveFilterState()
        val isSameOnlyChip = selectedSeries.equals(cleaned, ignoreCase = true) &&
                selectedCategory == null &&
                selectedTags.isEmpty() &&
                query.isBlank()
        return if (isSameOnlyChip) {
            LiveFilterState()
        } else {
            LiveFilterState(selectedSeries = cleaned)
        }
    }

    /**
     * Allows combining at most 1 Category + 1 Tag.
     * - Strictly limits active tags to a single tag (tapping another tag replaces the previous tag).
     * - Preserves the active [selectedCategory] / [selectedSeries].
     * - Re-tapping the currently active tag toggles off ONLY the tag.
     */
    fun toggleTag(rawTag: String): LiveFilterState {
        val clean = rawTag.trim().removePrefix("#").trim().lowercase()
        if (clean.isEmpty()) return copy(selectedTags = emptySet())
        val isSameTag = selectedTags.size == 1 && clean in selectedTags
        val nextTags = if (isSameTag) emptySet() else setOf(clean)
        return LiveFilterState(
            selectedCategory = selectedCategory,
            selectedSeries = selectedSeries,
            selectedTags = nextTags,
        )
    }

    /** Text kept visible in the top search bar matching the active chip or query. */
    fun displayInputText(): String = when {
        !selectedCategory.isNullOrBlank() && selectedTags.isNotEmpty() ->
            "${selectedCategory.trim()} #${selectedTags.first()}"
        !selectedCategory.isNullOrBlank() -> selectedCategory.trim()
        !selectedSeries.isNullOrBlank() -> selectedSeries.trim()
        selectedTags.isNotEmpty() -> "#${selectedTags.first()}"
        query.isNotBlank() -> query.trim()
        else -> ""
    }

    /** Human-readable summary for ActiveFilterStatusBar, e.g. "Games · #dark", "Zenless Zone Zero". */
    fun summaryLabel(): String = buildList {
        selectedCategory?.takeIf { it.isNotBlank() }?.let { add(it) }
        selectedSeries?.takeIf { it.isNotBlank() }?.let { add(it) }
        selectedTags.forEach { add("#$it") }
        query.trim().takeIf { it.isNotEmpty() }?.let { add("\"$it\"") }
    }.joinToString(" · ")

    /** Deterministic key for Room caching and pagination identity. */
    fun cacheKey(): String {
        if (isHome) return "Home_Trending"
        val catPart = selectedCategory?.trim()?.lowercase().orEmpty()
        val seriesPart = selectedSeries?.trim()?.lowercase().orEmpty()
        val tagsPart = selectedTags.sorted().joinToString(",")
        val qPart = query.trim().lowercase()
        return "cat=$catPart|ser=$seriesPart|tags=$tagsPart|q=$qPart|color=$isColorSearch"
    }
}

/**
 * Faceted filter state for Static 4K Artworks supporting:
 * - At most 1 Category AND at most 1 Tag simultaneously (replacing any previous Tag on tap).
 * - Independent free-text search query.
 */
@Immutable
data class StaticFilterState(
    val selectedCategory: String? = null,
    /** Single active tag without leading '#'. */
    val selectedTag: String? = null,
    val searchQuery: String = "",
) {
    val isHome: Boolean
        get() = selectedCategory == null && selectedTag == null && searchQuery.isBlank()

    /** True whenever filtering via Category and/or Tag chips (hides "X matches" bar). */
    val isChipFilterActive: Boolean
        get() = selectedCategory != null || selectedTag != null

    val isSearchQueryOnly: Boolean
        get() = searchQuery.isNotBlank() && selectedCategory == null && selectedTag == null

    /**
     * Toggles a Category chip:
     * - Re-tapping the active Category turns off ONLY the Category (preserving any active Tag).
     * - Selecting a Category when none was active preserves any active Tag so both combine.
     * - Switching from Category A to Category B clears a Category-A-specific Tag unless [preserveTag] is true.
     */
    fun toggleCategory(
        category: String,
        preserveTagWhenSwitching: Boolean = false,
    ): StaticFilterState {
        val cleaned = category.trim()
        if (cleaned.isEmpty()) return StaticFilterState()
        return if (selectedCategory.equals(cleaned, ignoreCase = true)) {
            StaticFilterState(
                selectedCategory = null,
                selectedTag = selectedTag,
            )
        } else {
            val nextTag = if (selectedCategory != null && !preserveTagWhenSwitching) {
                null
            } else {
                selectedTag
            }
            StaticFilterState(
                selectedCategory = cleaned,
                selectedTag = nextTag,
            )
        }
    }

    /**
     * Toggles a single Tag chip while keeping [selectedCategory] intact:
     * - Re-tapping the active Tag clears ONLY the Tag.
     * - Tapping a different Tag replaces the previous Tag (never more than 1 Tag active).
     */
    fun toggleTag(rawTag: String): StaticFilterState {
        val clean = rawTag.trim().removePrefix("#").trim()
        if (clean.isEmpty()) return copy(selectedTag = null)
        val isSameTag = selectedTag.equals(clean, ignoreCase = true)
        return StaticFilterState(
            selectedCategory = selectedCategory,
            selectedTag = if (isSameTag) null else clean,
        )
    }

    /** Human-readable display label (e.g., "Anime · #Kawaii", "Anime", "#Cyberpunk"). */
    fun summaryLabel(): String = buildList {
        selectedCategory?.takeIf { it.isNotBlank() }?.let { add(it) }
        selectedTag?.takeIf { it.isNotBlank() }?.let { add("#$it") }
        searchQuery.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(" · ")

    /** Combined query identifier passed into WallpaperRepository / cache keys. */
    fun toQueryKey(homeTrendingKey: String = "Home_Trending"): String = when {
        searchQuery.isNotBlank() -> searchQuery.trim()
        selectedCategory != null && selectedTag != null -> "${selectedCategory.trim()} ${selectedTag.trim()}"
        selectedCategory != null -> selectedCategory.trim()
        selectedTag != null -> selectedTag.trim()
        else -> homeTrendingKey
    }

    /** Deterministic cache key discriminator so Category+Tag never collides with plain text searches. */
    fun cacheKeyPart(): String? {
        if (selectedCategory == null && selectedTag == null) return null
        val cat = selectedCategory?.trim()?.lowercase().orEmpty()
        val tag = selectedTag?.trim()?.lowercase().orEmpty()
        return "sf_cat=${cat}_tag=$tag"
    }
}

@Immutable
data class CategoryCoverCandidate(
    val imageUrl: String,
    val isLive: Boolean,
    val liveKeyword: String? = null,
)

@Immutable
data class SyncedTabFilters(
    val liveFilter: LiveFilterState,
    val staticFilter: StaticFilterState,
    val searchInput: String,
    val activeQueryForLive: String,
    val activeQueryForStatic: String,
)

/**
 * Single Source of Truth helper that synchronizes search state across both Live Wallpapers
 * and 4K Artworks tabs whenever the user triggers a search from SearchScreen, HomeScreen search bar,
 * Anime 200+, or Trending banners.
 */
object CrossTabSearchSync {
    const val DEFAULT_HOME_KEY = "Home_Trending"

    fun syncFromSearch(
        rawQuery: String,
        isColorSearch: Boolean = false,
        facetType: FacetType? = null,
        matchedExploreCategoryTitle: String? = null,
        homeTrendingKey: String = DEFAULT_HOME_KEY,
    ): SyncedTabFilters {
        val raw = rawQuery.trim()
        val cleaned = raw.removePrefix("#").trim()
        if (cleaned.isEmpty() ||
            cleaned.equals(homeTrendingKey, ignoreCase = true) ||
            cleaned.equals("all", ignoreCase = true)
        ) {
            return SyncedTabFilters(
                liveFilter = LiveFilterState(),
                staticFilter = StaticFilterState(),
                searchInput = "",
                activeQueryForLive = homeTrendingKey,
                activeQueryForStatic = homeTrendingKey,
            )
        }

        val liveFilter = when {
            facetType == FacetType.CATEGORY -> LiveFilterState(selectedCategory = cleaned)
            facetType == FacetType.SERIES -> LiveFilterState(selectedSeries = cleaned)
            facetType == FacetType.TAG -> LiveFilterState(selectedTags = setOf(cleaned.lowercase()))
            else -> LiveFilterState(query = cleaned, isColorSearch = isColorSearch)
        }

        val staticFilter = when {
            matchedExploreCategoryTitle != null && facetType == FacetType.CATEGORY ->
                StaticFilterState(selectedCategory = matchedExploreCategoryTitle)
            matchedExploreCategoryTitle != null && facetType == null && !isColorSearch ->
                StaticFilterState(selectedCategory = matchedExploreCategoryTitle)
            facetType == FacetType.TAG -> StaticFilterState(selectedTag = cleaned)
            else -> StaticFilterState(searchQuery = cleaned)
        }

        val displaySearchInput = when {
            facetType == FacetType.CATEGORY -> ""
            facetType == FacetType.TAG -> "#$cleaned"
            else -> cleaned
        }

        return SyncedTabFilters(
            liveFilter = liveFilter,
            staticFilter = staticFilter,
            searchInput = displaySearchInput,
            activeQueryForLive = liveFilter.displayInputText().removePrefix("#").ifEmpty { homeTrendingKey },
            activeQueryForStatic = staticFilter.toQueryKey(homeTrendingKey),
        )
    }

    fun syncOnTabSwitch(
        targetIsLive: Boolean,
        currentLiveFilter: LiveFilterState,
        currentStaticFilter: StaticFilterState,
        searchInput: String,
        isColorSearch: Boolean = false,
        homeTrendingKey: String = DEFAULT_HOME_KEY,
    ): SyncedTabFilters {
        val cleanedInput = searchInput.trim().removePrefix("#").trim()
        val isTagInput = searchInput.trim().startsWith("#")

        val resolvedLive = if (targetIsLive && cleanedInput.isNotEmpty()) {
            val currentLiveDisplay = currentLiveFilter.displayInputText().removePrefix("#").trim()
            if (currentLiveDisplay.equals(cleanedInput, ignoreCase = true)) {
                currentLiveFilter
            } else if (isTagInput) {
                LiveFilterState(selectedTags = setOf(cleanedInput.lowercase()))
            } else {
                LiveFilterState(query = cleanedInput, isColorSearch = isColorSearch)
            }
        } else {
            currentLiveFilter
        }

        val resolvedStatic = if (!targetIsLive && cleanedInput.isNotEmpty()) {
            val currentStaticKey = currentStaticFilter.toQueryKey(homeTrendingKey)
            if (currentStaticKey.equals(cleanedInput, ignoreCase = true) ||
                currentStaticFilter.selectedTag.equals(cleanedInput, ignoreCase = true) ||
                currentStaticFilter.selectedCategory.equals(cleanedInput, ignoreCase = true)
            ) {
                currentStaticFilter
            } else if (isTagInput) {
                StaticFilterState(selectedTag = cleanedInput)
            } else {
                StaticFilterState(searchQuery = cleanedInput)
            }
        } else {
            currentStaticFilter
        }

        return SyncedTabFilters(
            liveFilter = resolvedLive,
            staticFilter = resolvedStatic,
            searchInput = searchInput,
            activeQueryForLive = resolvedLive.displayInputText().removePrefix("#").ifEmpty { homeTrendingKey },
            activeQueryForStatic = resolvedStatic.toQueryKey(homeTrendingKey),
        )
    }
}
