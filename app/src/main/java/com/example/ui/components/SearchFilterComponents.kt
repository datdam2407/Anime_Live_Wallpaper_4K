package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LocalMovies
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.domain.model.FacetType
import com.example.domain.model.LiveCatalogFacets
import com.example.domain.model.LiveFilterState
import com.example.domain.model.LiveSearchSuggestion
import com.example.domain.model.LiveTagChip
import com.example.domain.model.SearchSuggestionType
import com.example.domain.model.StaticFilterState
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
    LiveTagChip(label = "Anime", query = "Anime", count = 0, type = FacetType.CATEGORY),
    LiveTagChip(label = "Games", query = "Games", count = 0, type = FacetType.CATEGORY),
    LiveTagChip(label = "City", query = "City", count = 0, type = FacetType.CATEGORY),
    LiveTagChip(label = "Nature", query = "Nature", count = 0, type = FacetType.CATEGORY),
    LiveTagChip(label = "Sci-Fi", query = "Sci-Fi", count = 0, type = FacetType.CATEGORY),
    LiveTagChip(label = "Cars", query = "Cars", count = 0, type = FacetType.CATEGORY),
)

private val FALLBACK_LIVE_TAGS = listOf(
    LiveTagChip(label = "#anime", query = "anime", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#amoled", query = "amoled", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#dark", query = "dark", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#glowing", query = "glowing", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#magic", query = "magic", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#sword", query = "sword", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#neon", query = "neon", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#fantasy", query = "fantasy", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#cyberpunk", query = "cyberpunk", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#aesthetic", query = "aesthetic", count = 0, type = FacetType.TAG),
)

private val FALLBACK_LIVE_TAGS_BY_CATEGORY: Map<String, List<LiveTagChip>> = mapOf(
    "anime" to listOf("sakura", "samurai", "magic", "sword", "dark", "glowing", "kawaii", "cyberpunk").map {
        LiveTagChip(label = "#$it", query = it, count = 0, type = FacetType.TAG)
    },
    "games" to listOf("cyberpunk", "fantasy", "sword", "magic", "neon", "dark", "glowing", "warrior").map {
        LiveTagChip(label = "#$it", query = it, count = 0, type = FacetType.TAG)
    },
    "city" to listOf("neon", "cyberpunk", "night", "rain", "tokyo", "street", "skyline", "glowing").map {
        LiveTagChip(label = "#$it", query = it, count = 0, type = FacetType.TAG)
    },
    "nature" to listOf("forest", "mountain", "ocean", "sunset", "moonlight", "aurora", "rain", "snow").map {
        LiveTagChip(label = "#$it", query = it, count = 0, type = FacetType.TAG)
    },
    "sci-fi" to listOf("cyberpunk", "space", "mecha", "neon", "futuristic", "planet", "galaxy", "dark").map {
        LiveTagChip(label = "#$it", query = it, count = 0, type = FacetType.TAG)
    },
    "cars" to listOf("jdm", "drift", "supercar", "night", "neon", "rain", "racing", "dark").map {
        LiveTagChip(label = "#$it", query = it, count = 0, type = FacetType.TAG)
    },
)

val STATIC_TAG_POOL = listOf(
    LiveTagChip(label = "#Cyberpunk", query = "Cyberpunk", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Dark", query = "Dark", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Aesthetic", query = "Aesthetic", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Samurai", query = "Samurai", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Sakura", query = "Sakura", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Neon", query = "Neon", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Fantasy", query = "Fantasy", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Minimal", query = "Minimal", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Night", query = "Night", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Magic", query = "Magic", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Katana", query = "Katana", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Mecha", query = "Mecha", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Moonlight", query = "Moonlight", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Rain", query = "Rain", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Galaxy", query = "Galaxy", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Dragon", query = "Dragon", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Sunset", query = "Sunset", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Shrine", query = "Shrine", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Vaporwave", query = "Vaporwave", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Gothic", query = "Gothic", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Fire", query = "Fire", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Ice", query = "Ice", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Ocean", query = "Ocean", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Forest", query = "Forest", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Retro", query = "Retro", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Monochrome", query = "Monochrome", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Pastel", query = "Pastel", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Cityscape", query = "Cityscape", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Ukiyo-e", query = "Ukiyo-e", count = 0, type = FacetType.TAG),
    LiveTagChip(label = "#Kawaii", query = "Kawaii", count = 0, type = FacetType.TAG),
)

private fun staticTagChips(vararg names: String): List<LiveTagChip> =
    names.map { name -> LiveTagChip(label = "#$name", query = name, count = 0, type = FacetType.TAG) }

/**
 * Dynamic category-contextual #Tag suggestions for 4K Artworks so selecting Anime, Games, City,
 * Cars, Nature, Sci-Fi, etc. immediately surfaces hashtags tailored to that exact category.
 */
val STATIC_TAGS_BY_CATEGORY: Map<String, List<LiveTagChip>> = mapOf(
    "anime" to staticTagChips(
        "Kawaii", "Samurai", "Sakura", "Katana", "Dark", "Magic",
        "Fantasy", "Mecha", "Shrine", "Moonlight", "Cyberpunk", "Ukiyo-e",
        "Night", "Dragon", "Aesthetic",
    ),
    "games" to staticTagChips(
        "Cyberpunk", "Fantasy", "Knight", "Sword", "Dark", "Neon",
        "RPG", "Magic", "Dragon", "Mecha", "Sci-Fi", "Pixel Art",
        "Warrior", "Minimal",
    ),
    "city" to staticTagChips(
        "Neon", "Cyberpunk", "Night", "Rain", "Tokyo", "Skyline",
        "Street", "Sunset", "Vaporwave", "Architecture", "Reflections", "Alley",
    ),
    "cars" to staticTagChips(
        "JDM", "Supercar", "Drift", "Night", "Neon", "Racing",
        "Porsche", "GTR", "Dark", "Rain", "Classic", "Cyberpunk",
    ),
    "nature" to staticTagChips(
        "Mountain", "Forest", "Ocean", "Sunset", "Aurora", "Night",
        "Waterfall", "Snow", "Moonlight", "Autumn", "Sakura", "Lake",
    ),
    "tv & movies" to staticTagChips(
        "Cinematic", "Sci-Fi", "Dark", "Fantasy", "Space", "Superhero",
        "Retro", "Neon", "Noir", "Minimal",
    ),
    "cute" to staticTagChips(
        "Kawaii", "Pastel", "Chibi", "Sakura", "Cat", "Cozy",
        "Pink", "Minimal", "Aesthetic", "Fantasy",
    ),
    "sci-fi" to staticTagChips(
        "Space", "Cyberpunk", "Mecha", "Astronaut", "Nebula", "Futuristic",
        "Neon", "Robot", "Planet", "Galaxy", "Dark", "Cityscape",
    ),
    "abstract" to staticTagChips(
        "Minimal", "Dark", "Geometric", "Neon", "Fluid", "3D",
        "Gradient", "Vaporwave", "Monochrome", "Fractal", "AMOLED", "Aesthetic",
    ),
    "animals" to staticTagChips(
        "Wolf", "Cat", "Dragon", "Fox", "Tiger", "Owl",
        "Lion", "Bird", "Dark", "Forest", "Fantasy", "Neon",
    ),
    "comics" to staticTagChips(
        "Superhero", "Manga", "Monochrome", "Dark", "Retro", "Cyberpunk",
        "Noir", "Action", "Ink", "Minimal",
    ),
    "lifestyle" to staticTagChips(
        "Aesthetic", "Cozy", "Coffee", "Lo-Fi", "Sunset", "Minimal",
        "Pastel", "Autumn", "Rain", "Night",
    ),
)

val CURATED_STATIC_TAGS = STATIC_TAG_POOL.take(10)

fun getStaticTagCandidatePool(selectedCategory: String? = null): List<LiveTagChip> {
    val categoryKey = selectedCategory?.trim()?.lowercase().orEmpty()
    return STATIC_TAGS_BY_CATEGORY[categoryKey]
        ?.takeIf { it.isNotEmpty() }
        ?: STATIC_TAG_POOL
}

fun getRotatedStaticTags(
    selectedCategory: String? = null,
    rotationSeed: Int = 0,
    windowSize: Int = 10,
): List<LiveTagChip> {
    val sourcePool = getStaticTagCandidatePool(selectedCategory)
    return rotateVerifiedTagChips(
        verifiedTags = sourcePool,
        rotationSeed = rotationSeed,
        windowSize = windowSize,
    )
}

fun rotateVerifiedTagChips(
    verifiedTags: List<LiveTagChip>,
    rotationSeed: Int = 0,
    windowSize: Int = 10,
): List<LiveTagChip> {
    if (verifiedTags.isEmpty()) return emptyList()
    val distinct = verifiedTags.distinctBy { it.query.lowercase() }
    val resolvedWindow = windowSize.coerceAtMost(distinct.size)
    if (rotationSeed <= 0 || distinct.size <= 1) return distinct.take(resolvedWindow)
    val step = (rotationSeed * 3) % distinct.size
    return List(resolvedWindow) { idx ->
        distinct[(step + idx) % distinct.size]
    }
}

@Composable
fun SearchSuggestionStrip(
    suggestions: List<LiveSearchSuggestion>,
    onSelectSuggestion: (LiveSearchSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uniqueSuggestions = remember(suggestions) {
        suggestions.distinctBy { "${it.type.name}:${it.query.lowercase()}" }
    }

    AnimatedVisibility(
        visible = uniqueSuggestions.isNotEmpty(),
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
                items = uniqueSuggestions,
                key = { _, item -> "${item.type.name}:${item.query.lowercase()}" },
            ) { index, suggestion ->
                val (icon, accentColor, containerColor) = when (suggestion.type) {
                    SearchSuggestionType.CATEGORY -> Triple(
                        Icons.Rounded.AutoAwesome,
                        PremiumGold,
                        PremiumGoldSurface,
                    )
                    SearchSuggestionType.SERIES -> Triple(
                        Icons.Rounded.LocalMovies,
                        ElectricViolet,
                        ElectricViolet.copy(alpha = 0.16f),
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
                    onClick = { onSelectSuggestion(suggestion) },
                    color = containerColor,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .defaultMinSize(minHeight = 40.dp)
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
    filterState: LiveFilterState,
    facets: LiveCatalogFacets,
    resultCount: Int?,
    onToggleCategory: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onClearFilter: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isHome = filterState.isHome

    val categoryChips = remember(facets.categories, facets.totalWallpapers) {
        val nonEmpty = facets.categories.filter { it.count > 0 }
        val resolved = if (facets.totalWallpapers > 0) {
            nonEmpty
        } else {
            nonEmpty.ifEmpty { FALLBACK_LIVE_CATEGORIES }
        }
        resolved.distinctBy { it.query.lowercase() }
    }
    val tagChips = remember(facets, filterState.selectedCategory, facets.tagRotationVersion) {
        // Strictly display only hashtags verified to have count > 0 in the live catalog for the active scope.
        facets.displayTagsFor(filterState.selectedCategory)
            .filter { it.count > 0 }
            .distinctBy { it.query.lowercase() }
    }
    val liveTagsRowState = rememberLazyListState()
    LaunchedEffect(facets.tagRotationVersion, filterState.selectedCategory) {
        if (liveTagsRowState.firstVisibleItemIndex > 0 || liveTagsRowState.firstVisibleItemScrollOffset > 0) {
            liveTagsRowState.scrollToItem(0)
        }
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
                    val allSelected = filterState.selectedCategory == null &&
                            filterState.selectedSeries == null &&
                            filterState.selectedTags.isEmpty() &&
                            filterState.query.isBlank()
                    val allLabel = if (facets.totalWallpapers > 0) {
                        "All (${facets.totalWallpapers})"
                    } else {
                        "All"
                    }
                    Surface(
                        onClick = onClearFilter,
                        color = if (allSelected) CrimsonBlade else ObsidianSurfaceElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (allSelected) PremiumGold.copy(alpha = 0.85f) else HairlineBorder,
                        ),
                        modifier = Modifier
                            .height(38.dp)
                            .testTag("live_category_chip_all"),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp),
                        ) {
                            Text(
                                text = allLabel,
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (allSelected) FontWeight.Bold else FontWeight.Medium,
                                ),
                                color = if (allSelected) InkWhite else InkMuted,
                            )
                        }
                    }
                }

                items(
                    items = categoryChips,
                    key = { "cat_${it.query.lowercase()}" },
                ) { chip ->
                    val selected = filterState.selectedCategory.equals(chip.query, ignoreCase = true)
                    Surface(
                        onClick = { onToggleCategory(chip.query) },
                        color = if (selected) CrimsonBlade else ObsidianSurfaceElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selected) PremiumGold.copy(alpha = 0.9f) else HairlineBorder,
                        ),
                        modifier = Modifier
                            .height(38.dp)
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

        // Row 2: Contextual #Tags Chips (strictly verified count > 0 for selected Category / catalog)
        if (tagChips.isNotEmpty()) {
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
                    state = liveTagsRowState,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("live_tags_row"),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(
                        items = tagChips,
                        key = { "tag_${it.query.lowercase()}" },
                    ) { tag ->
                        val selected = tag.query.lowercase() in filterState.selectedTags
                        ClickableTagPill(
                            chip = tag,
                            selected = selected,
                            onClick = { onToggleTag(tag.query) },
                            modifier = Modifier.testTag("live_tag_chip_${tag.query.lowercase().replace(' ', '_')}"),
                        )
                    }
                }
            }
        }

        // Hide the "X matches" bar when filtering by Category or Tag chips to avoid
        // conflicting counts during paginated scrolling; keep it only for free-text / series searches.
        if (!isHome && !filterState.isChipFilterActive && resultCount != null) {
            ActiveFilterStatusBar(
                queryLabel = filterState.summaryLabel().ifBlank { "Filtered" },
                resultCount = resultCount,
                useFacetedFormat = true,
                onClear = onClearFilter,
            )
        }
    }
}

@Composable
fun StaticCatalogFilterSection(
    filterState: StaticFilterState,
    hiddenEmptyCategories: Set<String> = emptySet(),
    verifiedTags: List<LiveTagChip> = emptyList(),
    tagRotationVersion: Int = 0,
    onToggleCategory: (String) -> Unit,
    onToggleTag: (String) -> Unit,
    onClearFilter: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isHome = filterState.isHome
    val visibleCategories = remember(hiddenEmptyCategories) {
        AnimeCatalog.exploreCategories.filterNot { topic ->
            topic.title.lowercase() in hiddenEmptyCategories
        }
    }
    // Strictly render only API/cache-verified hashtags confirmed to have count > 0 images.
    val staticTags = remember(verifiedTags, filterState.selectedTag, tagRotationVersion) {
        val nonEmptyVerified = verifiedTags
            .filter { it.count > 0 }
            .distinctBy { it.query.lowercase() }
        val rotated = rotateVerifiedTagChips(
            verifiedTags = nonEmptyVerified,
            rotationSeed = tagRotationVersion,
            windowSize = 10,
        )
        val activeTag = filterState.selectedTag?.trim().orEmpty()
        if (activeTag.isNotEmpty() && rotated.none { it.query.equals(activeTag, ignoreCase = true) }) {
            val activeChip = nonEmptyVerified.firstOrNull { it.query.equals(activeTag, ignoreCase = true) }
            if (activeChip != null) {
                listOf(activeChip) + rotated.take(9)
            } else {
                rotated
            }
        } else {
            rotated
        }
    }
    val staticTagsRowState = rememberLazyListState()
    LaunchedEffect(tagRotationVersion, filterState.selectedCategory) {
        if (staticTagsRowState.firstVisibleItemIndex > 0 || staticTagsRowState.firstVisibleItemScrollOffset > 0) {
            staticTagsRowState.scrollToItem(0)
        }
    }
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
                        onClick = onClearFilter,
                        color = if (isHome) CrimsonBlade else ObsidianSurfaceElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (isHome) CrimsonBlade else HairlineBorder,
                        ),
                        modifier = Modifier
                            .height(38.dp)
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
                    items = visibleCategories,
                    key = { it.title },
                ) { topic ->
                    val selected = filterState.selectedCategory.equals(topic.title, ignoreCase = true) ||
                            filterState.selectedCategory.equals(topic.query, ignoreCase = true)
                    Surface(
                        onClick = { onToggleCategory(topic.title) },
                        color = if (selected) CrimsonBlade else ObsidianSurfaceElevated,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(
                            1.dp,
                            if (selected) CrimsonBlade else HairlineBorder,
                        ),
                        modifier = Modifier
                            .height(38.dp)
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

        // Row 2: Strictly API-verified Category-Contextual #Tags for Static 4K Artworks (count > 0 only)
        if (staticTags.isNotEmpty()) {
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
                    state = staticTagsRowState,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("static_tags_row"),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(
                        items = staticTags,
                        key = { "static_tag_${it.query.lowercase()}" },
                    ) { tag ->
                        val selected = filterState.selectedTag.equals(tag.query, ignoreCase = true)
                        ClickableTagPill(
                            chip = tag.copy(count = 0),
                            selected = selected,
                            onClick = { onToggleTag(tag.query) },
                            modifier = Modifier.testTag("static_tag_chip_${tag.query.lowercase()}"),
                        )
                    }
                }
            }
        }

        // Completely hide the "X matches" count bar whenever filtering by Category or Tag chips.
        // Only show a clean non-counted Clear bar for typed free-text searches.
        if (filterState.isSearchQueryOnly) {
            ActiveFilterStatusBar(
                queryLabel = filterState.searchQuery.trim(),
                resultCount = null,
                useFacetedFormat = false,
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
        onClick = onClick,
        color = if (selected) CyberCyanContainer else ObsidianSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) CyberCyan else HairlineBorder,
        ),
        modifier = modifier.defaultMinSize(minHeight = 36.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
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
    resultCount: Int?,
    useFacetedFormat: Boolean,
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
            val statusText = when {
                resultCount == null -> stringResource(id = R.string.search_results_for_label, queryLabel)
                useFacetedFormat -> stringResource(
                    id = R.string.search_matches_faceted_label,
                    resultCount,
                    queryLabel,
                )
                else -> stringResource(
                    id = R.string.search_matches_label,
                    resultCount,
                    queryLabel,
                )
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                onClick = onClear,
                color = CrimsonBlade.copy(alpha = 0.16f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, CrimsonBlade.copy(alpha = 0.5f)),
                modifier = Modifier
                    .defaultMinSize(minHeight = 32.dp)
                    .testTag("clear_active_filter_button"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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
