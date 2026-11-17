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
import com.example.util.LiveDownloadState
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
)

sealed interface UiEvent {
    data class ShowMessage(val text: String) : UiEvent
    data class LaunchLiveWallpaperChooser(val target: WallpaperTarget = WallpaperTarget.BOTH) : UiEvent
    data object WallpaperApplied : UiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as KuroWallApplication).container
    private val repository = container.wallpaperRepository
    private val settingsStore = container.settingsDataStore
    private val bitmapCropper = container.bitmapCropper
    private val mediaDownloadManager = container.mediaDownloadManager

    private val _rawState = MutableStateFlow(HomeUiState())
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

    val homeState: StateFlow<HomeUiState> = combine(_rawState, favoriteIds) { state, favIds ->
        val updatedStatus = when (val status = state.feedStatus) {
            is FeedStatus.Success -> FeedStatus.Success(
                status.items.map { it.copy(isFavorite = it.id in favIds) },
            )
            else -> status
        }
        val updatedBanners = state.trendingBanners.map { it.copy(isFavorite = it.id in favIds) }
        state.copy(feedStatus = updatedStatus, trendingBanners = updatedBanners)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

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
        viewModelScope.launch {
            launch { repository.warmupLiveCatalog() }
            enqueueImageWarmup(
                listOf(SPLASH_WALLPAPER_THUMB_URL, SPLASH_WALLPAPER_JPG_URL),
                widthPx = 1080,
                heightPx = 1920,
            )
            val savedSettings = settingsStore.settingsFlow.first()
            _rawState.update {
                it.copy(filter = it.filter.copy(resolution = savedSettings.preferredResolution))
            }
            loadInitialTrendingAndFeed()
        }
    }

    private fun loadInitialTrendingAndFeed() {
        loadFeed(forceRefresh = false)
    }

    private fun enqueueImageWarmup(urls: List<String>, widthPx: Int = 540, heightPx: Int = 960) {
        val app = getApplication<Application>()
        val loader = app.imageLoader
        urls.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
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
        if (current.isLoadingMore || !current.hasMore) return
        val nextPage = current.currentPage + 1
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            _rawState.update { it.copy(isLoadingMore = true) }
            val result = repository.getWallpapers(
                query = current.activeQuery,
                isLive = current.isLiveTab,
                filter = current.filter,
                page = nextPage,
                forceRefresh = false,
            )
            _rawState.update { state ->
                val existing = (state.feedStatus as? FeedStatus.Success)?.items.orEmpty()
                val merged = (existing + result.wallpapers).distinctBy { it.id }
                val updatedBanners = if (!current.isLiveTab &&
                    current.activeQuery == WallpaperRepository.HOME_TRENDING_KEY &&
                    merged.isNotEmpty()
                ) {
                    pickDistinctSeriesBanners(merged, limit = 10)
                } else {
                    state.trendingBanners
                }
                state.copy(
                    feedStatus = if (merged.isNotEmpty()) FeedStatus.Success(merged) else state.feedStatus,
                    trendingBanners = updatedBanners,
                    currentPage = nextPage,
                    hasMore = result.hasMore && result.wallpapers.isNotEmpty(),
                    isLoadingMore = false,
                    bannerMessage = result.warningMessage ?: state.bannerMessage,
                )
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
            if (!forceRefresh) {
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
            val result = repository.getWallpapers(
                query = current.activeQuery,
                isLive = current.isLiveTab,
                filter = current.filter,
                page = 1,
                forceRefresh = forceRefresh,
            )
            _rawState.update { state ->
                val status = if (result.wallpapers.isNotEmpty()) {
                    FeedStatus.Success(result.wallpapers)
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
                val banners = if (!current.isLiveTab && result.wallpapers.isNotEmpty() &&
                    (state.trendingBanners.isEmpty() || current.activeQuery == WallpaperRepository.HOME_TRENDING_KEY)
                ) {
                    pickDistinctSeriesBanners(result.wallpapers, limit = 10)
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
            if (!current.isLiveTab && result.wallpapers.isNotEmpty()) {
                enqueueImageWarmup(result.wallpapers.take(8).map { it.thumbnailUrl })
                if (!startupPrefetchStarted && current.activeQuery == WallpaperRepository.HOME_TRENDING_KEY) {
                    startupPrefetchStarted = true
                    launch {
                        repository.prefetchTrendingQueueBatch(filter = current.filter, page = 2)
                    }
                }
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
        viewModelScope.launch {
            _events.emit(
                UiEvent.ShowMessage("Thanks! Your wallpaper request has been queued for review."),
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
        return if (feedSource == com.example.ui.navigation.DetailRoute.FEED_FAVORITES) {
            favorites.value
        } else {
            when (val status = homeState.value.feedStatus) {
                is FeedStatus.Success -> status.items
                else -> homeState.value.trendingBanners
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainViewModel(app)
            }
        }
    }
}
