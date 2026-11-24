package com.example.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
import com.example.ads.CategoryGridEntry
import com.example.ads.NativeAdCard
import com.example.ads.NativeAdManager
import com.example.ads.NativeAdStyle
import com.example.ads.buildCategoriesWithProgressiveAds
import com.example.domain.model.AnimeCategory
import com.example.domain.model.AnimeSeriesPreview
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianVoid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Displays all 200 curated anime series in collection-row format:
 * each row shows the anime title above a 4-panel portrait wallpaper strip,
 * with the 4th panel displaying the remaining count (+N) when more than 4 wallpapers exist.
 * Only currently visible rows trigger throttled, cache-first preview loads.
 */
@Composable
fun CategoriesScreen(
    categories: List<AnimeCategory>,
    filterText: String,
    categoryPreviews: Map<String, AnimeSeriesPreview> = emptyMap(),
    onFilterChange: (String) -> Unit,
    onVisibleCategoriesChanged: (List<AnimeCategory>) -> Unit = {},
    onOpenCategoryDetail: (category: AnimeCategory, startIndex: Int) -> Unit,
    scrollToTopTick: Int = 0,
    modifier: Modifier = Modifier,
    nativeAdManager: NativeAdManager? = null,
) {
    val context = LocalContext.current
    val gridState = rememberLazyGridState()
    var lastHandledScrollTick by rememberSaveable { mutableIntStateOf(scrollToTopTick) }
    var lastHandledFilterText by rememberSaveable { mutableStateOf(filterText) }
    var visibleSeriesLimit by rememberSaveable(filterText) { mutableIntStateOf(CATEGORIES_PAGE_SIZE) }

    val pagedCategories = remember(categories, visibleSeriesLimit) {
        categories.take(visibleSeriesLimit.coerceAtLeast(CATEGORIES_PAGE_SIZE))
    }
    val hasMoreCategories by remember(categories.size, pagedCategories.size) {
        derivedStateOf { pagedCategories.size < categories.size }
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

    LaunchedEffect(filterText) {
        if (filterText != lastHandledFilterText) {
            lastHandledFilterText = filterText
            visibleSeriesLimit = CATEGORIES_PAGE_SIZE
            if (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0) {
                gridState.scrollToItem(0, 0)
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
    val gridEntries = remember(pagedCategories) {
        buildCategoriesWithProgressiveAds(pagedCategories)
    }

    LaunchedEffect(resolvedAdManager, canRequestAds, pagedCategories.isNotEmpty()) {
        if (canRequestAds && pagedCategories.isNotEmpty()) {
            resolvedAdManager?.preloadBuffer()
        }
    }

    // Automatically paginate Anime 200+ in smooth 18-series chunks as the user approaches the bottom.
    LaunchedEffect(gridState, gridEntries.size, hasMoreCategories) {
        snapshotFlow {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible to gridState.layoutInfo.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (lastVisible, totalCount) ->
                if (hasMoreCategories && totalCount > 0 && lastVisible >= totalCount - 5) {
                    visibleSeriesLimit = (visibleSeriesLimit + CATEGORIES_PAGE_SIZE).coerceAtMost(categories.size)
                }
            }
    }

    // Observe visible viewport rows (plus 2 lookahead) to load 4-panel thumbnail previews safely.
    LaunchedEffect(gridState, gridEntries) {
        snapshotFlow {
            val visibleIndices = gridState.layoutInfo.visibleItemsInfo.map { it.index }
            if (visibleIndices.isEmpty()) {
                gridEntries.filterIsInstance<CategoryGridEntry.CategoryItem>()
                    .take(6)
                    .map { it.category }
            } else {
                val first = visibleIndices.min()
                val last = (visibleIndices.max() + 2).coerceAtMost(gridEntries.lastIndex)
                (first..last).mapNotNull { idx ->
                    (gridEntries.getOrNull(idx) as? CategoryGridEntry.CategoryItem)?.category
                }
            }
        }
            .distinctUntilChanged()
            .collectLatest { visibleCategories ->
                if (visibleCategories.isNotEmpty()) {
                    delay(80L)
                    onVisibleCategoriesChanged(visibleCategories)
                }
            }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid),
    ) {
        val columnCount = if (maxWidth >= 840.dp) 2 else 1

        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "200 Anime Worlds",
                        style = MaterialTheme.typography.headlineLarge,
                        color = InkWhite,
                    )
                    Surface(
                        color = CrimsonBlade.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = "${categories.size} SERIES",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrimsonBlade,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ObsidianSurfaceElevated)
                        .border(1.dp, HairlineBorder, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Filter 200 anime list",
                        tint = InkMuted,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (filterText.isEmpty()) {
                            Text(
                                text = "Filter 200 anime series…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = InkMuted,
                            )
                        }
                        BasicTextField(
                            value = filterText,
                            onValueChange = onFilterChange,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = InkWhite),
                            cursorBrush = SolidColor(CrimsonBlade),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("categories_search_input"),
                        )
                    }
                    if (filterText.isNotEmpty()) {
                        IconButton(
                            onClick = { onFilterChange("") },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear filter",
                                tint = InkMuted,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(columnCount),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("categories_grid"),
            ) {
                itemsIndexed(
                    items = gridEntries,
                    key = { _, entry ->
                        when (entry) {
                            is CategoryGridEntry.CategoryItem -> "cat_${entry.category.id}"
                            is CategoryGridEntry.NativeAdSlot -> entry.slotKey
                        }
                    },
                    span = { _, entry ->
                        when (entry) {
                            is CategoryGridEntry.NativeAdSlot -> GridItemSpan(maxLineSpan)
                            is CategoryGridEntry.CategoryItem -> GridItemSpan(1)
                        }
                    },
                ) { _, entry ->
                    when (entry) {
                        is CategoryGridEntry.CategoryItem -> {
                            AnimeCollectionStripRow(
                                category = entry.category,
                                preview = categoryPreviews[entry.category.id],
                                onTileClick = { tileIndex ->
                                    onOpenCategoryDetail(entry.category, tileIndex)
                                },
                            )
                        }

                        is CategoryGridEntry.NativeAdSlot -> {
                            NativeAdCard(
                                style = NativeAdStyle.GRID_CARD,
                                slotKey = entry.slotKey,
                                nativeAdManager = resolvedAdManager,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                if (hasMoreCategories) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                color = CrimsonBlade,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Loading more anime worlds (${pagedCategories.size}/${categories.size})…",
                                style = MaterialTheme.typography.labelMedium,
                                color = InkMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val CATEGORIES_PAGE_SIZE = 18

@Composable
private fun AnimeCollectionStripRow(
    category: AnimeCategory,
    preview: AnimeSeriesPreview?,
    onTileClick: (Int) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val accentColor = Color(category.accentHex)
    val tileWidthPx = remember(density) { with(density) { 120.dp.roundToPx() } }
    val tileHeightPx = remember(density) { with(density) { 210.dp.roundToPx() } }
    val thumbnails = preview?.thumbnailUrls.orEmpty()
    val remainingBadge = preview?.formatRemainingBadge()
    val isLoading = preview == null || (preview.isLoading && thumbnails.isEmpty())

    val shimmerAlpha = if (isLoading) {
        val transition = rememberInfiniteTransition(label = "category_strip_shimmer")
        transition.animateFloat(
            initialValue = 0.28f,
            targetValue = 0.62f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "shimmer_alpha",
        ).value
    } else {
        0.35f
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("category_card_${category.id}"),
    ) {
        // Anime title header — tapping opens DetailPreviewScreen starting at photo #1
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { onTileClick(0) }
                .padding(top = 2.dp, bottom = 8.dp, start = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = category.title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
                color = InkWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Open ${category.title} collection",
                tint = InkMuted,
                modifier = Modifier.size(20.dp),
            )
        }

        // 4-panel seamless portrait strip — tapping any tile opens DetailPreviewScreen at that exact photo
        val stripShape = RoundedCornerShape(14.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .clip(stripShape)
                .background(ObsidianSurface)
                .border(1.dp, HairlineBorder.copy(alpha = 0.7f), stripShape),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (slotIndex in 0 until 4) {
                val thumbUrl = thumbnails.getOrNull(slotIndex)
                val isLastSlot = slotIndex == 3
                val targetIndex = if (thumbnails.isNotEmpty()) {
                    slotIndex.coerceAtMost(thumbnails.lastIndex)
                } else {
                    slotIndex
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    ObsidianSurfaceElevated,
                                    accentColor.copy(alpha = shimmerAlpha * (0.28f + slotIndex * 0.06f)),
                                    ObsidianVoid,
                                ),
                            ),
                        )
                        .clickable { onTileClick(targetIndex) }
                        .testTag("category_tile_${category.id}_$slotIndex"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!thumbUrl.isNullOrBlank()) {
                        val request = remember(thumbUrl, tileWidthPx, tileHeightPx) {
                            ImageRequest.Builder(context)
                                .data(thumbUrl)
                                .size(tileWidthPx, tileHeightPx)
                                .precision(Precision.INEXACT)
                                .scale(Scale.FILL)
                                .memoryCachePolicy(CachePolicy.ENABLED)
                                .diskCachePolicy(CachePolicy.ENABLED)
                                .allowHardware(false)
                                .crossfade(220)
                                .build()
                        }
                        AsyncImage(
                            model = request,
                            contentDescription = "${category.title} wallpaper ${slotIndex + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // 4th tile overlay displaying remaining count (+116, +22, +1.2K, etc.)
                    if (isLastSlot && remainingBadge != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ObsidianVoid.copy(alpha = 0.56f))
                                .testTag("category_remaining_${category.id}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = remainingBadge,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = (-0.2).sp,
                                ),
                                color = InkWhite,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}
