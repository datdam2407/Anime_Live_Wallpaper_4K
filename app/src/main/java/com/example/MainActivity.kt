package com.example

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.kuroanimelivewallpapers.app.BuildConfig
import com.kuroanimelivewallpapers.app.R
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.ads.AdaptiveBannerAd
import com.example.ads.findActivity
import com.example.domain.model.WallpaperTarget
import com.example.ui.components.RateLimitBreatherDialog
import com.example.ui.navigation.CategoriesRoute
import com.example.ui.navigation.DetailRoute
import com.example.ui.navigation.FavoritesRoute
import com.example.ui.navigation.HomeRoute
import com.example.ui.navigation.SearchRoute
import com.example.ui.navigation.SettingsRoute
import com.example.ui.navigation.SplashRoute
import com.example.ui.screens.CategoriesScreen
import com.example.ui.screens.DetailPreviewScreen
import com.example.ui.screens.FavoritesScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.state.FeedStatus
import com.example.ui.state.MainViewModel
import com.example.ui.state.UiEvent
import kotlinx.coroutines.launch
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkWhite
import com.example.ui.theme.KuroWallTheme
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianVoid
import com.example.util.LiveWallpaperHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            KuroWallTheme {
                KuroWallAppRoot()
            }
        }
    }
}

private data class TopLevelDestination(
    val route: Any,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

@Composable
private fun KuroWallAppRoot(
    viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory),
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val appContainer = remember(context) {
        (context.applicationContext as KuroWallApplication).container
    }
    val consentManager = appContainer.consentManager
    val interstitialAdManager = appContainer.interstitialAdManager
    val nativeAdManager = appContainer.nativeAdManager
    val canRequestAds by consentManager.canRequestAds.collectAsStateWithLifecycle()
    val isPrivacyOptionsRequired by consentManager.privacyOptionsRequired.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var pendingLiveWallpaperComponent by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLiveWallpaperTarget by rememberSaveable { mutableStateOf(WallpaperTarget.BOTH.name) }

    val liveWallpaperLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val expectedComponent = pendingLiveWallpaperComponent?.let(ComponentName::unflattenFromString)
        val target = runCatching { WallpaperTarget.valueOf(pendingLiveWallpaperTarget) }
            .getOrDefault(WallpaperTarget.BOTH)
        pendingLiveWallpaperComponent = null
        pendingLiveWallpaperTarget = WallpaperTarget.BOTH.name
        val applied = LiveWallpaperHelper.wasLiveWallpaperApplied(
            context = context,
            resultCode = result.resultCode,
            expectedComponent = expectedComponent,
        )
        if (applied) {
            interstitialAdManager.markWallpaperApplied()
            viewModel.onLiveWallpaperAppliedSuccess(target)
        }
    }

    fun launchAlternatingLiveWallpaperPicker(target: WallpaperTarget = WallpaperTarget.BOTH) {
        val targetComponent = LiveWallpaperHelper.getNextLiveWallpaperComponent(context)
        pendingLiveWallpaperComponent = targetComponent.flattenToString()
        pendingLiveWallpaperTarget = target.name
        val changeIntent = LiveWallpaperHelper.createChangeLiveWallpaperIntent(targetComponent)
        try {
            liveWallpaperLauncher.launch(changeIntent)
        } catch (_: Exception) {
            runCatching {
                liveWallpaperLauncher.launch(LiveWallpaperHelper.createLiveWallpaperChooserIntent())
            }.onFailure {
                pendingLiveWallpaperComponent = null
                pendingLiveWallpaperTarget = WallpaperTarget.BOTH.name
            }
        }
    }

    // Gather Google UMP GDPR/CCPA consent BEFORE initializing MobileAds and preloading ads.
    // Native Ad buffer preload is deferred until Home/Categories feed is loaded to protect Show Rate.
    LaunchedEffect(activity, consentManager, interstitialAdManager) {
        if (activity != null) {
            consentManager.gatherConsentAndInitializeAds(
                activity = activity,
                onAdsReady = {
                    interstitialAdManager.preload(context)
                },
            )
        }
    }

    DisposableEffect(nativeAdManager, activity) {
        onDispose {
            if (activity?.isChangingConfigurations != true) {
                nativeAdManager.clearBuffer()
            }
        }
    }

    LaunchedEffect(viewModel, interstitialAdManager) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.ShowMessage -> {
                    coroutineScope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(
                            message = event.text,
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
                is UiEvent.LaunchLiveWallpaperChooser -> {
                    launchAlternatingLiveWallpaperPicker(event.target)
                }
                is UiEvent.LaunchWallpaperRequestEmail -> {
                    val subject = "Kuro Wallpaper Request"
                    val body = buildString {
                        if (event.userEmail.isNotBlank()) {
                            append("From: ").append(event.userEmail).append("\n\n")
                        }
                        append("Wallpaper Request:\n").append(event.content)
                    }
                    val mailtoUri = Uri.parse(
                        "mailto:${Uri.encode(event.targetEmail)}" +
                            "?subject=${Uri.encode(subject)}" +
                            "&body=${Uri.encode(body)}",
                    )
                    val emailIntent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
                        putExtra(Intent.EXTRA_EMAIL, arrayOf(event.targetEmail))
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                        putExtra(Intent.EXTRA_TEXT, body)
                        if (activity == null) {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    try {
                        (activity ?: context).startActivity(emailIntent)
                    } catch (_: ActivityNotFoundException) {
                        coroutineScope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(
                                message = "No email app installed. Please send requests to ${event.targetEmail}.",
                                duration = SnackbarDuration.Long,
                            )
                        }
                    } catch (_: Exception) {
                        coroutineScope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(
                                message = "Unable to open email client. Contact ${event.targetEmail}.",
                                duration = SnackbarDuration.Long,
                            )
                        }
                    }
                }
                UiEvent.WallpaperApplied -> {
                    interstitialAdManager.markWallpaperApplied()
                }
            }
        }
    }

    val topDestinations = remember {
        listOf(
            TopLevelDestination(HomeRoute, R.string.nav_home, Icons.Rounded.Explore, Icons.Outlined.Explore),
            TopLevelDestination(CategoriesRoute, R.string.nav_categories, Icons.Rounded.GridView, Icons.Outlined.GridView),
            TopLevelDestination(SearchRoute, R.string.nav_search, Icons.Rounded.Search, Icons.Outlined.Search),
            TopLevelDestination(SettingsRoute, R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
        )
    }

    val isFullScreenRoute = currentDestination?.hasRoute<SplashRoute>() == true ||
            currentDestination?.hasRoute<DetailRoute>() == true

    var homeScrollTopTick by rememberSaveable { mutableIntStateOf(0) }
    var categoriesScrollTopTick by rememberSaveable { mutableIntStateOf(0) }
    var searchScrollTopTick by rememberSaveable { mutableIntStateOf(0) }
    var settingsScrollTopTick by rememberSaveable { mutableIntStateOf(0) }

    fun navigateTopLevel(route: Any, forceScrollToTop: Boolean = false) {
        val isReselectingCurrentTab = currentDestination?.hasRoute(route::class) == true
        if (isReselectingCurrentTab || forceScrollToTop) {
            when (route) {
                HomeRoute -> homeScrollTopTick++
                CategoriesRoute -> categoriesScrollTopTick++
                SearchRoute -> searchScrollTopTick++
                SettingsRoute -> settingsScrollTopTick++
            }
        }
        if (isReselectingCurrentTab) return

        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val configuration = LocalConfiguration.current
    val useNavigationRail = configuration.screenWidthDp >= 600 && !isFullScreenRoute

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianVoid),
    ) {
        Scaffold(
            containerColor = ObsidianVoid,
            contentWindowInsets = if (isFullScreenRoute) WindowInsets(0) else WindowInsets.safeDrawing,
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = if (isFullScreenRoute) Modifier.navigationBarsPadding() else Modifier,
                ) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = ObsidianSurfaceElevated,
                        contentColor = InkWhite,
                        actionColor = CyberCyan,
                    )
                }
            },
            bottomBar = {
                AnimatedVisibility(
                    visible = !isFullScreenRoute && !useNavigationRail,
                    enter = fadeIn(animationSpec = tween(420, easing = FastOutSlowInEasing)) +
                            expandVertically(
                                animationSpec = tween(380, easing = FastOutSlowInEasing),
                                expandFrom = Alignment.Top,
                            ) +
                            slideInVertically(
                                animationSpec = tween(420, easing = FastOutSlowInEasing),
                            ) { it / 2 },
                    exit = fadeOut(animationSpec = tween(160)) +
                            shrinkVertically(animationSpec = tween(160)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ObsidianSurface)
                            .navigationBarsPadding(),
                    ) {
                        NavigationBar(
                            containerColor = ObsidianSurface,
                            tonalElevation = 0.dp,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            topDestinations.forEach { dest ->
                                val selected = currentDestination?.hasRoute(dest.route::class) == true ||
                                    (dest.route == SettingsRoute && currentDestination?.hasRoute<FavoritesRoute>() == true)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(id = dest.labelRes),
                                        )
                                    },
                                    label = { Text(stringResource(id = dest.labelRes)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = CrimsonBlade,
                                        selectedTextColor = InkWhite,
                                        indicatorColor = CrimsonContainer,
                                        unselectedIconColor = InkMuted,
                                        unselectedTextColor = InkMuted,
                                    ),
                                )
                            }
                        }

                        if (canRequestAds) {
                            AdaptiveBannerAd(
                                adUnitId = BuildConfig.ADMOB_BANNER_AD_UNIT_ID,
                                topSafeMargin = 10.dp,
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        containerColor = ObsidianSurface,
                        windowInsets = WindowInsets(0, 0, 0, 0),
                    ) {
                        topDestinations.forEach { dest ->
                            val selected = currentDestination?.hasRoute(dest.route::class) == true ||
                                (dest.route == SettingsRoute && currentDestination?.hasRoute<FavoritesRoute>() == true)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(id = dest.labelRes),
                                    )
                                },
                                label = { Text(stringResource(id = dest.labelRes)) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = CrimsonBlade,
                                    selectedTextColor = InkWhite,
                                    indicatorColor = CrimsonContainer,
                                    unselectedIconColor = InkMuted,
                                    unselectedTextColor = InkMuted,
                                ),
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        NavHost(
                            navController = navController,
                            startDestination = SplashRoute,
                            enterTransition = { fadeIn(tween(240, easing = FastOutSlowInEasing)) },
                            exitTransition = { fadeOut(tween(200, easing = FastOutSlowInEasing)) },
                        ) {
                            composable<SplashRoute>(
                                exitTransition = {
                                    fadeOut(animationSpec = tween(520, easing = FastOutSlowInEasing)) +
                                            scaleOut(
                                                targetScale = 1.07f,
                                                animationSpec = tween(520, easing = FastOutSlowInEasing),
                                            )
                                },
                            ) {
                                val isPreloadComplete by viewModel.isSplashPreloadComplete.collectAsStateWithLifecycle()
                                SplashScreen(
                                    isPreloadComplete = isPreloadComplete,
                                    onFinished = {
                                        navController.navigate(HomeRoute) {
                                            popUpTo(SplashRoute) { inclusive = true }
                                        }
                                    },
                                )
                            }

                            composable<HomeRoute>(
                                enterTransition = {
                                    if (initialState.destination.hasRoute<SplashRoute>()) {
                                        fadeIn(animationSpec = tween(560, easing = FastOutSlowInEasing)) +
                                                scaleIn(
                                                    initialScale = 0.94f,
                                                    animationSpec = tween(560, easing = FastOutSlowInEasing),
                                                )
                                    } else {
                                        fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing))
                                    }
                                },
                            ) {
                                val homeState by viewModel.homeState.collectAsStateWithLifecycle()
                                val favoriteIdsState = viewModel.favoriteIds.collectAsStateWithLifecycle()
                                val homeFeedReady = (homeState.feedStatus as? FeedStatus.Success)
                                    ?.items?.isNotEmpty() == true
                                LaunchedEffect(canRequestAds, homeFeedReady, nativeAdManager) {
                                    if (canRequestAds && homeFeedReady) {
                                        nativeAdManager.preloadBuffer()
                                    }
                                }
                                HomeScreen(
                                    uiState = homeState,
                                    favoriteIdsState = favoriteIdsState,
                                    onSearchInputChange = viewModel::onSearchInputChange,
                                    onSubmitSearch = { viewModel.submitSearch() },
                                    onSelectSuggestion = viewModel::selectSearchSuggestion,
                                    onSelectTrendingTopic = viewModel::selectTrendingTopic,
                                    onToggleStaticCategory = viewModel::toggleStaticCategory,
                                    onToggleStaticTag = viewModel::toggleStaticTag,
                                    onClearStaticFilters = viewModel::clearStaticFilters,
                                    onToggleLiveCategory = viewModel::toggleLiveCategory,
                                    onToggleLiveTag = viewModel::toggleLiveTag,
                                    onClearLiveFilters = viewModel::clearLiveFilters,
                                    onSelectTab = viewModel::selectTab,
                                    onApplyFilter = viewModel::applyFilter,
                                    onRefresh = viewModel::refreshFeed,
                                    onLoadMore = viewModel::loadMore,
                                    onOpenDetail = { index ->
                                        navController.navigate(
                                            DetailRoute(
                                                startIndex = index,
                                                feedSource = DetailRoute.FEED_MAIN,
                                            ),
                                        )
                                    },
                                    onToggleFavorite = viewModel::toggleFavorite,
                                    scrollToTopTick = homeScrollTopTick,
                                )
                            }

                            composable<CategoriesRoute> {
                                val categories by viewModel.filteredCategories.collectAsStateWithLifecycle()
                                val categoryFilterText by viewModel.categoryFilterText.collectAsStateWithLifecycle()
                                val categoryPreviews by viewModel.animeCategoryPreviews.collectAsStateWithLifecycle()
                                CategoriesScreen(
                                    categories = categories,
                                    filterText = categoryFilterText,
                                    categoryPreviews = categoryPreviews,
                                    onFilterChange = viewModel::updateCategorySearch,
                                    onVisibleCategoriesChanged = viewModel::requestAnimeCategoryPreviews,
                                    onOpenCategoryDetail = { category, startIndex ->
                                        viewModel.openAnimeCategoryInDetail(category)
                                        navController.navigate(
                                            DetailRoute(
                                                startIndex = startIndex,
                                                feedSource = DetailRoute.FEED_COLLECTION,
                                            ),
                                        )
                                    },
                                    scrollToTopTick = categoriesScrollTopTick,
                                    nativeAdManager = nativeAdManager,
                                )
                            }

                            composable<SearchRoute> {
                                val liveFacets by viewModel.searchLiveFacets.collectAsStateWithLifecycle()
                                val verifiedStaticTags by viewModel.searchVerifiedStaticTags.collectAsStateWithLifecycle()
                                val trendingBanners by viewModel.searchTrendingBanners.collectAsStateWithLifecycle()
                                val searchCategoryCovers by viewModel.searchCategoryCovers.collectAsStateWithLifecycle()
                                SearchScreen(
                                    liveFacets = liveFacets,
                                    verifiedStaticTags = verifiedStaticTags,
                                    trendingBanners = trendingBanners,
                                    categoryCovers = searchCategoryCovers,
                                    onComputeSuggestions = viewModel::getUnifiedSearchSuggestions,
                                    onSearchSubmit = { query, isColorSearch, preferLive, facetType ->
                                        viewModel.searchFromSearchScreen(
                                            query = query,
                                            isColorSearch = isColorSearch,
                                            preferLive = preferLive,
                                            facetType = facetType,
                                        )
                                        navigateTopLevel(HomeRoute, forceScrollToTop = true)
                                    },
                                    onScreenEntered = viewModel::onSearchScreenEntered,
                                    scrollToTopTick = searchScrollTopTick,
                                    nativeAdManager = nativeAdManager,
                                )
                            }

                            composable<FavoritesRoute> {
                                val favorites by viewModel.favorites.collectAsStateWithLifecycle()
                                FavoritesScreen(
                                    favorites = favorites,
                                    onOpenDetail = { index, feedSource ->
                                        navController.navigate(
                                            DetailRoute(
                                                startIndex = index,
                                                feedSource = feedSource,
                                            ),
                                        )
                                    },
                                    onToggleFavorite = viewModel::toggleFavorite,
                                    onExploreClick = { navigateTopLevel(HomeRoute) },
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable<SettingsRoute> {
                                val settings by viewModel.settings.collectAsStateWithLifecycle()
                                val favorites by viewModel.favorites.collectAsStateWithLifecycle()
                                LaunchedEffect(consentManager) {
                                    consentManager.refreshPrivacyOptionsStatus()
                                }
                                SettingsScreen(
                                    settings = settings,
                                    savedCount = favorites.size,
                                    onOpenSaved = {
                                        navController.navigate(FavoritesRoute) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onResolutionSelected = viewModel::updatePreferredResolution,
                                    onSubmitWallpaperRequest = viewModel::submitWallpaperRequest,
                                    showPrivacyOptionsButton = isPrivacyOptionsRequired,
                                    onOpenPrivacyOptions = {
                                        activity?.let { act ->
                                            consentManager.showPrivacyOptionsForm(act) { formError ->
                                                if (formError != null) {
                                                    coroutineScope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            message = context.getString(R.string.settings_ad_privacy_error),
                                                            duration = SnackbarDuration.Short,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    scrollToTopTick = settingsScrollTopTick,
                                )
                            }

                            composable<DetailRoute>(
                                enterTransition = {
                                    fadeIn(animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                                            scaleIn(
                                                initialScale = 0.96f,
                                                animationSpec = tween(280, easing = FastOutSlowInEasing),
                                            )
                                },
                                popExitTransition = {
                                    fadeOut(animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                                            scaleOut(
                                                targetScale = 0.96f,
                                                animationSpec = tween(240, easing = FastOutSlowInEasing),
                                            )
                                },
                            ) { entry ->
                                val route = entry.toRoute<DetailRoute>()
                                val actionState by viewModel.detailActionState.collectAsStateWithLifecycle()
                                val favoriteIdsState = viewModel.favoriteIds.collectAsStateWithLifecycle()
                                val settings by viewModel.settings.collectAsStateWithLifecycle()
                                val isCollectionSource = route.feedSource == DetailRoute.FEED_COLLECTION
                                val isMainSource = route.feedSource == DetailRoute.FEED_MAIN

                                val feed: List<com.example.domain.model.AppWallpaper>
                                val totalCountHint: Int?
                                val isLoadingFeed: Boolean
                                val hasMore: Boolean
                                val isLoadingMore: Boolean

                                when {
                                    isCollectionSource -> {
                                        val collectionDetailState by viewModel.collectionDetailState.collectAsStateWithLifecycle()
                                        feed = collectionDetailState.items
                                        totalCountHint = collectionDetailState.totalCount
                                        isLoadingFeed = collectionDetailState.isLoadingInitial
                                        hasMore = collectionDetailState.hasMore
                                        isLoadingMore = collectionDetailState.isLoadingMore
                                    }
                                    isMainSource -> {
                                        val homeState by viewModel.homeState.collectAsStateWithLifecycle()
                                        feed = remember(homeState.feedStatus, homeState.trendingBanners) {
                                            when (val status = homeState.feedStatus) {
                                                is FeedStatus.Success -> status.items
                                                else -> homeState.trendingBanners
                                            }
                                        }
                                        totalCountHint = null
                                        isLoadingFeed = false
                                        hasMore = homeState.hasMore
                                        isLoadingMore = homeState.isLoadingMore
                                    }
                                    else -> {
                                        val favorites by viewModel.favorites.collectAsStateWithLifecycle()
                                        feed = remember(route.feedSource, favorites) {
                                            when (route.feedSource) {
                                                DetailRoute.FEED_FAVORITES_STATIC -> favorites.filter { !it.isLive }
                                                DetailRoute.FEED_FAVORITES_LIVE -> favorites.filter { it.isLive }
                                                else -> favorites
                                            }
                                        }
                                        totalCountHint = null
                                        isLoadingFeed = false
                                        hasMore = false
                                        isLoadingMore = false
                                    }
                                }

                                DetailPreviewScreen(
                                    wallpapers = feed,
                                    initialIndex = route.startIndex,
                                    favoriteIdsState = favoriteIdsState,
                                    isProcessing = actionState.isProcessingAction,
                                    processingLabel = actionState.processingLabel,
                                    liveDownloadProgress = actionState.liveDownloadProgress,
                                    appliedSuccessTarget = actionState.appliedSuccessTarget,
                                    muteLiveAudio = settings.muteLiveWallpaper,
                                    onBack = {
                                        interstitialAdManager.onDetailBackClicked(activity) {
                                            navController.popBackStack()
                                        }
                                    },
                                    onSetStaticWallpaper = viewModel::setStaticWallpaper,
                                    onSetLiveWallpaper = viewModel::applyLiveWallpaper,
                                    onToggleFavorite = viewModel::toggleFavorite,
                                    onDismissAppliedSuccess = viewModel::dismissAppliedSuccess,
                                    onDetailImageViewed = { wallpaperId ->
                                        interstitialAdManager.onDetailImageViewed(wallpaperId)
                                    },
                                    totalCountHint = totalCountHint,
                                    isLoadingFeed = isLoadingFeed,
                                    hasMore = hasMore,
                                    isLoadingMore = isLoadingMore,
                                    onLoadMore = {
                                        when {
                                            isCollectionSource -> viewModel.loadMoreCollectionDetail()
                                            isMainSource -> viewModel.loadMore()
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (!isFullScreenRoute && useNavigationRail && canRequestAds) {
                        AdaptiveBannerAd(
                            adUnitId = BuildConfig.ADMOB_BANNER_AD_UNIT_ID,
                            topSafeMargin = 10.dp,
                        )
                    }
                }
            }
        }

        RateLimitBreatherHost(
            viewModel = viewModel,
            isSplashRoute = currentDestination?.hasRoute<SplashRoute>() == true,
        )
    }
}

@Composable
private fun RateLimitBreatherHost(
    viewModel: MainViewModel,
    isSplashRoute: Boolean,
) {
    val rateLimitDistraction by viewModel.rateLimitDistraction.collectAsStateWithLifecycle()
    rateLimitDistraction?.let { prompt ->
        if (!isSplashRoute) {
            RateLimitBreatherDialog(
                prompt = prompt,
                onDismiss = viewModel::dismissRateLimitDistraction,
            )
        }
    }
}

