package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.WallpaperRepository
import com.example.domain.model.AnimeCatalog
import com.example.domain.model.LiveCatalogFacets
import com.example.domain.model.LiveSearchSuggestion
import com.example.domain.model.LiveTagChip
import com.example.domain.model.SearchSuggestionType
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.CyberCyanContainer
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkSubtle
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.PremiumGold
import com.example.ui.theme.PremiumGoldSurface
import com.kuroanimelivewallpapers.app.R

private val FALLBACK_LIVE_CATEGORIES = listOf(
    LiveTagChip(label = "Frieren", query = "Frieren", count = 0, isCategory = true),
    LiveTagChip(label = "Jujutsu Kaisen", query = "Jujutsu Kaisen", count = 0, isCategory = true),
    LiveTagChip(label = "Solo Leveling", query = "Solo Leveling", count = 0, isCategory = true),
    LiveTagChip(label = "Bleach", query = "Bleach", count = 0, isCategory = true),
    LiveTagChip(label = "Demon Slayer", query = "Demon Slayer", count = 0, isCategory = true),
    LiveTagChip(label = "Cyberpunk", query = "Cyberpunk", count = 0, isCategory = true),
)

private val FALLBACK_LIVE_TAGS = listOf(
    LiveTagChip(label = "#anime", query = "anime", count = 0, isCategory = false),
    LiveTagChip(label = "#4k", query = "4k", count = 0, isCategory = false),
    LiveTagChip(label = "#dark", query = "dark", count = 0, isCategory = false),
    LiveTagChip(label = "#magic", query = "magic", count = 0, isCategory = false),
    LiveTagChip(label = "#sword", query = "sword", count = 0, isCategory = false),
    LiveTagChip(label = "#neon", query = "neon", count = 0, isCategory = false),
    LiveTagChip(label = "#fantasy", query = "fantasy", count = 0, isCategory = false),
    LiveTagChip(label = "#cyberpunk", query = "cyberpunk", count = 0, isCategory = false),
    LiveTagChip(label = "#action", query = "action", count = 0, isCategory = false),
    LiveTagChip(label = "#aesthetic", query = "aesthetic", count = 0, isCategory = false),
)

val CURATED_STATIC_TAGS = listOf(
    LiveTagChip(label = "#Cyberpunk", query = "Cyberpunk", count = 0, isCategory = false),
    LiveTagChip(label = "#Dark", query = "Dark", count = 0, isCategory = false),
    LiveTagChip(label = "#Aesthetic", query = "Aesthetic", count = 0, isCategory = false),
    LiveTagChip(label = "#Samurai", query = "Samurai", count = 0, isCategory = false),
    LiveTagChip(label = "#Sakura", query = "Sakura", count = 0, isCategory = false),
    LiveTagChip(label = "#Neon", query = "Neon", count = 0, isCategory = false),
    LiveTagChip(label = "#Fantasy", query = "Fantasy", count = 0, isCategory = false),
    LiveTagChip(label = "#Minimal", query = "Minimal", count = 0, isCategory = false),
    LiveTagChip(label = "#Night", query = "Night", count = 0, isCategory = false),
    LiveTagChip(label = "#Magic", query = "Magic", count = 0, isCategory = false),
)

@Composable
fun SearchSuggestionStrip(
    suggestions: List<LiveSearchSuggestion>,
    onSelectSuggestion: (LiveSearchSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = suggestions.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_suggestions_row"),
        ) {
            itemsIndexed(
                items = suggestions,
                key = { _, item -> "${item.type.name}:${item.query}" },
            ) { index, suggestion ->
                val (icon, accentColor, containerColor) = when (suggestion.type) {
                    SearchSuggestionType.CATEGORY -> Triple(
                        Icons.Rounded.AutoAwesome,
                        PremiumGold,
                        PremiumGoldSurface,
                    )
                    SearchSuggestionType.TAG -> Triple(
                        Icons.Rounded.Tag,
                        CyberCyan,
                        CyberCyanContainer.copy(alpha = 0.65f),
                    )
                    SearchSuggestionType.TITLE -> Triple(
                        Icons.Rounded.Search,
                        InkMuted,
                        ObsidianSurfaceElevated,
                    )
                }

                Surface(
                    color = containerColor,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
                        .clickable { onSelectSuggestion(suggestion) }
                        .testTag("search_suggestion_$index"),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = suggestion.label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = InkWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (suggestion.count > 1) {
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "· ${suggestion.count}",
                                style = MaterialTheme.typography.labelSmall,
                                color = accentColor.copy(alpha = 0.88f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiveCatalogFilterSection(
    activeQuery: String,
    facets: LiveCatalogFacets,
    resultCount: Int?,
    onSelectTopic: (String) -> Unit,
    onClearFilter: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cleanedActive = remember(activeQuery) {
        activeQuery.trim().removePrefix("#").trim()
    }
    val isHome = activeQuery == WallpaperRepository.HOME_TRENDING_KEY || cleanedActive.isEmpty()

    val categoryChips = remember(facets.categories) {
        facets.categories.ifEmpty { FALLBACK_LIVE_CATEGORIES }
    }
    val tagChips = remember(facets.tags) {
        facets.tags.ifEmpty { FALLBACK_LIVE_TAGS }
    }
    val liveTagsCd = stringResource(id = R.string.live_tags_section_cd)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Row 1: Category Chips (from live_wallpapers_v1.json) + Refresh button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .testTag("live_categories_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "cat_all") {
                    val allLabel = if (facets.totalWallpapers > 0) {
                        "All (${facets.totalWallpapers})"
                    } else {
                        "All"
                    }
                    Surface(
                        color = if (isHome) CrimsonBlade else ObsidianSurfaceElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isHome) PremiumGold.copy(alpha = 0.85f) else HairlineBorder,
                        ),
                        modifier = Modifier
                            .height(38.dp)
                            .clickable { onClearFilter() }
                            .testTag("live_category_chip_all"),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        ) {
                            Text(
                                text = allLabel,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (isHome) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = if (isHome) InkWhite else InkMuted,
                            )
                        }
                    }
                }

                items(
                    items = categoryChips,
                    key = { "cat_${it.query.lowercase()}" },
                ) { chip ->
                    val selected = !isHome && cleanedActive.equals(chip.query, ignoreCase = true)
                    Surface(
                        color = if (selected) CrimsonBlade else ObsidianSurfaceElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected) PremiumGold.copy(alpha = 0.9f) else HairlineBorder,
                        ),
                        modifier = Modifier
                            .height(38.dp)
                            .clickable { onSelectTopic(chip.query) }
                            .testTag("live_category_chip_${chip.query.lowercase().replace(' ', '_')}"),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        ) {
                            Text(
                                text = chip.label,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = if (selected) InkWhite else InkMuted,
                                maxLines = 1,
                            )
                            if (chip.count > 0) {
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "${chip.count}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) PremiumGold else InkSubtle,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("live_refresh_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh live wallpapers",
                    tint = InkMuted,
                )
            }
        }

        // Row 2: Clickable #Tags Chips extracted from live_wallpapers_v1.json
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = liveTagsCd },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Tag,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .testTag("live_tags_row"),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(
                    items = tagChips,
                    key = { "tag_${it.query.lowercase()}" },
                ) { tag ->
                    val selected = !isHome && cleanedActive.equals(tag.query, ignoreCase = true)
                    ClickableTagPill(
                        chip = tag,
                        selected = selected,
                        onClick = { onSelectTopic(tag.label) },
                        modifier = Modifier.testTag("live_tag_chip_${tag.query.lowercase().replace(' ', '_')}"),
                    )
                }
            }
        }

        // Active Search/Tag Filter Status Pill
        if (!isHome && resultCount != null) {
            ActiveFilterStatusBar(
                queryLabel = activeQuery,
                resultCount = resultCount,
                onClear = onClearFilter,
            )
        }
    }
}

@Composable
fun StaticCatalogFilterSection(
    activeQuery: String,
    resultCount: Int?,
    onSelectTopic: (String) -> Unit,
    onClearFilter: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cleanedActive = remember(activeQuery) {
        activeQuery.trim().removePrefix("#").trim()
    }
    val isHome = activeQuery == WallpaperRepository.HOME_TRENDING_KEY || cleanedActive.isEmpty()
    val staticTagsCd = stringResource(id = R.string.static_tags_section_cd)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .testTag("static_topics_row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "static_all") {
                    Surface(
                        color = if (isHome) CrimsonBlade else ObsidianSurfaceElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (isHome) CrimsonBlade else HairlineBorder,
                        ),
                        modifier = Modifier
                            .height(38.dp)
                            .clickable { onClearFilter() }
                            .testTag("static_topic_all"),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        ) {
                            Text(
                                text = "All",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isHome) InkWhite else InkMuted,
                            )
                        }
                    }
                }
                items(
                    items = AnimeCatalog.exploreCategories,
                    key = { it.title },
                ) { topic ->
                    val selected = !isHome && (
                            cleanedActive.equals(topic.title, ignoreCase = true) ||
                                    cleanedActive.equals(topic.query, ignoreCase = true)
                            )
                    Surface(
                        color = if (selected) CrimsonBlade else ObsidianSurfaceElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (selected) CrimsonBlade else HairlineBorder,
                        ),
                        modifier = Modifier
                            .height(38.dp)
                            .clickable { onSelectTopic(topic.title) }
                            .testTag("static_topic_${topic.title.lowercase().replace(' ', '_')}"),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        ) {
                            Text(
                                text = topic.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) InkWhite else InkMuted,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = onRefresh,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("static_refresh_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh wallpapers",
                    tint = InkMuted,
                )
            }
        }

        // Row 2: Quick Style #Tags for Static 4K Artworks
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = staticTagsCd },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Tag,
                contentDescription = null,
                tint = ElectricViolet,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .testTag("static_tags_row"),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                items(
                    items = CURATED_STATIC_TAGS,
                    key = { "static_tag_${it.query.lowercase()}" },
                ) { tag ->
                    val selected = !isHome && cleanedActive.equals(tag.query, ignoreCase = true)
                    ClickableTagPill(
                        chip = tag,
                        selected = selected,
                        onClick = { onSelectTopic(tag.label) },
                        modifier = Modifier.testTag("static_tag_chip_${tag.query.lowercase()}"),
                    )
                }
            }
        }

        if (!isHome && resultCount != null) {
            ActiveFilterStatusBar(
                queryLabel = activeQuery,
                resultCount = resultCount,
                onClear = onClearFilter,
            )
        }
    }
}

@Composable
private fun ClickableTagPill(
    chip: LiveTagChip,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) CyberCyanContainer else ObsidianSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) CyberCyan else HairlineBorder,
        ),
        modifier = modifier
            .height(34.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 11.dp),
        ) {
            Text(
                text = chip.label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (selected) CyberCyan else InkWhite.copy(alpha = 0.86f),
                maxLines = 1,
            )
            if (chip.count > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "· ${chip.count}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                    color = if (selected) CyberCyan.copy(alpha = 0.9f) else InkSubtle,
                )
            }
        }
    }
}

@Composable
private fun ActiveFilterStatusBar(
    queryLabel: String,
    resultCount: Int,
    onClear: () -> Unit,
) {
    Surface(
        color = ObsidianSurfaceElevated.copy(alpha = 0.92f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("active_filter_status_bar"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = stringResource(id = R.string.search_matches_label, resultCount, queryLabel),
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                color = CrimsonBlade.copy(alpha = 0.16f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, CrimsonBlade.copy(alpha = 0.5f)),
                modifier = Modifier
                    .clickable(onClick = onClear)
                    .testTag("clear_active_filter_button"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(id = R.string.search_clear_filter),
                        tint = CrimsonBlade,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(id = R.string.search_clear_filter),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = InkWhite,
                    )
                }
            }
        }
    }
}
