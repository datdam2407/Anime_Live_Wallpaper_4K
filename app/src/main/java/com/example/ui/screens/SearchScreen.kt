package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Landscape
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.example.KuroWallApplication
import com.example.ads.NativeAdCard
import com.example.ads.NativeAdManager
import com.example.ads.NativeAdStyle
import com.example.domain.model.AnimeCatalog
import com.example.domain.model.AppWallpaper
import com.example.domain.model.CategoryCoverCandidate
import com.example.domain.model.LiveCatalogFacets
import com.example.domain.model.LiveSearchSuggestion
import com.example.domain.model.LiveTagChip
import com.example.ui.components.CURATED_STATIC_TAGS
import com.example.ui.components.SearchSuggestionStrip
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkSubtle
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PremiumGold
import com.kuroanimelivewallpapers.app.R
import kotlinx.coroutines.delay

@Immutable
data class TrendingColorOption(
    val id: String,
    val label: String,
    val query: String,
    val primaryColor: Color,
    val secondaryColor: Color,
    val highlightColor: Color,
    val isSplitMonochrome: Boolean = false,
)

@Immutable
private data class TrendingCategoryVisualStyle(
    val title: String,
    val icon: ImageVector,
    val gradientStart: Color,
    val gradientMid: Color,
    val gradientEnd: Color,
    val glowColor: Color,
)

@Immutable
private data class TrendingCategoryCardItem(
    val id: String,
    val title: String,
    val query: String,
    val clickKeyword: String,
    val coverUrl: String?,
    val icon: ImageVector,
    val gradientStart: Color,
    val gradientMid: Color,
    val gradientEnd: Color,
    val glowColor: Color,
    val isLiveCover: Boolean,
)

private val TRENDING_COLORS_ROW_1 = listOf(
    TrendingColorOption(
        id = "black",
        label = "Black",
        query = "black",
        primaryColor = Color(0xFF1A1A22),
        secondaryColor = Color(0xFF060609),
        highlightColor = Color(0xFF454556),
    ),
    TrendingColorOption(
        id = "black_and_white",
        label = "Black And White",
        query = "black and white",
        primaryColor = Color(0xFF141418),
        secondaryColor = Color(0xFFF5F5F7),
        highlightColor = Color(0xFFDADAE2),
        isSplitMonochrome = true,
    ),
    TrendingColorOption(
        id = "blue",
        label = "Blue",
        query = "blue",
        primaryColor = Color(0xFF2196F3),
        secondaryColor = Color(0xFF0D47A1),
        highlightColor = Color(0xFF81D4FA),
    ),
    TrendingColorOption(
        id = "pink",
        label = "Pink",
        query = "pink",
        primaryColor = Color(0xFFFF80AB),
        secondaryColor = Color(0xFFD81B60),
        highlightColor = Color(0xFFFFCDD2),
    ),
    TrendingColorOption(
        id = "purple",
        label = "Purple",
        query = "purple",
        primaryColor = Color(0xFF9C27B0),
        secondaryColor = Color(0xFF4A148C),
        highlightColor = Color(0xFFE1BEE7),
    ),
)

private val TRENDING_COLORS_ROW_2 = listOf(
    TrendingColorOption(
        id = "white",
        label = "White",
        query = "white",
        primaryColor = Color(0xFFE8E8EE),
        secondaryColor = Color(0xFFBDBDC9),
        highlightColor = Color(0xFFFFFFFF),
    ),
    TrendingColorOption(
        id = "red",
        label = "Red",
        query = "red",
        primaryColor = Color(0xFFE53935),
        secondaryColor = Color(0xFF8E0000),
        highlightColor = Color(0xFFFF8A80),
    ),
    TrendingColorOption(
        id = "yellow",
        label = "Yellow",
        query = "yellow",
        primaryColor = Color(0xFFFFB300),
        secondaryColor = Color(0xFFE65100),
        highlightColor = Color(0xFFFFF176),
    ),
    TrendingColorOption(
        id = "green",
        label = "Green",
        query = "green",
        primaryColor = Color(0xFF43A047),
        secondaryColor = Color(0xFF1B5E20),
        highlightColor = Color(0xFFA5D6A7),
    ),
    TrendingColorOption(
        id = "orange",
        label = "Orange",
        query = "orange",
        primaryColor = Color(0xFFFF6D00),
        secondaryColor = Color(0xFFBF360C),
        highlightColor = Color(0xFFFFCC80),
    ),
)

private val EXPLORE_CATEGORY_VISUALS: Map<String, TrendingCategoryVisualStyle> = listOf(
    TrendingCategoryVisualStyle(
        title = "Anime",
        icon = Icons.Rounded.AutoAwesome,
        gradientStart = Color(0xFF4A148C),
        gradientMid = Color(0xFF880E4F),
        gradientEnd = Color(0xFF140B24),
        glowColor = Color(0xFFFF2E54),
    ),
    TrendingCategoryVisualStyle(
        title = "Games",
        icon = Icons.Rounded.SportsEsports,
        gradientStart = Color(0xFF311B92),
        gradientMid = Color(0xFF6A1B9A),
        gradientEnd = Color(0xFF0E1028),
        glowColor = Color(0xFF8C52FF),
    ),
    TrendingCategoryVisualStyle(
        title = "City",
        icon = Icons.Rounded.LocationCity,
        gradientStart = Color(0xFF0D47A1),
        gradientMid = Color(0xFF006064),
        gradientEnd = Color(0xFF071321),
        glowColor = Color(0xFF00E5FF),
    ),
    TrendingCategoryVisualStyle(
        title = "Cars",
        icon = Icons.Rounded.DirectionsCar,
        gradientStart = Color(0xFFB71C1C),
        gradientMid = Color(0xFFD84315),
        gradientEnd = Color(0xFF1A0909),
        glowColor = Color(0xFFFF5252),
    ),
    TrendingCategoryVisualStyle(
        title = "Nature",
        icon = Icons.Rounded.Landscape,
        gradientStart = Color(0xFF1B5E20),
        gradientMid = Color(0xFF00695C),
        gradientEnd = Color(0xFF071912),
        glowColor = Color(0xFF69F0AE),
    ),
    TrendingCategoryVisualStyle(
        title = "TV & Movies",
        icon = Icons.Rounded.Movie,
        gradientStart = Color(0xFF4E342E),
        gradientMid = Color(0xFFAD1457),
        gradientEnd = Color(0xFF190C12),
        glowColor = Color(0xFFFFCA28),
    ),
    TrendingCategoryVisualStyle(
        title = "Cute",
        icon = Icons.Rounded.Favorite,
        gradientStart = Color(0xFFEC407A),
        gradientMid = Color(0xFFAB47BC),
        gradientEnd = Color(0xFF210D1B),
        glowColor = Color(0xFFFF80AB),
    ),
    TrendingCategoryVisualStyle(
        title = "Sci-Fi",
        icon = Icons.Rounded.RocketLaunch,
        gradientStart = Color(0xFF004D40),
        gradientMid = Color(0xFF1A237E),
        gradientEnd = Color(0xFF060B1E),
        glowColor = Color(0xFF18FFFF),
    ),
    TrendingCategoryVisualStyle(
        title = "Abstract",
        icon = Icons.Rounded.Palette,
        gradientStart = Color(0xFFFF6F00),
        gradientMid = Color(0xFFC2185B),
        gradientEnd = Color(0xFF1B0A22),
        glowColor = Color(0xFFFFD54F),
    ),
    TrendingCategoryVisualStyle(
        title = "Animals",
        icon = Icons.Rounded.Pets,
        gradientStart = Color(0xFFE65100),
        gradientMid = Color(0xFF33691E),
        gradientEnd = Color(0xFF14170B),
        glowColor = Color(0xFFFFAB40),
    ),
    TrendingCategoryVisualStyle(
        title = "Comics",
        icon = Icons.Rounded.MenuBook,
        gradientStart = Color(0xFFD50000),
        gradientMid = Color(0xFF1565C0),
        gradientEnd = Color(0xFF100E24),
        glowColor = Color(0xFFFFEA00),
    ),
    TrendingCategoryVisualStyle(
        title = "Lifestyle",
        icon = Icons.Rounded.LocalCafe,
        gradientStart = Color(0xFF8D6E63),
        gradientMid = Color(0xFF5D4037),
        gradientEnd = Color(0xFF1B1310),
        glowColor = Color(0xFFFFCC80),
    ),
).associateBy { it.title }

@Composable
fun SearchScreen(
    liveFacets: LiveCatalogFacets,
    trendingBanners: List<AppWallpaper>,
    categoryCovers: Map<String, CategoryCoverCandidate>,
    onComputeSuggestions: (String) -> List<LiveSearchSuggestion>,
    onSearchSubmit: (query: String, isColorSearch: Boolean, preferLive: Boolean?) -> Unit,
    onScreenEntered: () -> Unit = {},
    scrollToTopTick: Int = 0,
    nativeAdManager: NativeAdManager? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val gridState = rememberLazyGridState()
    var lastHandledScrollTick by rememberSaveable { mutableIntStateOf(scrollToTopTick) }
    var query by rememberSaveable { mutableStateOf("") }
    val suggestions = remember(query, liveFacets) {
        if (query.isBlank()) emptyList() else onComputeSuggestions(query)
    }

    LaunchedEffect(Unit) {
        onScreenEntered()
    }

    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick != lastHandledScrollTick) {
            lastHandledScrollTick = scrollToTopTick
            if (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0) {
                if (gridState.firstVisibleItemIndex <= 8) {
                    gridState.animateScrollToItem(0)
                } else {
                    gridState.scrollToItem(0, 0)
                }
            }
        }
    }

    val resolvedAdManager = remember(context, nativeAdManager) {
        nativeAdManager ?: (context.applicationContext as? KuroWallApplication)?.container?.nativeAdManager
    }
    val consentManager = remember(context) {
        (context.applicationContext as? KuroWallApplication)?.container?.consentManager
    }
    val canRequestAds = consentManager?.canRequestAds?.collectAsStateWithLifecycle()?.value ?: true
    // Defer mounting NativeAdCard (which instantiates AndroidView/NativeAdView & triggers AdLoader
    // on Main thread) until after the NavHost fade-in finishes so SearchScreen renders on Frame 1 (<16ms).
    var showInlineAd by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(220L)
        showInlineAd = true
    }

    LaunchedEffect(resolvedAdManager, canRequestAds) {
        if (canRequestAds) {
            delay(350L)
            resolvedAdManager?.preloadBuffer()
        }
    }

    val categoryCards = remember(categoryCovers, liveFacets, trendingBanners) {
        buildTrendingCategoryCards(
            categoryCovers = categoryCovers,
            liveFacets = liveFacets,
            trendingBanners = trendingBanners,
        )
    }

    val popularTags = remember(liveFacets.tags) {
        if (liveFacets.tags.isNotEmpty()) {
            liveFacets.tags
        } else {
            CURATED_STATIC_TAGS
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid),
    ) {
        val gridColumns = when {
            maxWidth >= 840.dp -> 5
            maxWidth >= 600.dp -> 4
            else -> 3
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Top Rounded Pill Search Bar (matching reference mockup)
            SearchPillBar(
                query = query,
                onQueryChange = { query = it },
                onSubmit = {
                    val trimmed = query.trim()
                    if (trimmed.isNotEmpty()) {
                        focusManager.clearFocus()
                        onSearchSubmit(trimmed, false, null)
                    }
                },
                onClear = { query = "" },
            )

            // Instant Autocomplete Suggestion Strip while typing
            SearchSuggestionStrip(
                suggestions = suggestions,
                onSelectSuggestion = { suggestion ->
                    focusManager.clearFocus()
                    query = suggestion.label
                    onSearchSubmit(suggestion.label, false, null)
                },
            )

            // Scrollable body: Trending color -> Inline Ad Banner -> Popular #Tags -> Trending Categories (3-col grid)
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(gridColumns),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("search_screen_grid"),
            ) {
                // Section 1: Trending color (2-row horizontal scroll of color orb capsules)
                item(span = { GridItemSpan(maxLineSpan) }, key = "section_trending_color") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(id = R.string.search_trending_color),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            color = InkWhite,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        TrendingColorPaletteRows(
                            onColorSelected = { colorOption ->
                                focusManager.clearFocus()
                                onSearchSubmit(colorOption.query, true, null)
                            },
                        )
                    }
                }

                // Inline Ad Banner (between Trending color and Popular #Tags)
                if (canRequestAds) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "section_search_inline_ad") {
                        Box(modifier = Modifier.padding(vertical = 4.dp)) {
                            if (showInlineAd) {
                                NativeAdCard(
                                    style = NativeAdStyle.SEARCH_BANNER,
                                    slotKey = "search_inline_banner",
                                    nativeAdManager = resolvedAdManager,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(76.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF1F1A2E).copy(alpha = 0.45f)),
                                )
                            }
                        }
                    }
                }

                // Section 2: Popular #Tags quick selector (positioned ABOVE Trending Categories)
                if (popularTags.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "section_popular_tags") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.Tag,
                                    contentDescription = null,
                                    tint = CyberCyan,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(id = R.string.search_popular_tags),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = InkWhite,
                                )
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("search_popular_tags_row"),
                            ) {
                                items(
                                    items = popularTags,
                                    key = { "search_tag_${it.query.lowercase()}" },
                                ) { tag ->
                                    SearchTagChip(
                                        chip = tag,
                                        onClick = {
                                            focusManager.clearFocus()
                                            onSearchSubmit(tag.label, false, if (liveFacets.tags.isNotEmpty()) true else null)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 3 Header: Trending Categories
                item(span = { GridItemSpan(maxLineSpan) }, key = "section_trending_categories_header") {
                    Text(
                        text = stringResource(id = R.string.search_trending_categories),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = InkWhite,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                // 3-Column Portrait Visual Category Cards (12 Explore Domains with rotating static + live covers)
                items(
                    items = categoryCards,
                    key = { "cat_card_${it.id}" },
                ) { card ->
                    TrendingCategoryCard(
                        card = card,
                        onClick = {
                            focusManager.clearFocus()
                            if (card.isLiveCover) {
                                // LIVE-badged card navigates straight to Live Wallpapers tab with keyword
                                onSearchSubmit(card.clickKeyword, false, true)
                            } else {
                                // Static-artwork card navigates straight to 4K Artworks tab with category keyword
                                onSearchSubmit(card.title, false, false)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchPillBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(ObsidianSurfaceElevated)
            .border(1.dp, HairlineBorder, RoundedCornerShape(28.dp))
            .padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.search_screen_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = InkWhite),
                cursorBrush = SolidColor(CrimsonBlade),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("search_screen_input"),
            )
        }

        if (query.isNotEmpty()) {
            IconButton(
                onClick = onClear,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("search_screen_clear"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Clear search",
                    tint = InkMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        IconButton(
            onClick = onSubmit,
            modifier = Modifier
                .size(48.dp)
                .testTag("search_screen_submit"),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "Search wallpapers",
                tint = InkWhite,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun TrendingColorPaletteRows(
    onColorSelected: (TrendingColorOption) -> Unit,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .testTag("trending_colors_scroll"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TRENDING_COLORS_ROW_1.forEach { option ->
                TrendingColorCapsule(
                    option = option,
                    onClick = { onColorSelected(option) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TRENDING_COLORS_ROW_2.forEach { option ->
                TrendingColorCapsule(
                    option = option,
                    onClick = { onColorSelected(option) },
                )
            }
        }
    }
}

@Composable
private fun TrendingColorCapsule(
    option: TrendingColorOption,
    onClick: () -> Unit,
) {
    Surface(
        color = ObsidianSurface,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, InkMuted.copy(alpha = 0.42f)),
        modifier = Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .testTag("trending_color_${option.id}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 16.dp, top = 5.dp, bottom = 5.dp),
        ) {
            ColorOrbAvatar(
                option = option,
                modifier = Modifier.size(36.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = option.label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = InkWhite,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ColorOrbAvatar(
    option: TrendingColorOption,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
            .drawWithCache {
                val radius = size.minDimension / 2f
                val center = Offset(size.width / 2f, size.height / 2f)
                if (option.isSplitMonochrome) {
                    val whiteWave = Path().apply {
                        moveTo(size.width * 0.15f, size.height)
                        cubicTo(
                            size.width * 0.32f, size.height * 0.52f,
                            size.width * 0.68f, size.height * 0.48f,
                            size.width * 0.85f, 0f,
                        )
                        lineTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        close()
                    }
                    onDrawBehind {
                        drawCircle(color = option.primaryColor, radius = radius, center = center)
                        drawPath(path = whiteWave, color = option.secondaryColor)
                    }
                } else {
                    val bodyBrush = Brush.radialGradient(
                        colors = listOf(
                            option.highlightColor,
                            option.primaryColor,
                            option.secondaryColor,
                        ),
                        center = Offset(size.width * 0.34f, size.height * 0.32f),
                        radius = radius * 1.25f,
                    )
                    val gleamCenter = Offset(size.width * 0.30f, size.height * 0.26f)
                    val gleamRadius = radius * 0.55f
                    val gleamBrush = Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.34f), Color.Transparent),
                        center = gleamCenter,
                        radius = gleamRadius,
                    )
                    onDrawBehind {
                        drawCircle(brush = bodyBrush, radius = radius, center = center)
                        drawCircle(brush = gleamBrush, radius = gleamRadius, center = gleamCenter)
                    }
                }
            },
    )
}

private val CATEGORY_CARD_SCRIM_BRUSH = Brush.verticalGradient(
    0.0f to Color.Transparent,
    0.48f to ObsidianVoid.copy(alpha = 0.16f),
    1.0f to ObsidianVoid.copy(alpha = 0.90f),
)

@Composable
private fun TrendingCategoryCard(
    card: TrendingCategoryCardItem,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = remember(density) { with(density) { 150.dp.roundToPx() } }
    val heightPx = remember(density) { with(density) { 200.dp.roundToPx() } }
    val backdropBrush = remember(card.id) {
        Brush.linearGradient(
            colors = listOf(
                card.gradientStart,
                card.gradientMid,
                card.gradientEnd,
            ),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.76f)
            .clip(RoundedCornerShape(14.dp))
            .background(backdropBrush)
            .border(1.dp, HairlineBorder.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag("trending_category_${card.id}"),
    ) {
        if (card.coverUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val glowCenter = Offset(size.width * 0.78f, size.height * 0.20f)
                        val glowRadius = size.minDimension * 0.85f
                        val glowBrush = Brush.radialGradient(
                            colors = listOf(card.glowColor.copy(alpha = 0.55f), Color.Transparent),
                            center = glowCenter,
                            radius = glowRadius,
                        )
                        val highlightCenter = Offset(size.width * 0.22f, size.height * 0.45f)
                        val highlightRadius = size.minDimension * 0.65f
                        val highlightBrush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                            center = highlightCenter,
                            radius = highlightRadius,
                        )
                        onDrawBehind {
                            drawCircle(brush = glowBrush, radius = glowRadius, center = glowCenter)
                            drawCircle(brush = highlightBrush, radius = highlightRadius, center = highlightCenter)
                        }
                    },
            ) {
                Surface(
                    color = ObsidianVoid.copy(alpha = 0.28f),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(bottom = 20.dp)
                        .size(48.dp),
                ) {
                    Icon(
                        imageVector = card.icon,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.94f),
                        modifier = Modifier.padding(11.dp),
                    )
                }
            }
        } else {
            val request = remember(card.coverUrl, widthPx, heightPx) {
                ImageRequest.Builder(context)
                    .data(card.coverUrl)
                    .size(widthPx, heightPx)
                    .precision(Precision.INEXACT)
                    .scale(Scale.FILL)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .allowHardware(true)
                    .crossfade(120)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Bottom gradient scrim for crisp readable label
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CATEGORY_CARD_SCRIM_BRUSH),
        )

        if (card.isLiveCover) {
            Surface(
                color = ObsidianVoid.copy(alpha = 0.72f),
                shape = RoundedCornerShape(50),
                border = BorderStroke(0.8.dp, PremiumGold.copy(alpha = 0.75f)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .testTag("trending_category_live_badge_${card.id}"),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = PremiumGold,
                        modifier = Modifier.size(11.dp),
                    )
                    Text(
                        text = "LIVE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = PremiumGold,
                    )
                }
            }
        }

        Text(
            text = card.title,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 16.sp,
            ),
            color = InkWhite,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SearchTagChip(
    chip: LiveTagChip,
    onClick: () -> Unit,
) {
    Surface(
        color = ObsidianSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("search_popular_tag_${chip.query.lowercase().replace(' ', '_')}"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Text(
                text = chip.label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = InkWhite,
                maxLines = 1,
            )
            if (chip.count > 0) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "· ${chip.count}",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSubtle,
                )
            }
        }
    }
}

private fun buildTrendingCategoryCards(
    categoryCovers: Map<String, CategoryCoverCandidate>,
    liveFacets: LiveCatalogFacets,
    trendingBanners: List<AppWallpaper>,
): List<TrendingCategoryCardItem> {
    fun normalizeKey(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "")

    val fallbackAnimeLiveCover = liveFacets.categories.firstOrNull()?.coverUrl
    val fallbackAnimeStaticCover = trendingBanners.firstOrNull()?.thumbnailUrl?.takeIf { it.isNotBlank() }

    return AnimeCatalog.exploreCategories.map { topic ->
        val key = normalizeKey(topic.title)
        val visual = EXPLORE_CATEGORY_VISUALS[topic.title] ?: TrendingCategoryVisualStyle(
            title = topic.title,
            icon = Icons.Rounded.AutoAwesome,
            gradientStart = Color(0xFF4A148C),
            gradientMid = Color(0xFF311B92),
            gradientEnd = Color(0xFF0E0B1A),
            glowColor = CrimsonBlade,
        )
        val chosen = categoryCovers[topic.title]
        val isAnime = topic.title.equals("Anime", ignoreCase = true)
        val resolvedCover = chosen?.imageUrl ?: if (isAnime) {
            fallbackAnimeLiveCover ?: fallbackAnimeStaticCover
        } else {
            null
        }
        val isLive = chosen?.isLive ?: (isAnime && fallbackAnimeLiveCover != null)
        val clickKeyword = chosen?.liveKeyword ?: topic.title

        TrendingCategoryCardItem(
            id = key,
            title = topic.title,
            query = topic.query,
            clickKeyword = clickKeyword,
            coverUrl = resolvedCover,
            icon = visual.icon,
            gradientStart = visual.gradientStart,
            gradientMid = visual.gradientMid,
            gradientEnd = visual.gradientEnd,
            glowColor = visual.glowColor,
            isLiveCover = isLive,
        )
    }
}
