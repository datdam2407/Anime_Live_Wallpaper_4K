package com.example.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class LiveTagChip(
    val label: String,
    val query: String,
    val count: Int,
    val isCategory: Boolean,
    val coverUrl: String? = null,
)

@Immutable
data class LiveCatalogFacets(
    val categories: List<LiveTagChip> = emptyList(),
    val tags: List<LiveTagChip> = emptyList(),
    val totalWallpapers: Int = 0,
)

enum class SearchSuggestionType {
    CATEGORY,
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
data class CategoryCoverCandidate(
    val imageUrl: String,
    val isLive: Boolean,
    val liveKeyword: String? = null,
)
