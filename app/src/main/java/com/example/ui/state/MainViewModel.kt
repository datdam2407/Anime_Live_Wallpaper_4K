package com.example.ui.state

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import com.example.KuroWallApplication
import com.example.data.local.UserSettings
import com.example.data.repository.WallpaperRepository
import com.example.domain.model.AnimeCatalog
import com.example.domain.model.AnimeCategory
import com.example.domain.model.AppWallpaper
import com.example.domain.model.ResolutionTier
import com.example.domain.model.WallpaperFilter
import com.example.domain.model.WallpaperTarget
import com.example.ui.screens.SPLASH_WALLPAPER_JPG_URL
import com.example.ui.screens.SPLASH_WALLPAPER_THUMB_URL
import com.example.data.remote.RateLimitDistractionPrompt
import com.example.util.LiveDownloadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface FeedStatus {
    data object Loading : FeedStatus
    data class Success(val items: List<AppWallpaper>) : FeedStatus
    data class Error(val message: String) : FeedStatus
}

data class HomeUiState(
    val activeQuery: String = WallpaperRepository.HOME_TRENDING_KEY,
    val searchInput: String = "",
    val isLiveTab: Boolean = false,
    val filter: WallpaperFilter = WallpaperFilter(),
    val feedStatus: FeedStatus = FeedStatus.Loading,
    val trendingBanners: List<AppWallpaper> = emptyList(),
    val bannerMessage: String? = null,
    val isRefreshing: Boolean = false,
    val currentPage: Int = 1,
    val hasMore: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isProcessingAction: Boolean = false,
    val processingLabel: String? = null,
    val liveDownloadProgress: Int? = null,
    val appliedSuccessTarget: String? = null,
    val isSplashPreloadComplete: Boolean = false,
)

sealed interface UiEvent {
    data class ShowMessage(val text: String) : UiEvent
    data class LaunchLiveWallpaperChooser(val target: WallpaperTarget = WallpaperTarget.BOTH) : UiEvent
    data class LaunchWallpaperRequestEmail(
        val targetEmail: String,
        val userEmail: String,
        val content: String,
    ) : UiEvent
    data object WallpaperApplied : UiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as KuroWallApplication).container
    private val repository = container.wallpaperRepository
    private val settingsStore = container.settingsDataStore
    private val bitmapCropper = container.bitmapCropper
    private val mediaDownloadManager = container.mediaDownloadManager
    private val interstitialAdManager = container.interstitialAdManager
    private val nativeAdManager = container.nativeAdManager
    private val consentManager = container.consentManager
    private val wallhavenRateLimiter = container.wallhavenRateLimiter

    private val _rawState = MutableStateFlow(HomeUiState())
    private val _isSplashPreloadComplete = MutableStateFlow(false)
    val isSplashPreloadComplete: StateFlow<Boolean> = _isSplashPreloadComplete.asStateFlow()
    private val _categories = MutableStateFlow(AnimeCatalog.allTwoHundred)
    private val _categoryFilterText = MutableStateFlow("")
    private var feedJob: Job? = null
    private var loadMoreJob: Job? = null
    private var searchDebounceJob: Job? = null
    private var startupPrefetchStarted = false
    private val _events = MutableSharedFlow<UiEvent>()
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    val favoriteIds: StateFlow<Set<String>> = repository.observeFavoriteIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val favorites: StateFlow<List<AppWallpaper>> = repository.observeFavorites()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<UserSettings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserSettings())

    // Expose _rawState directly without cloning lists on every favorite toggle;
    // favorite state is observed independently at the item level.
    val homeState: StateFlow<HomeUiState> = _rawState.asStateFlow()

    val rateLimitDistraction: StateFlow<RateLimitDistractionPrompt?> =
        wallhavenRateLimiter.distractionPrompt

    val categoryFilterText: StateFlow<String> = _categoryFilterText.asStateFlow()

    val filteredCategories: StateFlow<List<AnimeCategory>> =
        combine(_categories, _categoryFilterText) { all, query ->
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                all
            } else {
                all.filter {
                    it.title.contains(trimmed, ignoreCase = true) ||
                            it.genreTag.contains(trimmed, ignoreCase = true)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AnimeCatalog.allTwoHundred)

    init {
        runSplashPreloadQueue()
    }

    /**
     * Executes during the 5-second Splash Screen window:
     * 1. Waits for Google UMP GDPR/CCPA consent before preloading Interstitial & Native Ads on Main.
     * 2. Warms Live MP4 catalog, prunes expired Room cache, and pre-decodes Splash hero art.
     * 3. Fetches Page 1 of the initial Home API feed + warms Home & Carousel thumbnails,
     *    then queues Page 2 trending prefetch in the background.
     */
    private fun runSplashPreloadQueue() {
        viewModelScope.launch {
            // Stage 1: Start local warmups immediately; gate AdMob preloading strictly on UMP consent
            launch {
                consentManager.canRequestAds.first { it }
                withContext(Dispatchers.Main.immediate) {
                    interstitialAdManager.preload()
                    nativeAdManager.preloadBuffer()
                }
            }
            launch { repository.pruneExpiredCache() }
            launch { repository.warmupLiveCatalog() }
            enqueueImageWarmup(
                listOf(SPLASH_WALLPAPER_THUMB_URL, SPLASH_WALLPAPER_JPG_URL),
                widthPx = 1080,
                heightPx = 1920,
            )

            // Stage 2: Load user resolution preference and fetch initial Home API feed
            val savedSettings = settingsStore.settingsFlow.first()
            _rawState.update {
                it.copy(filter = it.filter.copy(resolution = savedSettings.preferredResolution))
            }
            loadFeed(forceRefresh = false)
            feedJob?.join()
            _isSplashPreloadComplete.value = true
            _rawState.update { it.copy(isSplashPreloadComplete = true) }
        }
    }

    fun dismissRateLimitDistraction() {
        wallhavenRateLimiter.dismissDistractionPrompt()
    }

    private fun enqueueImageWarmup(urls: List<String>, widthPx: Int = 540, heightPx: Int = 960) {
        val app = getApplication<Application>()
        val loader = app.imageLoader
        urls.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(12)
            .forEach { url ->
                val request = ImageRequest.Builder(app)
                    .data(url)
                    .size(widthPx, heightPx)
                    .precision(Precision.INEXACT)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build()
                loader.enqueue(request)
            }
    }

    fun onSearchInputChange(value: String) {
        _rawState.update { it.copy(searchInput = value) }
        val trimmed = value.trim()
        val targetQuery = trimmed.ifEmpty { WallpaperRepository.HOME_TRENDING_KEY }
        searchDebounceJob?.cancel()
        if (targetQuery != WallpaperRepository.HOME_TRENDING_KEY && trimmed.length < 2) {
            return
        }
        if (!_rawState.value.activeQuery.equals(targetQuery, ignoreCase = true)) {
            searchDebounceJob = viewModelScope.launch {
                delay(if (targetQuery == WallpaperRepository.HOME_TRENDING_KEY) 250L else 550L)
                _rawState.update { it.copy(activeQuery = targetQuery) }
                loadFeed(forceRefresh = false)
            }
        }
    }

    fun submitSearch(queryOverride: String? = null) {
        searchDebounceJob?.cancel()
        val targetQuery = (queryOverride ?: _rawState.value.searchInput).trim()
            .ifEmpty { WallpaperRepository.HOME_TRENDING_KEY }
        _rawState.update {
            it.copy(
                activeQuery = targetQuery,
                searchInput = if (queryOverride != null) targetQuery else it.searchInput,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun selectTrendingTopic(topic: String) {
        searchDebounceJob?.cancel()
        val isReset = topic.equals("anime", ignoreCase = true) ||
                _rawState.value.activeQuery.equals(topic, ignoreCase = true)
        val normalized = if (isReset) {
            WallpaperRepository.HOME_TRENDING_KEY
        } else {
            topic
        }
        _rawState.update {
            it.copy(
                activeQuery = normalized,
                searchInput = if (normalized == WallpaperRepository.HOME_TRENDING_KEY) "" else topic,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun selectTab(isLive: Boolean) {
        if (_rawState.value.isLiveTab == isLive) return
        searchDebounceJob?.cancel()
        _rawState.update { it.copy(isLiveTab = isLive) }
        loadFeed(forceRefresh = false)
    }

    fun applyFilter(newFilter: WallpaperFilter) {
        searchDebounceJob?.cancel()
        _rawState.update { it.copy(filter = newFilter) }
        viewModelScope.launch {
            settingsStore.setPreferredResolution(newFilter.resolution)
        }
        loadFeed(forceRefresh = false)
    }

    fun refreshFeed() {
        searchDebounceJob?.cancel()
        _rawState.update { it.copy(isRefreshing = true) }
        loadFeed(forceRefresh = true)
    }

    fun loadMore() {
        val current = _rawState.value
        if (current.isLoadingMore || !current.hasMore || current.feedStatus !is FeedStatus.Success) return
        val nextPage = current.currentPage + 1
        val expectedQuery = current.activeQuery
        val expectedIsLive = current.isLiveTab
        val expectedFilter = current.filter

        // Update synchronously before launch to prevent duplicate triggers in the same frame.
        _rawState.update { it.copy(isLoadingMore = true) }

        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            try {
                val result = repository.getWallpapers(
                    query = expectedQuery,
                    isLive = expectedIsLive,
                    filter = expectedFilter,
                    page = nextPage,
                    forceRefresh = false,
                )
                _rawState.update { state ->
                    if (state.activeQuery != expectedQuery ||
                        state.isLiveTab != expectedIsLive ||
                        state.filter != expectedFilter
                    ) {
                        return@update state.copy(isLoadingMore = false)
                    }
                    val existing = (state.feedStatus as? FeedStatus.Success)?.items.orEmpty()
                    val merged = (existing + result.wallpapers).distinctBy { it.id }
                    val addedNewItems = merged.size > existing.size
                    val updatedBanners = if (!expectedIsLive &&
                        expectedQuery == WallpaperRepository.HOME_TRENDING_KEY &&
                        merged.isNotEmpty()
                    ) {
                        pickDistinctSeriesBanners(merged, limit = 10)
                    } else {
                        state.trendingBanners
                    }
                    state.copy(
                        feedStatus = if (merged.isNotEmpty()) FeedStatus.Success(merged) else state.feedStatus,
                        trendingBanners = updatedBanners,
                        currentPage = if (addedNewItems) nextPage else state.currentPage,
                        hasMore = result.hasMore && addedNewItems,
                        isLoadingMore = false,
                        bannerMessage = result.warningMessage ?: state.bannerMessage,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _rawState.update {
                    it.copy(
                        isLoadingMore = false,
                        bannerMessage = e.message ?: "Unable to load more wallpapers.",
                    )
                }
            } finally {
                if (_rawState.value.isLoadingMore) {
                    _rawState.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    fun selectAnimeCategory(category: AnimeCategory) {
        searchDebounceJob?.cancel()
        _rawState.update {
            it.copy(
                activeQuery = category.query,
                searchInput = category.title,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun updateCategorySearch(query: String) {
        _categoryFilterText.value = query
    }

    private fun loadFeed(forceRefresh: Boolean) {
        val current = _rawState.value
        loadMoreJob?.cancel()
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            val localSnapshot = repository.getCachedWallpapersSnapshot(
                query = current.activeQuery,
                isLive = current.isLiveTab,
                filter = current.filter,
                page = 1,
            )
            val cacheIsFresh = if (localSnapshot.isNotEmpty()) {
                repository.isFeedCacheFresh(
                    query = current.activeQuery,
                    isLive = current.isLiveTab,
                    filter = current.filter,
                    page = 1,
                )
            } else {
                false
            }

            if (localSnapshot.isNotEmpty()) {
                val needsNetworkRefresh = forceRefresh || !cacheIsFresh
                _rawState.update { state ->
                    val banners = if (!current.isLiveTab &&
                        (state.trendingBanners.isEmpty() || current.activeQuery == WallpaperRepository.HOME_TRENDING_KEY)
                    ) {
                        pickDistinctSeriesBanners(localSnapshot, limit = 10)
                    } else {
                        state.trendingBanners
                    }
                    state.copy(
                        feedStatus = FeedStatus.Success(localSnapshot),
                        trendingBanners = banners,
                        bannerMessage = null,
                        isRefreshing = needsNetworkRefresh,
                        currentPage = 1,
                        hasMore = true,
                        isLoadingMore = false,
                    )
                }

                // Static feed with fresh Room cache: 0 network requests to Wallhaven!
                if (!needsNetworkRefresh && !current.isLiveTab) {
                    warmInitialFeedAssets(current, localSnapshot)
                    return@launch
                }
            } else if (!forceRefresh) {
                _rawState.update {
                    it.copy(
                        feedStatus = FeedStatus.Loading,
                        bannerMessage = null,
                        currentPage = 1,
                        hasMore = true,
                        isLoadingMore = false,
                    )
                }
            }

            val result = repository.refreshWallpapersIfNeeded(
                query = current.activeQuery,
                isLive = current.isLiveTab,
                filter = current.filter,
                page = 1,
                forceRefresh = forceRefresh,
            )
            val resolvedItems = result.wallpapers.ifEmpty { localSnapshot }
            _rawState.update { state ->
                val status = if (resolvedItems.isNotEmpty()) {
                    FeedStatus.Success(resolvedItems)
                } else {
                    val label = if (current.activeQuery == WallpaperRepository.HOME_TRENDING_KEY) {
                        "Hot Anime"
                    } else {
                        current.activeQuery
                    }
                    val emptyReason = if (current.isLiveTab) {
                        "No live MP4 loops found for \"$label\". Try \"Cyberpunk Edgerunners\" or \"Solo Leveling\"."
                    } else {
                        "No ${current.filter.resolution.label} wallpapers found for \"$label\"."
                    }
                    FeedStatus.Error(result.warningMessage ?: emptyReason)
                }
                val banners = if (!current.isLiveTab && resolvedItems.isNotEmpty() &&
                    (state.trendingBanners.isEmpty() || current.activeQuery == WallpaperRepository.HOME_TRENDING_KEY)
                ) {
                    pickDistinctSeriesBanners(resolvedItems, limit = 10)
                } else {
                    state.trendingBanners
                }
                state.copy(
                    feedStatus = status,
                    trendingBanners = banners,
                    bannerMessage = result.warningMessage,
                    isRefreshing = false,
                    currentPage = 1,
                    hasMore = result.hasMore,
                    isLoadingMore = false,
                )
            }
            if (resolvedItems.isNotEmpty()) {
                warmInitialFeedAssets(current, resolvedItems)
            }
        }
    }

    private fun CoroutineScope.warmInitialFeedAssets(
        snapshotState: HomeUiState,
        wallpapers: List<AppWallpaper>,
    ) {
        if (snapshotState.isLiveTab || wallpapers.isEmpty()) return
        val bannerUrls = pickDistinctSeriesBanners(wallpapers, limit = 6).map { it.thumbnailUrl }
        val cardUrls = wallpapers.take(8).map { it.thumbnailUrl }
        enqueueImageWarmup(bannerUrls + cardUrls)
        if (!startupPrefetchStarted && snapshotState.activeQuery == WallpaperRepository.HOME_TRENDING_KEY) {
            startupPrefetchStarted = true
            launch {
                repository.prefetchTrendingQueueBatch(filter = snapshotState.filter, page = 2)
            }
        }
    }

    private fun pickDistinctSeriesBanners(
        wallpapers: List<AppWallpaper>,
        limit: Int,
    ): List<AppWallpaper> {
        val byDistinctSeries = wallpapers
            .distinctBy { it.animeSeries.lowercase() }
            .take(limit)
        return if (byDistinctSeries.size >= limit || wallpapers.size <= byDistinctSeries.size) {
            byDistinctSeries
        } else {
            (byDistinctSeries + wallpapers).distinctBy { it.id }.take(limit)
        }
    }

    fun toggleFavorite(wallpaper: AppWallpaper) {
        viewModelScope.launch {
            val nowFav = repository.toggleFavorite(wallpaper)
            _events.emit(
                UiEvent.ShowMessage(
                    if (nowFav) "Saved to Favorites" else "Removed from Favorites",
                ),
            )
        }
    }

    fun setStaticWallpaper(wallpaper: AppWallpaper, target: WallpaperTarget) {
        viewModelScope.launch {
            _rawState.update {
                it.copy(
                    isProcessingAction = true,
                    processingLabel = "Cropping bitmap for screen…",
                )
            }
            val result = bitmapCropper.setStaticWallpaper(wallpaper.imageUrl, target)
            _rawState.update { it.copy(isProcessingAction = false, processingLabel = null) }
            result.fold(
                onSuccess = {
                    _rawState.update { it.copy(appliedSuccessTarget = target.label) }
                    _events.emit(UiEvent.WallpaperApplied)
                },
                onFailure = { err ->
                    _events.emit(
                        UiEvent.ShowMessage(err.message ?: "Could not apply wallpaper"),
                    )
                },
            )
        }
    }

    fun dismissAppliedSuccess() {
        _rawState.update { it.copy(appliedSuccessTarget = null) }
    }

    fun applyLiveWallpaper(
        wallpaper: AppWallpaper,
        target: WallpaperTarget = WallpaperTarget.BOTH,
    ) {
        val videoUrl = wallpaper.videoUrl ?: return
        if (_rawState.value.isProcessingAction) return

        _rawState.update {
            it.copy(
                isProcessingAction = true,
                liveDownloadProgress = null,
                processingLabel = "Preparing Live Wallpaper…",
            )
        }

        viewModelScope.launch {
            mediaDownloadManager.downloadLiveWallpaper(videoUrl).collect { downloadState ->
                when (downloadState) {
                    LiveDownloadState.Idle -> Unit
                    is LiveDownloadState.Downloading -> {
                        _rawState.update {
                            it.copy(
                                isProcessingAction = true,
                                liveDownloadProgress = downloadState.progress,
                                processingLabel = "Downloading Live Video... ${downloadState.progress}%",
                            )
                        }
                    }

                    is LiveDownloadState.Success -> {
                        settingsStore.setActiveLiveWallpaper(downloadState.file.absolutePath, wallpaper.title)
                        _rawState.update {
                            it.copy(
                                isProcessingAction = false,
                                liveDownloadProgress = null,
                                processingLabel = null,
                                appliedSuccessTarget = null,
                            )
                        }
                        _events.emit(UiEvent.LaunchLiveWallpaperChooser(target))
                    }

                    is LiveDownloadState.Error -> {
                        _rawState.update {
                            it.copy(
                                isProcessingAction = false,
                                liveDownloadProgress = null,
                                processingLabel = null,
                            )
                        }
                        _events.emit(UiEvent.ShowMessage(downloadState.message))
                    }
                }
            }
        }
    }

    fun updatePreferredResolution(tier: ResolutionTier) {
        viewModelScope.launch {
            settingsStore.setPreferredResolution(tier)
            _rawState.update { it.copy(filter = it.filter.copy(resolution = tier)) }
            loadFeed(forceRefresh = false)
        }
    }

    fun setMuteLiveWallpaper(muted: Boolean) {
        viewModelScope.launch {
            settingsStore.setMuteLiveWallpaper(muted)
        }
    }

    fun submitWallpaperRequest(email: String, content: String) {
        val trimmedEmail = email.trim()
        val trimmedContent = content.trim()
        if (trimmedContent.isEmpty()) {
            viewModelScope.launch {
                _events.emit(UiEvent.ShowMessage("Please enter details about the wallpaper you want to request."))
            }
            return
        }
        viewModelScope.launch {
            _events.emit(
                UiEvent.LaunchWallpaperRequestEmail(
                    targetEmail = REQUEST_SUPPORT_EMAIL,
                    userEmail = trimmedEmail,
                    content = trimmedContent,
                ),
            )
        }
    }

    fun notifySelectLiveWallpaperFirst() {
        viewModelScope.launch {
            _events.emit(
                UiEvent.ShowMessage("Please select a Live wallpaper first"),
            )
        }
    }

    fun onLiveWallpaperAppliedSuccess(target: WallpaperTarget = WallpaperTarget.BOTH) {
        viewModelScope.launch {
            _events.emit(
                UiEvent.ShowMessage("Live wallpaper applied (${target.label})!"),
            )
        }
    }

    fun getCurrentFeedList(feedSource: String): List<AppWallpaper> {
        return when (feedSource) {
            com.example.ui.navigation.DetailRoute.FEED_FAVORITES -> favorites.value
            com.example.ui.navigation.DetailRoute.FEED_FAVORITES_STATIC ->
                favorites.value.filter { !it.isLive }
            com.example.ui.navigation.DetailRoute.FEED_FAVORITES_LIVE ->
                favorites.value.filter { it.isLive }
            else -> when (val status = homeState.value.feedStatus) {
                is FeedStatus.Success -> status.items
                else -> homeState.value.trendingBanners
            }
        }
    }

    companion object {
        const val REQUEST_SUPPORT_EMAIL = "damtiendat24072000@gmail.com"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainViewModel(app)
            }
        }
    }
}
