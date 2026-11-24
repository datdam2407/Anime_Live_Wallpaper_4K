package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayCircleFilled
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.kuroanimelivewallpapers.app.R
import com.example.ads.AdMobConstants
import com.example.ads.NativeAdCard
import com.example.ads.NativeAdStyle
import com.example.ads.WallpaperGridEntry
import com.example.ads.buildFeedWithNativeAds
import com.example.domain.model.AnimeCatalog
import com.example.domain.model.AppWallpaper
import com.example.domain.model.LiveSearchSuggestion
import com.example.domain.model.WallpaperFilter
import com.example.ui.components.FilterBottomSheet
import com.example.ui.components.LiveCatalogFilterSection
import com.example.ui.components.PremiumGoldBadge
import com.example.ui.components.PremiumGoldBorderBrush
import com.example.ui.components.SearchSuggestionStrip
import com.example.ui.components.ShimmerBox
import com.example.ui.components.SilverBorderBrush
import com.example.ui.components.StaticCatalogFilterSection
import com.example.ui.components.WallpaperStaggeredCard
import com.example.ui.state.FeedStatus
import com.example.ui.state.HomeUiState
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianSurfaceHighest
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PremiumGold
import com.example.ui.theme.SilverMetallic
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    favoriteIdsState: State<Set<String>>,
    onSearchInputChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onSelectSuggestion: (LiveSearchSuggestion) -> Unit,
    onSelectTrendingTopic: (String) -> Unit,
    onToggleStaticCategory: (String) -> Unit = {},
    onToggleStaticTag: (String) -> Unit = {},
    onClearStaticFilters: () -> Unit = {},
    onToggleLiveCategory: (String) -> Unit = {},
    onToggleLiveTag: (String) -> Unit = {},
    onClearLiveFilters: () -> Unit = {},
    onSelectTab: (Boolean) -> Unit,
    onApplyFilter: (WallpaperFilter) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenDetail: (Int) -> Unit,
    onToggleFavorite: (AppWallpaper) -> Unit,
    scrollToTopTick: Int = 0,
    modifier: Modifier = Modifier,
) {
    var showFilterSheet by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    if (showFilterSheet) {
        FilterBottomSheet(
            currentFilter = uiState.filter,
            onDismiss = { showFilterSheet = false },
            onApply = onApplyFilter,
        )
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianVoid),
    ) {
        val columnCount = when {
            maxWidth >= 840.dp -> 4
            maxWidth >= 600.dp -> 3
            else -> 2
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Header: Search Bar + Filter Icon
            HomeSearchHeader(
                query = uiState.searchInput,
                isLiveTab = uiState.isLiveTab,
                activeFilterLabel = uiState.filter.resolution.label,
                onQueryChange = onSearchInputChange,
                onSearch = {
                    focusManager.clearFocus()
                    onSubmitSearch()
                },
                onClear = {
                    onSearchInputChange("")
                },
                onOpenFilter = { showFilterSheet = true },
            )

            // Instant Search Autocomplete Suggestions (Category / Series / #Tag / Title)
            SearchSuggestionStrip(
                suggestions = uiState.searchSuggestions,
                onSelectSuggestion = { suggestion ->
                    focusManager.clearFocus()
                    onSelectSuggestion(suggestion)
                },
            )

            // 2 Tabs: Static (Tĩnh) | Live (Động)
            StaticLiveTabs(
                isLiveTab = uiState.isLiveTab,
                onSelectTab = onSelectTab,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            val gridEntries = remember(uiState.feedStatus) {
                val items = (uiState.feedStatus as? FeedStatus.Success)?.items.orEmpty()
                if (items.isEmpty()) {
                    emptyList()
                } else {
                    buildFeedWithNativeAds(
                        wallpapers = items,
                        interval = AdMobConstants.GRID_NATIVE_AD_INTERVAL,
                    )
                }
            }

            val gridState = rememberLazyStaggeredGridState()
            var lastHandledFeedScrollSignal by rememberSaveable { mutableIntStateOf(uiState.scrollToTopSignal) }
            var lastHandledNavScrollTick by rememberSaveable { mutableIntStateOf(scrollToTopTick) }

            // Snap cleanly to the top whenever the user switches 4K Artworks <-> Live Wallpapers,
            // searches, picks a category/#tag/filter, or refreshes. Back-from-Detail preserves position.
            LaunchedEffect(uiState.scrollToTopSignal) {
                if (uiState.scrollToTopSignal != lastHandledFeedScrollSignal) {
                    lastHandledFeedScrollSignal = uiState.scrollToTopSignal
                    if (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0) {
                        gridState.scrollToItem(0, 0)
                    }
                }
            }

            // Return to top when the user taps the Discover tab in bottom navigation or navigation rail.
            LaunchedEffect(scrollToTopTick) {
                if (scrollToTopTick != lastHandledNavScrollTick) {
                    lastHandledNavScrollTick = scrollToTopTick
                    if (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0) {
                        if (gridState.firstVisibleItemIndex <= 15) {
                            gridState.animateScrollToItem(0)
                        } else {
                            gridState.scrollToItem(0, 0)
                        }
                    }
                }
            }

            val currentFeedCountState = rememberUpdatedState(
                (uiState.feedStatus as? FeedStatus.Success)?.items?.size ?: 0,
            )
            val canLoadMoreState = rememberUpdatedState(uiState.hasMore && !uiState.isLoadingMore)
            var lastLoadMoreTriggeredAtCount by remember(
                uiState.activeQuery,
                uiState.isLiveTab,
                uiState.liveFilter,
                uiState.staticFilter,
                uiState.filter,
            ) {
                mutableIntStateOf(-1)
            }

            LaunchedEffect(uiState.isRefreshing) {
                if (uiState.isRefreshing) {
                    lastLoadMoreTriggeredAtCount = -1
                }
            }

            LaunchedEffect(
                gridState,
                uiState.activeQuery,
                uiState.isLiveTab,
                uiState.liveFilter,
                uiState.staticFilter,
                uiState.filter,
            ) {
                snapshotFlow {
                    val layoutInfo = gridState.layoutInfo
                    val totalItems = layoutInfo.totalItemsCount
                    val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    val feedCount = currentFeedCountState.value
                    val canLoadMore = canLoadMoreState.value
                    canLoadMore &&
                            feedCount > 0 &&
                            totalItems > 0 &&
                            lastVisibleIndex >= totalItems - 4 &&
                            lastLoadMoreTriggeredAtCount != feedCount
                }
                    .distinctUntilChanged()
                    .collect { shouldTrigger ->
                        if (shouldTrigger) {
                            lastLoadMoreTriggeredAtCount = currentFeedCountState.value
                            onLoadMore()
                        }
                    }
            }

            val coroutineScope = rememberCoroutineScope()
            val showScrollToTop by remember(gridState) {
                derivedStateOf { gridState.firstVisibleItemIndex > 15 }
            }

            val pullToRefreshState = rememberPullToRefreshState()

            // Body: Pull-to-Refresh + Auto-scrolling Trending Carousel + Trending Chips + Staggered Grid
            PullToRefreshBox(
                isRefreshing = uiState.isRefreshing,
                onRefresh = onRefresh,
                state = pullToRefreshState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullToRefreshState,
                        isRefreshing = uiState.isRefreshing,
                        containerColor = ObsidianSurfaceElevated,
                        color = if (uiState.isLiveTab) PremiumGold else CrimsonBlade,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .testTag("home_pull_to_refresh"),
            ) {
                LazyVerticalStaggeredGrid(
                    state = gridState,
                    columns = StaggeredGridCells.Fixed(columnCount),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalItemSpacing = 12.dp,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("home_staggered_grid"),
                ) {
                    item(key = "home_trending_carousel", span = StaggeredGridItemSpan.FullLine) {
                        TrendingCarouselBanner(
                            banners = uiState.trendingBanners,
                            onBannerClick = { index, banner ->
                                if (banner != null && uiState.feedStatus is FeedStatus.Success) {
                                    val found = uiState.feedStatus.items.indexOfFirst { it.id == banner.id }
                                    if (found >= 0) {
                                        onOpenDetail(found)
                                    } else {
                                        onSelectTrendingTopic(banner.animeSeries)
                                    }
                                } else {
                                    val topic = AnimeCatalog.trendingTopics.getOrElse(index) {
                                        AnimeCatalog.trendingTopics.first()
                                    }
                                    onSelectTrendingTopic(topic.title)
                                }
                            },
                        )
                    }

                    item(key = "home_catalog_filter_section", span = StaggeredGridItemSpan.FullLine) {
                        val currentResultCount = (uiState.feedStatus as? FeedStatus.Success)?.items?.size
                        if (uiState.isLiveTab) {
                            LiveCatalogFilterSection(
                                filterState = uiState.liveFilter,
                                facets = uiState.liveCatalogFacets,
                                resultCount = uiState.liveTotalCount ?: currentResultCount,
                                onToggleCategory = { category ->
                                    focusManager.clearFocus()
                                    onToggleLiveCategory(category)
                                },
                                onToggleTag = { tagQuery ->
                                    focusManager.clearFocus()
                                    onToggleLiveTag(tagQuery)
                                },
                                onClearFilter = {
                                    focusManager.clearFocus()
                                    onClearLiveFilters()
                                },
                                onRefresh = onRefresh,
                            )
                        } else {
                            StaticCatalogFilterSection(
                                filterState = uiState.staticFilter,
                                hiddenEmptyCategories = uiState.hiddenEmptyStaticCategories,
                                verifiedTags = uiState.verifiedStaticTags,
                                tagRotationVersion = uiState.staticTagRotationVersion,
                                onToggleCategory = { category ->
                                    focusManager.clearFocus()
                                    onToggleStaticCategory(category)
                                },
                                onToggleTag = { tagQuery ->
                                    focusManager.clearFocus()
                                    onToggleStaticTag(tagQuery)
                                },
                                onClearFilter = {
                                    focusManager.clearFocus()
                                    onClearStaticFilters()
                                },
                                onRefresh = onRefresh,
                            )
                        }
                    }

                    if (!uiState.bannerMessage.isNullOrBlank()) {
                        item(span = StaggeredGridItemSpan.FullLine) {
                            RateLimitBanner(message = uiState.bannerMessage)
                        }
                    }

                    when (val status = uiState.feedStatus) {
                        FeedStatus.Loading -> {
                            val shimmerRatio = if (uiState.isLiveTab) 0.58f else 0.66f
                            items(8) {
                                ShimmerBox(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(shimmerRatio)
                                        .clip(RoundedCornerShape(18.dp)),
                                )
                            }
                        }

                        is FeedStatus.Error -> {
                            item(span = StaggeredGridItemSpan.FullLine) {
                                EmptyFeedCard(
                                    message = status.message,
                                    isLive = uiState.isLiveTab,
                                    onRetry = onRefresh,
                                    onBrowseAll = {
                                        if (uiState.isLiveTab) onClearLiveFilters() else onClearStaticFilters()
                                    },
                                )
                            }
                        }

                        is FeedStatus.Success -> {
                            itemsIndexed(
                                items = gridEntries,
                                key = { _, entry ->
                                    when (entry) {
                                        is WallpaperGridEntry.WallpaperItem -> entry.wallpaper.id
                                        is WallpaperGridEntry.NativeAdSlot -> entry.slotKey
                                    }
                                },
                                span = { _, entry ->
                                    when (entry) {
                                        is WallpaperGridEntry.NativeAdSlot -> StaggeredGridItemSpan.FullLine
                                        is WallpaperGridEntry.WallpaperItem -> StaggeredGridItemSpan.SingleLane
                                    }
                                },
                            ) { _, entry ->
                                when (entry) {
                                    is WallpaperGridEntry.NativeAdSlot -> {
                                        NativeAdCard(
                                            style = NativeAdStyle.GRID_CARD,
                                            slotKey = entry.slotKey,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }

                                    is WallpaperGridEntry.WallpaperItem -> {
                                        val index = entry.originalIndex
                                        val wallpaper = entry.wallpaper
                                        // Uniform card dimensions across each feed for clean visual alignment:
                                        // Live Wallpapers: uniform 0.58f (9:15.5 phone portrait);
                                        // 4K Artworks: uniform 0.66f (2:3 showcase frame).
                                        val uniformCardRatio = if (wallpaper.isLive) 0.58f else 0.66f
                                        val isFavorite by remember(wallpaper.id, favoriteIdsState) {
                                            derivedStateOf { wallpaper.id in favoriteIdsState.value }
                                        }
                                        WallpaperStaggeredCard(
                                            wallpaper = wallpaper,
                                            staggeredRatio = uniformCardRatio,
                                            isFavorite = isFavorite,
                                            onClick = { onOpenDetail(index) },
                                            onToggleFavorite = { onToggleFavorite(wallpaper) },
                                        )
                                    }
                                }
                            }

                            if (uiState.isLoadingMore || uiState.hasMore) {
                                item(span = StaggeredGridItemSpan.FullLine) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (uiState.isLoadingMore) {
                                            androidx.compose.material3.CircularProgressIndicator(
                                                color = CrimsonBlade,
                                                modifier = Modifier.size(28.dp),
                                            )
                                        } else {
                                            Button(
                                                onClick = onLoadMore,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = ObsidianSurfaceElevated,
                                                    contentColor = InkWhite,
                                                ),
                                                shape = RoundedCornerShape(14.dp),
                                            ) {
                                                Text("Load More Wallpapers")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Top-Center "Scroll to Top" Floating Action Button when deep in the feed (> 15 items)
                androidx.compose.animation.AnimatedVisibility(
                    visible = showScrollToTop,
                    enter = fadeIn() + slideInVertically { -it },
                    exit = fadeOut() + slideOutVertically { -it },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp),
                ) {
                    FloatingActionButton(
                        onClick = {
                            coroutineScope.launch {
                                gridState.animateScrollToItem(0)
                            }
                        },
                        shape = CircleShape,
                        containerColor = ObsidianSurfaceElevated.copy(alpha = 0.94f),
                        contentColor = CrimsonBlade,
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 8.dp,
                            pressedElevation = 12.dp,
                        ),
                        modifier = Modifier
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .border(1.dp, CrimsonBlade.copy(alpha = 0.55f), CircleShape)
                            .testTag("scroll_to_top_fab"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowUpward,
                            contentDescription = "Scroll to top",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSearchHeader(
    query: String,
    isLiveTab: Boolean,
    activeFilterLabel: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ObsidianSurfaceElevated)
                .border(
                    width = 1.dp,
                    color = if (isLiveTab) PremiumGold.copy(alpha = 0.35f) else HairlineBorder,
                    shape = RoundedCornerShape(16.dp),
                )
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = "Search anime",
                tint = if (isLiveTab) PremiumGold else CrimsonBlade,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(
                            id = if (isLiveTab) R.string.search_hint_live else R.string.search_hint,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
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
                    cursorBrush = SolidColor(if (isLiveTab) PremiumGold else CrimsonBlade),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("home_search_input"),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("home_search_clear"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Clear search",
                        tint = InkMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        FilledTonalIconButton(
            onClick = onOpenFilter,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = ObsidianSurfaceElevated,
                contentColor = InkWhite,
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .size(48.dp)
                .border(1.dp, HairlineBorder, RoundedCornerShape(16.dp))
                .testTag("filter_button"),
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "Open quality filter ($activeFilterLabel)",
                tint = CyberCyan,
            )
        }
    }
}

@Composable
private fun StaticLiveTabs(
    isLiveTab: Boolean,
    onSelectTab: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ObsidianSurface)
            .border(1.dp, HairlineBorder, RoundedCornerShape(16.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val staticSelected = !isLiveTab
        val staticTabShape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .weight(0.90f)
                .fillMaxSize()
                .clip(staticTabShape)
                .background(
                    if (staticSelected) {
                        Brush.horizontalGradient(listOf(CrimsonBlade, Color(0xFFD5004F)))
                    } else {
                        SolidColor(Color.Transparent)
                    },
                )
                .then(
                    if (staticSelected) {
                        Modifier.border(1.2.dp, SilverBorderBrush, staticTabShape)
                    } else {
                        Modifier.border(0.8.dp, SilverMetallic.copy(alpha = 0.36f), staticTabShape)
                    },
                )
                .clickable { onSelectTab(false) }
                .testTag("tab_static"),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = null,
                    tint = if (staticSelected) InkWhite else InkMuted,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(id = R.string.tab_static),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (staticSelected) InkWhite else InkMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        val liveSelected = isLiveTab
        val liveTabShape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .weight(1.12f)
                .fillMaxSize()
                .clip(liveTabShape)
                .background(
                    if (liveSelected) {
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xFF431873), Color(0xFF7B1540)),
                        )
                    } else {
                        SolidColor(Color.Transparent)
                    },
                )
                .then(
                    if (liveSelected) {
                        Modifier.border(1.2.dp, PremiumGoldBorderBrush, liveTabShape)
                    } else {
                        Modifier.border(0.8.dp, PremiumGold.copy(alpha = 0.42f), liveTabShape)
                    },
                )
                .clickable { onSelectTab(true) }
                .testTag("tab_live"),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                Text(
                    text = stringResource(id = R.string.tab_live),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = if (liveSelected) InkWhite else InkMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(4.dp))
                PremiumGoldBadge(
                    label = "PREMIUM",
                    compact = true,
                    modifier = Modifier.testTag("tab_live_premium_badge"),
                )
            }
        }
    }
}

@Composable
private fun TrendingCarouselBanner(
    banners: List<AppWallpaper>,
    onBannerClick: (Int, AppWallpaper?) -> Unit,
) {
    val pageCount = if (banners.isNotEmpty()) banners.size else AnimeCatalog.trendingTopics.size
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(pagerState, pageCount, lifecycleOwner) {
        if (pageCount <= 1) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { pagerState.isScrollInProgress }
                .collectLatest { isScrolling ->
                    if (!isScrolling) {
                        while (true) {
                            delay(4000L)
                            val next = (pagerState.currentPage + 1) % pageCount
                            pagerState.animateScrollToPage(next)
                        }
                    }
                }
        }
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val bannerHeightPx = remember(density) { with(density) { 176.dp.roundToPx() } }
    val bannerWidthPx = remember(density) { with(density) { 420.dp.roundToPx() } }

    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            key = { page -> banners.getOrNull(page)?.id ?: "topic_$page" },
            contentPadding = PaddingValues(end = 24.dp),
            pageSpacing = 12.dp,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .testTag("trending_carousel"),
        ) { page ->
            val banner = banners.getOrNull(page)
            val topicTitle = AnimeCatalog.trendingTopics.getOrNull(page)?.title ?: "Trending Anime"

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(22.dp))
                    .background(ObsidianSurfaceElevated)
                    .border(1.dp, HairlineBorder, RoundedCornerShape(22.dp))
                    .clickable { onBannerClick(page, banner) },
            ) {
                if (banner != null) {
                    val bannerRequest = remember(banner.thumbnailUrl, bannerWidthPx, bannerHeightPx) {
                        ImageRequest.Builder(context)
                            .data(banner.thumbnailUrl)
                            .size(bannerWidthPx, bannerHeightPx)
                            .precision(Precision.INEXACT)
                            .scale(Scale.FILL)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .allowHardware(false)
                            .crossfade(160)
                            .build()
                    }
                    AsyncImage(
                        model = bannerRequest,
                        contentDescription = "Trending anime banner",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.img_hero_anime_night),
                        contentDescription = "Trending anime banner",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.horizontalGradient(
                                0.0f to ObsidianVoid.copy(alpha = 0.92f),
                                0.58f to ObsidianVoid.copy(alpha = 0.45f),
                                1.0f to Color.Transparent,
                            ),
                        ),
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(18.dp),
                ) {
                    Surface(
                        color = CrimsonBlade.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CrimsonBlade.copy(alpha = 0.6f)),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = CrimsonBlade,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "TRENDING NOW",
                                style = MaterialTheme.typography.labelSmall,
                                color = InkWhite,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = banner?.animeSeries?.takeIf {
                            it.isNotBlank() &&
                                    !it.equals("anime", true) &&
                                    !it.equals("Trending Anime", true) &&
                                    !it.equals("Hot Anime", true)
                        } ?: topicTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = InkWhite,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Carousel dot indicators
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(pageCount.coerceAtMost(10)) { index ->
                val selected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .height(6.dp)
                        .width(if (selected) 20.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (selected) CrimsonBlade else HairlineBorder),
                )
            }
        }
    }
}

@Composable
private fun RateLimitBanner(message: String) {
    Surface(
        color = CrimsonBlade.copy(alpha = 0.14f),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CrimsonBlade.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = CrimsonBlade,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = InkWhite,
            )
        }
    }
}

@Composable
private fun EmptyFeedCard(
    message: String,
    isLive: Boolean,
    onRetry: () -> Unit,
    onBrowseAll: () -> Unit,
) {
    Surface(
        color = ObsidianSurfaceElevated,
        shape = RoundedCornerShape(22.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (isLive) Icons.Rounded.PlayCircleFilled else Icons.Rounded.Image,
                contentDescription = null,
                tint = CrimsonBlade,
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = InkWhite,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onBrowseAll,
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlade),
                ) {
                    Text("Hot Anime", color = InkWhite)
                }
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = ObsidianSurfaceHighest),
                ) {
                    Text("Retry", color = InkWhite)
                }
            }
        }
    }
}
