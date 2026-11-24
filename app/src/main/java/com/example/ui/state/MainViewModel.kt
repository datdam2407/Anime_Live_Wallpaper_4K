package com.example.ui.state

import android.app.Application
import android.util.Log
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
import com.example.domain.model.AnimeSeriesPreview
import com.example.domain.model.AppWallpaper
import com.example.domain.model.CategoryCoverCandidate
import com.example.domain.model.CrossTabSearchSync
import com.example.domain.model.FacetType
import com.example.domain.model.LiveCatalogFacets
import com.example.domain.model.LiveFilterState
import com.example.domain.model.LiveSearchSuggestion
import com.example.domain.model.LiveTagChip
import com.example.domain.model.ResolutionTier
import com.example.domain.model.SearchSuggestionType
import com.example.domain.model.StaticFilterState
import com.example.domain.model.WallpaperFilter
import com.example.domain.model.WallpaperTarget
import com.example.ui.components.CURATED_STATIC_TAGS
import com.example.ui.components.getStaticTagCandidatePool
import com.example.ui.screens.SPLASH_WALLPAPER_JPG_URL
import com.example.ui.screens.SPLASH_WALLPAPER_THUMB_URL
import com.example.data.remote.RateLimitDistractionPrompt
import com.example.util.MediaDownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

sealed interface FeedStatus {
    data object Loading : FeedStatus
    data class Success(val items: List<AppWallpaper>) : FeedStatus
    data class Error(val message: String) : FeedStatus
}

data class HomeUiState(
    val activeQuery: String = WallpaperRepository.HOME_TRENDING_KEY,
    val searchInput: String = "",
    val isLiveTab: Boolean = false,
    val liveFilter: LiveFilterState = LiveFilterState(),
    val staticFilter: StaticFilterState = StaticFilterState(),
    val isActiveColorSearch: Boolean = false,
    val hiddenEmptyStaticCategories: Set<String> = emptySet(),
    val verifiedStaticTags: List<LiveTagChip> = emptyList(),
    val liveTotalCount: Int? = null,
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
    val liveCatalogFacets: LiveCatalogFacets = LiveCatalogFacets(),
    val staticTagRotationVersion: Int = 0,
    val searchSuggestions: List<LiveSearchSuggestion> = emptyList(),
    val scrollToTopSignal: Int = 0,
)

data class DetailActionState(
    val isProcessingAction: Boolean = false,
    val processingLabel: String? = null,
    val liveDownloadProgress: Int? = null,
    val appliedSuccessTarget: String? = null,
)

data class CollectionDetailState(
    val category: AnimeCategory? = null,
    val items: List<AppWallpaper> = emptyList(),
    val totalCount: Int = 0,
    val currentPage: Int = 1,
    val hasMore: Boolean = false,
    val isLoadingInitial: Boolean = false,
    val isLoadingMore: Boolean = false,
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
    private val liveWallpaperRepository = container.liveWallpaperRepository
    private val settingsStore = container.settingsDataStore
    private val bitmapCropper = container.bitmapCropper
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
    private var searchCoverWarmupJob: Job? = null
    private var staticTagProbeJob: Job? = null
    private var searchReshuffleJob: Job? = null
    private var categoryPreviewJob: Job? = null
    private val categoryPreviewSemaphore = kotlinx.coroutines.sync.Semaphore(permits = 3)
    private val inFlightCategoryPreviewIds = ConcurrentHashMap.newKeySet<String>()
    private val verifiedStaticTagsByScope = ConcurrentHashMap<String, LinkedHashMap<String, LiveTagChip>>()
    private val emptyStaticTagsByScope = ConcurrentHashMap<String, MutableSet<String>>()
    private var collectionDetailJob: Job? = null
    private var collectionLoadMoreJob: Job? = null
    private var startupPrefetchStarted = false
    private var lastManualRefreshTriggeredAtMs = 0L
    private val categoryStaticPools = ConcurrentHashMap<String, List<String>>()
    private val _searchCategoryCovers = MutableStateFlow<Map<String, CategoryCoverCandidate>>(emptyMap())
    val searchCategoryCovers: StateFlow<Map<String, CategoryCoverCandidate>> = _searchCategoryCovers.asStateFlow()
    private val _animeCategoryPreviews = MutableStateFlow<Map<String, AnimeSeriesPreview>>(emptyMap())
    val animeCategoryPreviews: StateFlow<Map<String, AnimeSeriesPreview>> = _animeCategoryPreviews.asStateFlow()
    private val _collectionDetailState = MutableStateFlow(CollectionDetailState())
    val collectionDetailState: StateFlow<CollectionDetailState> = _collectionDetailState.asStateFlow()
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

    val searchLiveFacets: StateFlow<LiveCatalogFacets> = _rawState
        .map { it.liveCatalogFacets }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LiveCatalogFacets())

    val searchVerifiedStaticTags: StateFlow<List<LiveTagChip>> = _rawState
        .map { it.verifiedStaticTags }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val searchTrendingBanners: StateFlow<List<AppWallpaper>> = _rawState
        .map { it.trendingBanners }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val detailActionState: StateFlow<DetailActionState> = _rawState
        .map {
            DetailActionState(
                isProcessingAction = it.isProcessingAction,
                processingLabel = it.processingLabel,
                liveDownloadProgress = it.liveDownloadProgress,
                appliedSuccessTarget = it.appliedSuccessTarget,
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DetailActionState())

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
        viewModelScope.launch {
            liveWallpaperRepository.catalogFacets.collect { facets ->
                _rawState.update { state ->
                    val refreshedSuggestions = if (state.isLiveTab && state.searchInput.isNotBlank()) {
                        computeSearchSuggestions(state.searchInput, isLiveTab = true)
                    } else {
                        state.searchSuggestions
                    }
                    state.copy(
                        liveCatalogFacets = facets,
                        searchSuggestions = refreshedSuggestions,
                    )
                }
                launch(Dispatchers.Default) {
                    fillMissingSearchCategoryCovers()
                }
            }
        }
        runSplashPreloadQueue()
    }

    /**
     * Executes during the 5-second Splash Screen window:
     * 1. Waits for Google UMP GDPR/CCPA consent before preloading Interstitial & Native Ads on Main.
     * 2. Warms Live MP4 catalog, prunes expired Room cache, and pre-decodes Splash hero art.
     * 3. Fetches Page 1 of the initial Home API feed + warms Home & Carousel thumbnails,
     *    then queues Page 2 trending prefetch & Search category cover pre-warming in the background.
     */
    private fun runSplashPreloadQueue() {
        viewModelScope.launch {
            // Stage 1: Start local warmups immediately; gate Interstitial preload strictly on UMP consent.
            // Native Ad preloading is deferred until Home/Categories feed loads to protect AdMob Show Rate.
            launch {
                consentManager.canRequestAds.first { it }
                withContext(Dispatchers.Main.immediate) {
                    interstitialAdManager.preload()
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

            // Stage 3: Verify Home 4K Artworks #Tags first (so only guaranteed non-empty hashtags appear),
            // then probe explore categories & pre-warm SearchScreen category covers in the background.
            launch {
                val activeState = _rawState.value
                warmVerifiedStaticTags(
                    selectedCategory = activeState.staticFilter.selectedCategory,
                    filter = activeState.filter,
                    rotationSeed = activeState.staticTagRotationVersion,
                    targetNewProbes = 6,
                )
                warmSearchCategoryCoverPools()
            }
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
                    .allowHardware(false)
                    .build()
                loader.enqueue(request)
            }
    }

    fun onSearchInputChange(value: String) {
        val isLive = _rawState.value.isLiveTab
        val suggestions = computeSearchSuggestions(value, isLiveTab = isLive)
        _rawState.update {
            it.copy(
                searchInput = value,
                searchSuggestions = suggestions,
            )
        }
        val trimmedRaw = value.trim()
        val isTagPrefix = trimmedRaw.startsWith("#")
        val cleaned = trimmedRaw.removePrefix("#").trim()
        val targetQuery = cleaned.ifEmpty { WallpaperRepository.HOME_TRENDING_KEY }
        searchDebounceJob?.cancel()

        if (isLive) {
            val currentLiveFilter = _rawState.value.liveFilter
            if (cleaned.isEmpty() && currentLiveFilter.isHome && _rawState.value.staticFilter.isHome) {
                return
            }
            if (cleaned.isNotEmpty() &&
                currentLiveFilter.query.equals(cleaned, ignoreCase = true) &&
                currentLiveFilter.selectedCategory == null &&
                currentLiveFilter.selectedSeries == null &&
                currentLiveFilter.selectedTags.isEmpty() &&
                !currentLiveFilter.isColorSearch &&
                _rawState.value.staticFilter.searchQuery.equals(cleaned, ignoreCase = true)
            ) {
                return
            }
            searchDebounceJob = viewModelScope.launch {
                delay(120L)
                val synced = CrossTabSearchSync.syncFromSearch(
                    rawQuery = trimmedRaw,
                    isColorSearch = false,
                    facetType = if (isTagPrefix && cleaned.isNotEmpty()) FacetType.TAG else null,
                    homeTrendingKey = WallpaperRepository.HOME_TRENDING_KEY,
                )
                _rawState.update { state ->
                    state.copy(
                        liveFilter = synced.liveFilter,
                        staticFilter = synced.staticFilter,
                        isActiveColorSearch = false,
                        activeQuery = synced.activeQueryForLive,
                        scrollToTopSignal = state.scrollToTopSignal + 1,
                    )
                }
                loadFeed(forceRefresh = false)
            }
            return
        }

        if (targetQuery != WallpaperRepository.HOME_TRENDING_KEY && cleaned.length < 2) {
            return
        }
        if (!_rawState.value.activeQuery.equals(targetQuery, ignoreCase = true) ||
            _rawState.value.staticFilter.isChipFilterActive ||
            !_rawState.value.liveFilter.query.equals(cleaned, ignoreCase = true)
        ) {
            val debounceMs = if (targetQuery == WallpaperRepository.HOME_TRENDING_KEY) 250L else 550L
            searchDebounceJob = viewModelScope.launch {
                delay(debounceMs)
                val synced = CrossTabSearchSync.syncFromSearch(
                    rawQuery = trimmedRaw,
                    isColorSearch = false,
                    facetType = if (isTagPrefix && cleaned.isNotEmpty()) FacetType.TAG else null,
                    homeTrendingKey = WallpaperRepository.HOME_TRENDING_KEY,
                )
                _rawState.update { state ->
                    state.copy(
                        liveFilter = synced.liveFilter,
                        staticFilter = synced.staticFilter,
                        isActiveColorSearch = false,
                        activeQuery = synced.activeQueryForStatic,
                        scrollToTopSignal = state.scrollToTopSignal + 1,
                    )
                }
                loadFeed(forceRefresh = false)
            }
        }
    }

    fun submitSearch(queryOverride: String? = null) {
        searchDebounceJob?.cancel()
        val raw = (queryOverride ?: _rawState.value.searchInput).trim()
        val isTagPrefix = raw.startsWith("#")
        val cleaned = raw.removePrefix("#").trim()
        val synced = CrossTabSearchSync.syncFromSearch(
            rawQuery = raw,
            isColorSearch = false,
            facetType = if (isTagPrefix && cleaned.isNotEmpty()) FacetType.TAG else null,
            homeTrendingKey = WallpaperRepository.HOME_TRENDING_KEY,
        )
        _rawState.update { state ->
            state.copy(
                liveFilter = synced.liveFilter,
                staticFilter = synced.staticFilter,
                isActiveColorSearch = false,
                activeQuery = if (state.isLiveTab) synced.activeQueryForLive else synced.activeQueryForStatic,
                searchInput = when {
                    cleaned.isEmpty() -> ""
                    queryOverride != null -> raw
                    else -> state.searchInput
                },
                searchSuggestions = emptyList(),
                scrollToTopSignal = state.scrollToTopSignal + 1,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun selectSearchSuggestion(suggestion: LiveSearchSuggestion) {
        searchDebounceJob?.cancel()
        val facetType = when (suggestion.type) {
            SearchSuggestionType.CATEGORY -> FacetType.CATEGORY
            SearchSuggestionType.SERIES -> FacetType.SERIES
            SearchSuggestionType.TAG -> FacetType.TAG
            SearchSuggestionType.TITLE -> null
        }
        val matchedExploreCategory = AnimeCatalog.exploreCategories.firstOrNull {
            it.title.equals(suggestion.query, ignoreCase = true) ||
                    it.query.equals(suggestion.query, ignoreCase = true)
        }
        val synced = CrossTabSearchSync.syncFromSearch(
            rawQuery = suggestion.query,
            isColorSearch = false,
            facetType = facetType,
            matchedExploreCategoryTitle = matchedExploreCategory?.title,
            homeTrendingKey = WallpaperRepository.HOME_TRENDING_KEY,
        )
        _rawState.update { state ->
            state.copy(
                liveFilter = synced.liveFilter,
                staticFilter = synced.staticFilter,
                isActiveColorSearch = false,
                activeQuery = if (state.isLiveTab) synced.activeQueryForLive else synced.activeQueryForStatic,
                searchInput = suggestion.label,
                searchSuggestions = emptyList(),
                scrollToTopSignal = state.scrollToTopSignal + 1,
            )
        }
        if (synced.staticFilter.selectedCategory != null) {
            warmVerifiedStaticTags(
                selectedCategory = synced.staticFilter.selectedCategory,
                filter = _rawState.value.filter,
                rotationSeed = 0,
                targetNewProbes = 6,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun toggleLiveCategory(category: String) {
        searchDebounceJob?.cancel()
        _rawState.update { state ->
            val isSwitchingBetweenCategories = state.liveFilter.selectedCategory != null &&
                    !state.liveFilter.selectedCategory.equals(category.trim(), ignoreCase = true)
            val activeTag = state.liveFilter.selectedTags.firstOrNull()
            val tagExistsInNewCategory = activeTag != null &&
                    state.liveCatalogFacets.tagsFor(category).any { it.query.equals(activeTag, ignoreCase = true) }
            val nextLiveFilter = state.liveFilter.toggleCategory(
                category = category,
                clearTagIfSwitchingCategory = isSwitchingBetweenCategories && !tagExistsInNewCategory,
            )
            val displayLabel = nextLiveFilter.displayInputText()
            // Clear stale free-text search on the 4K Artworks tab when user pivots to a Live Category chip.
            val cleanedStaticFilter = if (state.staticFilter.searchQuery.isNotBlank()) {
                StaticFilterState()
            } else {
                state.staticFilter
            }
            state.copy(
                liveFilter = nextLiveFilter,
                staticFilter = cleanedStaticFilter,
                isActiveColorSearch = false,
                activeQuery = displayLabel.removePrefix("#").ifEmpty { WallpaperRepository.HOME_TRENDING_KEY },
                searchInput = "",
                searchSuggestions = emptyList(),
                scrollToTopSignal = state.scrollToTopSignal + 1,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun toggleLiveSeries(series: String) {
        searchDebounceJob?.cancel()
        _rawState.update { state ->
            val nextLiveFilter = state.liveFilter.toggleSeries(series)
            val displayLabel = nextLiveFilter.displayInputText()
            val syncedStaticFilter = nextLiveFilter.selectedSeries?.let {
                StaticFilterState(searchQuery = it)
            } ?: StaticFilterState()
            state.copy(
                liveFilter = nextLiveFilter,
                staticFilter = syncedStaticFilter,
                isActiveColorSearch = false,
                activeQuery = displayLabel.removePrefix("#").ifEmpty { WallpaperRepository.HOME_TRENDING_KEY },
                searchInput = "",
                searchSuggestions = emptyList(),
                scrollToTopSignal = state.scrollToTopSignal + 1,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun toggleLiveTag(tagQuery: String) {
        searchDebounceJob?.cancel()
        _rawState.update { state ->
            val nextLiveFilter = state.liveFilter.toggleTag(tagQuery)
            val displayLabel = nextLiveFilter.displayInputText()
            val cleanedStaticFilter = if (state.staticFilter.searchQuery.isNotBlank()) {
                StaticFilterState()
            } else {
                state.staticFilter
            }
            state.copy(
                liveFilter = nextLiveFilter,
                staticFilter = cleanedStaticFilter,
                isActiveColorSearch = false,
                activeQuery = displayLabel.removePrefix("#").ifEmpty { WallpaperRepository.HOME_TRENDING_KEY },
                searchInput = "",
                searchSuggestions = emptyList(),
                scrollToTopSignal = state.scrollToTopSignal + 1,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun clearLiveFilters() {
        searchDebounceJob?.cancel()
        _rawState.update { state ->
            state.copy(
                liveFilter = LiveFilterState(),
                staticFilter = StaticFilterState(),
                isActiveColorSearch = false,
                activeQuery = WallpaperRepository.HOME_TRENDING_KEY,
                searchInput = "",
                searchSuggestions = emptyList(),
                scrollToTopSignal = state.scrollToTopSignal + 1,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun toggleStaticCategory(category: String) {
        searchDebounceJob?.cancel()
        var nextCategory: String? = null
        _rawState.update { state ->
            val nextStaticFilter = state.staticFilter.toggleCategory(category)
            nextCategory = nextStaticFilter.selectedCategory
            val cleanedLiveFilter = if (state.liveFilter.query.isNotBlank() || state.liveFilter.selectedSeries != null) {
                LiveFilterState()
            } else {
                state.liveFilter
            }
            val scopeKey = buildStaticTagScopeKey(nextStaticFilter.selectedCategory, state.filter)
            val cachedVerified = verifiedStaticTagsByScope[scopeKey]?.values?.toList().orEmpty()
            state.copy(
                staticFilter = nextStaticFilter,
                liveFilter = cleanedLiveFilter,
                isActiveColorSearch = false,
                verifiedStaticTags = cachedVerified,
                activeQuery = nextStaticFilter.toQueryKey(WallpaperRepository.HOME_TRENDING_KEY),
                searchInput = "",
                searchSuggestions = emptyList(),
                scrollToTopSignal = state.scrollToTopSignal + 1,
            )
        }
        warmVerifiedStaticTags(
            selectedCategory = nextCategory,
            filter = _rawState.value.filter,
            rotationSeed = 0,
            targetNewProbes = 6,
        )
        loadFeed(forceRefresh = false)
    }

    fun toggleStaticTag(tagQuery: String) {
        searchDebounceJob?.cancel()
        _rawState.update { state ->
            val nextStaticFilter = state.staticFilter.toggleTag(tagQuery)
            val cleanedLiveFilter = if (state.liveFilter.query.isNotBlank() || state.liveFilter.selectedSeries != null) {
                LiveFilterState()
            } else {
                state.liveFilter
            }
            state.copy(
                staticFilter = nextStaticFilter,
                liveFilter = cleanedLiveFilter,
                isActiveColorSearch = false,
                activeQuery = nextStaticFilter.toQueryKey(WallpaperRepository.HOME_TRENDING_KEY),
                searchInput = "",
                searchSuggestions = emptyList(),
                scrollToTopSignal = state.scrollToTopSignal + 1,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun clearStaticFilters() {
        searchDebounceJob?.cancel()
        _rawState.update { state ->
            val scopeKey = buildStaticTagScopeKey(null, state.filter)
            val cachedVerified = verifiedStaticTagsByScope[scopeKey]?.values?.toList().orEmpty()
            state.copy(
                staticFilter = StaticFilterState(),
                liveFilter = LiveFilterState(),
                isActiveColorSearch = false,
                verifiedStaticTags = cachedVerified,
                activeQuery = WallpaperRepository.HOME_TRENDING_KEY,
                searchInput = "",
                searchSuggestions = emptyList(),
                scrollToTopSignal = state.scrollToTopSignal + 1,
            )
        }
        warmVerifiedStaticTags(
            selectedCategory = null,
            filter = _rawState.value.filter,
            rotationSeed = _rawState.value.staticTagRotationVersion,
            targetNewProbes = 6,
        )
        loadFeed(forceRefresh = false)
    }

    fun selectTrendingTopic(topic: String) {
        searchDebounceJob?.cancel()
        val cleanedTopic = topic.trim().removePrefix("#").trim()
        val isResetExplicit = cleanedTopic.isEmpty() ||
                cleanedTopic.equals("all", ignoreCase = true) ||
                cleanedTopic.equals(WallpaperRepository.HOME_TRENDING_KEY, ignoreCase = true)

        if (_rawState.value.isLiveTab) {
            if (isResetExplicit) {
                clearLiveFilters()
                return
            }
            val facets = _rawState.value.liveCatalogFacets
            when {
                facets.categories.any { it.query.equals(cleanedTopic, ignoreCase = true) } -> {
                    toggleLiveCategory(cleanedTopic)
                }
                facets.series.any { it.query.equals(cleanedTopic, ignoreCase = true) } -> {
                    toggleLiveSeries(cleanedTopic)
                }
                else -> {
                    val synced = CrossTabSearchSync.syncFromSearch(
                        rawQuery = cleanedTopic,
                        homeTrendingKey = WallpaperRepository.HOME_TRENDING_KEY,
                    )
                    _rawState.update { state ->
                        state.copy(
                            liveFilter = synced.liveFilter,
                            staticFilter = synced.staticFilter,
                            isActiveColorSearch = false,
                            activeQuery = synced.activeQueryForLive,
                            searchInput = "",
                            searchSuggestions = emptyList(),
                            scrollToTopSignal = state.scrollToTopSignal + 1,
                        )
                    }
                    loadFeed(forceRefresh = false)
                }
            }
            return
        }

        if (isResetExplicit) {
            clearStaticFilters()
            return
        }

        val isExploreCategory = AnimeCatalog.exploreCategories.any {
            it.title.equals(cleanedTopic, ignoreCase = true) || it.query.equals(cleanedTopic, ignoreCase = true)
        }
        if (isExploreCategory) {
            toggleStaticCategory(cleanedTopic)
            return
        }

        val currentActive = _rawState.value.activeQuery.trim().removePrefix("#").trim()
        val isReset = currentActive.equals(cleanedTopic, ignoreCase = true)
        val synced = if (isReset) {
            CrossTabSearchSync.syncFromSearch(
                rawQuery = "",
                homeTrendingKey = WallpaperRepository.HOME_TRENDING_KEY,
            )
        } else {
            CrossTabSearchSync.syncFromSearch(
                rawQuery = cleanedTopic,
                homeTrendingKey = WallpaperRepository.HOME_TRENDING_KEY,
            )
        }
        _rawState.update {
            it.copy(
                staticFilter = synced.staticFilter,
                liveFilter = synced.liveFilter,
                isActiveColorSearch = false,
                activeQuery = synced.activeQueryForStatic,
                searchInput = "",
                searchSuggestions = emptyList(),
                scrollToTopSignal = it.scrollToTopSignal + 1,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun selectTab(isLive: Boolean) {
        if (_rawState.value.isLiveTab == isLive) {
            // Re-tapping the active 4K Artworks / Live Wallpapers tab still returns the feed to the top.
            _rawState.update { state ->
                state.copy(scrollToTopSignal = state.scrollToTopSignal + 1)
            }
            return
        }
        searchDebounceJob?.cancel()
        var staticCategoryToWarm: String? = null
        _rawState.update { state ->
            val synced = CrossTabSearchSync.syncOnTabSwitch(
                targetIsLive = isLive,
                currentLiveFilter = state.liveFilter,
                currentStaticFilter = state.staticFilter,
                searchInput = state.searchInput,
                isColorSearch = state.isActiveColorSearch,
                homeTrendingKey = WallpaperRepository.HOME_TRENDING_KEY,
            )
            staticCategoryToWarm = synced.staticFilter.selectedCategory
            val scopeKey = buildStaticTagScopeKey(synced.staticFilter.selectedCategory, state.filter)
            val cachedVerified = verifiedStaticTagsByScope[scopeKey]?.values?.toList().orEmpty()
            state.copy(
                isLiveTab = isLive,
                liveFilter = synced.liveFilter,
                staticFilter = synced.staticFilter,
                verifiedStaticTags = if (!isLive) cachedVerified else state.verifiedStaticTags,
                activeQuery = if (isLive) synced.activeQueryForLive else synced.activeQueryForStatic,
                searchSuggestions = emptyList(),
                scrollToTopSignal = state.scrollToTopSignal + 1,
            )
        }
        if (!isLive) {
            warmVerifiedStaticTags(
                selectedCategory = staticCategoryToWarm,
                filter = _rawState.value.filter,
                rotationSeed = _rawState.value.staticTagRotationVersion,
                targetNewProbes = 6,
            )
        }
        loadFeed(forceRefresh = false)
    }

    private fun computeSearchSuggestions(
        rawInput: String,
        isLiveTab: Boolean,
    ): List<LiveSearchSuggestion> {
        val cleaned = rawInput.trim().removePrefix("#").trim()
        if (cleaned.isEmpty()) return emptyList()
        if (isLiveTab) {
            return liveWallpaperRepository.getSearchSuggestions(rawInput, limit = 8)
        }
        val results = mutableListOf<LiveSearchSuggestion>()
        CURATED_STATIC_TAGS
            .filter { it.query.contains(cleaned, ignoreCase = true) }
            .take(4)
            .forEach { chip ->
                results += LiveSearchSuggestion(
                    label = chip.label,
                    query = chip.label,
                    type = SearchSuggestionType.TAG,
                )
            }
        val startsWithMatches = AnimeCatalog.animeSearchKeywords
            .filter { it.startsWith(cleaned, ignoreCase = true) }
        val containsMatches = AnimeCatalog.animeSearchKeywords
            .filter { !it.startsWith(cleaned, ignoreCase = true) && it.contains(cleaned, ignoreCase = true) }
        (startsWithMatches + containsMatches)
            .take((8 - results.size).coerceAtLeast(0))
            .forEach { series ->
                results += LiveSearchSuggestion(
                    label = series,
                    query = series,
                    type = SearchSuggestionType.CATEGORY,
                )
            }
        return results
    }

    fun applyFilter(newFilter: WallpaperFilter) {
        searchDebounceJob?.cancel()
        val filterChanged = _rawState.value.filter != newFilter
        val activeStaticCategory = _rawState.value.staticFilter.selectedCategory
        _rawState.update {
            val nextVerified = if (filterChanged) {
                val scopeKey = buildStaticTagScopeKey(activeStaticCategory, newFilter)
                verifiedStaticTagsByScope[scopeKey]?.values?.toList().orEmpty()
            } else {
                it.verifiedStaticTags
            }
            it.copy(
                filter = newFilter,
                hiddenEmptyStaticCategories = if (filterChanged) emptySet() else it.hiddenEmptyStaticCategories,
                verifiedStaticTags = nextVerified,
                bannerMessage = null,
                scrollToTopSignal = it.scrollToTopSignal + 1,
            )
        }
        viewModelScope.launch {
            settingsStore.setPreferredResolution(newFilter.resolution)
        }
        if (filterChanged) {
            searchCoverWarmupJob?.cancel()
            warmVerifiedStaticTags(
                selectedCategory = activeStaticCategory,
                filter = newFilter,
                rotationSeed = 0,
                targetNewProbes = 6,
            )
            warmSearchCategoryCoverPools(forceReprobe = true)
        }
        loadFeed(forceRefresh = false)
    }

    fun refreshFeed() {
        val current = _rawState.value
        val nowMs = System.currentTimeMillis()
        if (current.isRefreshing || (nowMs - lastManualRefreshTriggeredAtMs) in 0L until 400L) {
            return
        }
        lastManualRefreshTriggeredAtMs = nowMs
        searchDebounceJob?.cancel()
        val isLive = current.isLiveTab
        if (isLive) {
            liveWallpaperRepository.rotateContentAndTags()
        }
        val nextRotation = if (!isLive) current.staticTagRotationVersion + 1 else current.staticTagRotationVersion
        _rawState.update {
            it.copy(
                isRefreshing = true,
                bannerMessage = null,
                staticTagRotationVersion = nextRotation,
                scrollToTopSignal = it.scrollToTopSignal + 1,
            )
        }
        if (!isLive) {
            // Probe a small fresh batch (max 3) in the background so pull-to-refresh reveals new
            // API-verified hashtags without risking Wallhaven's 45 req/min rate limit.
            warmVerifiedStaticTags(
                selectedCategory = current.staticFilter.selectedCategory,
                filter = current.filter,
                rotationSeed = nextRotation,
                targetNewProbes = 3,
            )
        }
        loadFeed(forceRefresh = true)
    }

    fun loadMore() {
        val current = _rawState.value
        if (current.isLoadingMore || !current.hasMore || current.feedStatus !is FeedStatus.Success) return
        val nextPage = current.currentPage + 1
        val expectedQuery = current.activeQuery
        val expectedIsLive = current.isLiveTab
        val expectedFilter = current.filter
        val expectedLiveFilter = current.liveFilter
        val expectedStaticFilter = current.staticFilter

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
                    liveFilter = if (expectedIsLive) expectedLiveFilter else null,
                    staticFilter = if (!expectedIsLive) expectedStaticFilter else null,
                )
                _rawState.update { state ->
                    if (state.activeQuery != expectedQuery ||
                        state.isLiveTab != expectedIsLive ||
                        state.filter != expectedFilter ||
                        (expectedIsLive && state.liveFilter != expectedLiveFilter) ||
                        (!expectedIsLive && state.staticFilter != expectedStaticFilter)
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
                        bannerMessage = result.warningMessage ?: if (addedNewItems) null else state.bannerMessage,
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
                isLiveTab = false,
                staticFilter = StaticFilterState(searchQuery = category.query),
                liveFilter = LiveFilterState(query = category.title),
                isActiveColorSearch = false,
                activeQuery = category.query,
                searchInput = category.title,
                searchSuggestions = emptyList(),
                scrollToTopSignal = it.scrollToTopSignal + 1,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun searchFromSearchScreen(
        query: String,
        isColorSearch: Boolean = false,
        preferLive: Boolean? = null,
        facetType: FacetType? = null,
    ) {
        searchDebounceJob?.cancel()
        val raw = query.trim()
        val cleaned = raw.removePrefix("#").trim()
        val targetQuery = cleaned.ifEmpty { WallpaperRepository.HOME_TRENDING_KEY }

        val matchedExploreCategory = AnimeCatalog.exploreCategories.firstOrNull {
            it.title.equals(targetQuery, ignoreCase = true) || it.query.equals(targetQuery, ignoreCase = true)
        }
        val synced = CrossTabSearchSync.syncFromSearch(
            rawQuery = raw,
            isColorSearch = isColorSearch,
            facetType = facetType,
            matchedExploreCategoryTitle = matchedExploreCategory?.title,
            homeTrendingKey = WallpaperRepository.HOME_TRENDING_KEY,
        )

        val currentIsLive = _rawState.value.isLiveTab
        val isExploreDomain = matchedExploreCategory != null
        val resolvedIsLive = when {
            preferLive != null -> preferLive
            (isColorSearch || isExploreDomain) && currentIsLive -> {
                // Keep Live tab if R2 JSON has matching wallpapers for this color/domain; otherwise switch to 4K Artworks so user sees rich results immediately.
                val liveMatches = liveWallpaperRepository.getCachedCatalogSnapshot(synced.liveFilter)?.items.orEmpty()
                liveMatches.isNotEmpty()
            }
            else -> currentIsLive
        }

        val displaySearchInput = when {
            targetQuery == WallpaperRepository.HOME_TRENDING_KEY -> ""
            !resolvedIsLive && synced.staticFilter.isChipFilterActive -> ""
            else -> synced.searchInput
        }

        _rawState.update { state ->
            val scopeKey = buildStaticTagScopeKey(synced.staticFilter.selectedCategory, state.filter)
            val cachedVerified = verifiedStaticTagsByScope[scopeKey]?.values?.toList().orEmpty()
            // Single Source of Truth: ALWAYS synchronize BOTH liveFilter AND staticFilter together
            // so switching between Live Wallpapers and 4K Artworks never resurrects stale search results.
            state.copy(
                isLiveTab = resolvedIsLive,
                liveFilter = synced.liveFilter,
                staticFilter = synced.staticFilter,
                isActiveColorSearch = isColorSearch,
                verifiedStaticTags = if (!resolvedIsLive) cachedVerified else state.verifiedStaticTags,
                activeQuery = if (resolvedIsLive) synced.activeQueryForLive else synced.activeQueryForStatic,
                searchInput = displaySearchInput,
                searchSuggestions = emptyList(),
                scrollToTopSignal = state.scrollToTopSignal + 1,
            )
        }
        if (synced.staticFilter.selectedCategory != null) {
            warmVerifiedStaticTags(
                selectedCategory = synced.staticFilter.selectedCategory,
                filter = _rawState.value.filter,
                rotationSeed = 0,
                targetNewProbes = 6,
            )
        }
        loadFeed(forceRefresh = false)
    }

    fun getUnifiedSearchSuggestions(rawInput: String): List<LiveSearchSuggestion> {
        val cleaned = rawInput.trim().removePrefix("#").trim()
        if (cleaned.isEmpty()) return emptyList()

        val merged = LinkedHashMap<String, LiveSearchSuggestion>()
        liveWallpaperRepository.getSearchSuggestions(rawInput, limit = 6).forEach { suggestion ->
            merged.putIfAbsent("${suggestion.type.name}:${suggestion.query.lowercase()}", suggestion)
        }
        computeSearchSuggestions(rawInput, isLiveTab = false).forEach { suggestion ->
            if (merged.size < 10) {
                merged.putIfAbsent("${suggestion.type.name}:${suggestion.query.lowercase()}", suggestion)
            }
        }
        return merged.values.take(10)
    }

    fun updateCategorySearch(query: String) {
        _categoryFilterText.value = query
    }

    /**
     * Loads 4-thumbnail preview strips and real wallpaper totals only for the currently
     * visible anime rows on CategoriesScreen (Anime 200+). Local Room/R2 caches hydrate
     * instantaneously; uncached rows fetch sequentially with pacing to protect API quotas.
     */
    fun requestAnimeCategoryPreviews(visibleCategories: List<AnimeCategory>) {
        if (visibleCategories.isEmpty()) return
        val activeFilter = _rawState.value.filter
        viewModelScope.launch {
            // Phase 1: Instant local hydration from Room + R2 cache (0 network calls)
            for (category in visibleCategories) {
                val existing = _animeCategoryPreviews.value[category.id]
                if (existing != null && existing.thumbnailUrls.size >= 4 && !existing.isLoading) continue
                val cached = repository.getCachedAnimeCategoryPreview(category, activeFilter)
                if (cached != null && cached.thumbnailUrls.isNotEmpty()) {
                    _animeCategoryPreviews.update { map -> map + (category.id to cached) }
                }
            }

            // Phase 2: Parallel throttled fetch (up to 3 visible rows concurrently) without
            // cancelling already-in-flight row loads when the user scrolls slightly.
            for (category in visibleCategories) {
                val current = _animeCategoryPreviews.value[category.id]
                if (current != null && current.thumbnailUrls.size >= 4 && current.totalCount > 4 && !current.isLoading) {
                    continue
                }
                if (!inFlightCategoryPreviewIds.add(category.id)) {
                    continue
                }
                if (current == null || current.thumbnailUrls.isEmpty()) {
                    _animeCategoryPreviews.update { map ->
                        map + (
                                category.id to AnimeSeriesPreview(
                                    categoryId = category.id,
                                    isLoading = true,
                                )
                                )
                    }
                }
                launch {
                    try {
                        categoryPreviewSemaphore.acquire()
                        val fetched = try {
                            repository.fetchAnimeCategoryPreview(category, activeFilter)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            current?.copy(isLoading = false)
                                ?: AnimeSeriesPreview(categoryId = category.id, isLoading = false)
                        }
                        _animeCategoryPreviews.update { map -> map + (category.id to fetched) }
                        if (fetched.thumbnailUrls.isNotEmpty()) {
                            enqueueImageWarmup(fetched.thumbnailUrls, widthPx = 240, heightPx = 420)
                        }
                    } finally {
                        categoryPreviewSemaphore.release()
                        inFlightCategoryPreviewIds.remove(category.id)
                    }
                }
            }
        }
    }

    /**
     * Opens an Anime 200+ series directly into DetailPreviewScreen at the exact tapped tile,
     * pre-seeded with the already-loaded Page 1 wallpapers so the detail screen opens instantly
     * and can seamlessly paginate through all remaining (+N) wallpapers on swipe.
     */
    fun openAnimeCategoryInDetail(category: AnimeCategory) {
        collectionDetailJob?.cancel()
        collectionLoadMoreJob?.cancel()
        val activeFilter = _rawState.value.filter
        val preview = _animeCategoryPreviews.value[category.id]
        val initialItems = preview?.wallpapers.orEmpty()
        val initialTotal = maxOf(preview?.totalCount ?: 0, initialItems.size)
        val initialHasMore = initialItems.isNotEmpty() &&
                (initialItems.size < initialTotal || initialItems.size >= 18)

        _collectionDetailState.value = CollectionDetailState(
            category = category,
            items = initialItems,
            totalCount = initialTotal,
            currentPage = 1,
            hasMore = initialHasMore,
            isLoadingInitial = initialItems.isEmpty(),
            isLoadingMore = false,
        )

        if (initialItems.isEmpty()) {
            collectionDetailJob = viewModelScope.launch {
                val fetched = try {
                    repository.fetchAnimeCategoryPreview(category, activeFilter)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    AnimeSeriesPreview(categoryId = category.id, isLoading = false)
                }
                _animeCategoryPreviews.update { map -> map + (category.id to fetched) }
                val loadedItems = fetched.wallpapers
                val resolvedTotal = maxOf(fetched.totalCount, loadedItems.size)
                val hasMore = loadedItems.isNotEmpty() &&
                        (loadedItems.size < resolvedTotal || loadedItems.size >= 18)
                _collectionDetailState.update { state ->
                    if (state.category?.id != category.id) return@update state
                    state.copy(
                        items = loadedItems,
                        totalCount = resolvedTotal,
                        currentPage = 1,
                        hasMore = hasMore,
                        isLoadingInitial = false,
                    )
                }
            }
        }
    }

    /**
     * Loads the next page (page 2, 3, ...) of the active Anime 200+ collection when the user
     * swipes near the end inside DetailPreviewScreen.
     */
    fun loadMoreCollectionDetail() {
        val current = _collectionDetailState.value
        val category = current.category ?: return
        if (current.isLoadingInitial || current.isLoadingMore || !current.hasMore) return

        val nextPage = current.currentPage + 1
        val activeFilter = _rawState.value.filter
        _collectionDetailState.update { it.copy(isLoadingMore = true) }

        collectionLoadMoreJob?.cancel()
        collectionLoadMoreJob = viewModelScope.launch {
            try {
                val result = repository.fetchAnimeCategoryDetailPage(
                    category = category,
                    filter = activeFilter,
                    page = nextPage,
                )
                _collectionDetailState.update { state ->
                    if (state.category?.id != category.id) {
                        return@update state.copy(isLoadingMore = false)
                    }
                    val merged = (state.items + result.wallpapers).distinctBy { it.id }
                    val addedNew = merged.size > state.items.size
                    val updatedTotal = maxOf(state.totalCount, result.totalCount, merged.size)
                    state.copy(
                        items = merged,
                        totalCount = updatedTotal,
                        currentPage = if (addedNew) nextPage else state.currentPage,
                        hasMore = result.hasMore && addedNew,
                        isLoadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _collectionDetailState.update { it.copy(isLoadingMore = false) }
            } finally {
                if (_collectionDetailState.value.isLoadingMore) {
                    _collectionDetailState.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    /**
     * Invoked every time the user enters (or returns to) SearchScreen from another screen.
     * Rotates all 12 Trending Category cards off the Main thread (0ms UI jank) and ensures
     * any uncached category pools are hydrated immediately from Room + bounded parallel fetches.
     */
    fun onSearchScreenEntered() {
        searchReshuffleJob?.cancel()
        searchReshuffleJob = viewModelScope.launch(Dispatchers.Default) {
            reshuffleSearchCategoryCovers()
        }
        warmSearchCategoryCoverPools()
    }

    private fun buildCandidatesForCategory(title: String): Pair<List<CategoryCoverCandidate>, List<CategoryCoverCandidate>> {
        val staticUrls = buildList {
            categoryStaticPools[title].orEmpty().forEach { add(it) }
            if (title.equals("Anime", ignoreCase = true)) {
                _rawState.value.trendingBanners.forEach { banner ->
                    if (banner.thumbnailUrl.isNotBlank()) add(banner.thumbnailUrl)
                }
            }
        }.distinct()

        val liveUrls = liveWallpaperRepository.getMatchingLiveCoverUrls(query = title, limit = 10)

        val staticCandidates = staticUrls.map { url ->
            CategoryCoverCandidate(imageUrl = url, isLive = false, liveKeyword = null)
        }
        val liveCandidates = liveUrls.map { url ->
            CategoryCoverCandidate(imageUrl = url, isLive = true, liveKeyword = title)
        }
        return staticCandidates to liveCandidates
    }

    private fun reshuffleSearchCategoryCovers() {
        val previousMap = _searchCategoryCovers.value
        val nextMap = LinkedHashMap<String, CategoryCoverCandidate>()
        for (topic in AnimeCatalog.exploreCategories) {
            val (staticCandidates, liveCandidates) = buildCandidatesForCategory(topic.title)
            val chosen = pickNextRandomCover(
                staticCandidates = staticCandidates,
                liveCandidates = liveCandidates,
                previousUrl = previousMap[topic.title]?.imageUrl,
            )
            if (chosen != null) {
                nextMap[topic.title] = chosen
            }
        }
        if (nextMap.isNotEmpty() || previousMap.isEmpty()) {
            _searchCategoryCovers.value = nextMap
        }
    }

    private fun fillMissingSearchCategoryCovers() {
        val currentMap = _searchCategoryCovers.value
        var changed = false
        val updated = LinkedHashMap(currentMap)
        for (topic in AnimeCatalog.exploreCategories) {
            if (updated.containsKey(topic.title)) continue
            val (staticCandidates, liveCandidates) = buildCandidatesForCategory(topic.title)
            val chosen = pickNextRandomCover(
                staticCandidates = staticCandidates,
                liveCandidates = liveCandidates,
                previousUrl = null,
            )
            if (chosen != null) {
                updated[topic.title] = chosen
                changed = true
            }
        }
        if (changed) {
            _searchCategoryCovers.value = updated
        }
    }

    private fun warmSearchCategoryCoverPools(forceReprobe: Boolean = false) {
        if (!forceReprobe && searchCoverWarmupJob?.isActive == true) return
        val activeFilter = _rawState.value.filter
        searchCoverWarmupJob = viewModelScope.launch(Dispatchers.IO) {
            // Phase 1: Instant parallel Room cache hydration for all 12 categories (0 network, 0 delay).
            val targetCategories = if (forceReprobe) {
                AnimeCatalog.exploreCategories
            } else {
                AnimeCatalog.exploreCategories.filter { topic ->
                    val existingPool = categoryStaticPools[topic.title]
                    existingPool == null || existingPool.size < 4
                }
            }
            if (targetCategories.isEmpty()) return@launch

            val cachedResults = coroutineScope {
                targetCategories.map { topic ->
                    async {
                        val urls = runCatching {
                            repository.getCachedExploreCategoryStaticCovers(
                                topic = topic,
                                filter = activeFilter,
                                maxCount = 10,
                            )
                        }.getOrDefault(emptyList())
                        topic to urls
                    }
                }.awaitAll()
            }

            val newlyHydrated = mutableListOf<String>()
            val stillMissing = mutableListOf<com.example.domain.model.TrendingAnimeTopic>()
            for ((topic, urls) in cachedResults) {
                if (urls.isNotEmpty()) {
                    categoryStaticPools[topic.title] = urls
                    newlyHydrated += urls.first()
                }
                if (urls.size < 4) {
                    stillMissing += topic
                }
            }
            if (newlyHydrated.isNotEmpty()) {
                fillMissingSearchCategoryCovers()
                enqueueImageWarmup(newlyHydrated, widthPx = 300, heightPx = 400)
            }

            if (stillMissing.isEmpty()) return@launch

            // Phase 2: Bounded parallel network warmup (3 concurrent fetches, zero artificial sleep)
            // so cold-start category covers populate in ~1s and confirmed-empty categories hide automatically.
            val semaphore = Semaphore(permits = 3)
            coroutineScope {
                stillMissing.map { topic ->
                    async {
                        semaphore.withPermit {
                            val probe = try {
                                repository.probeExploreCategoryStaticCovers(
                                    topic = topic,
                                    filter = activeFilter,
                                    maxCount = 10,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                com.example.data.repository.ExploreCategoryProbeResult(
                                    coverUrls = emptyList(),
                                    confirmedEmpty = false,
                                )
                            }

                            if (probe.confirmedEmpty) {
                                _rawState.update { state ->
                                    if (state.filter != activeFilter) return@update state
                                    state.copy(
                                        hiddenEmptyStaticCategories = state.hiddenEmptyStaticCategories +
                                                topic.title.lowercase(),
                                    )
                                }
                            } else if (probe.coverUrls.isNotEmpty()) {
                                val urls = probe.coverUrls
                                categoryStaticPools[topic.title] = urls
                                _rawState.update { state ->
                                    if (state.filter != activeFilter) return@update state
                                    val key = topic.title.lowercase()
                                    if (key in state.hiddenEmptyStaticCategories) {
                                        state.copy(hiddenEmptyStaticCategories = state.hiddenEmptyStaticCategories - key)
                                    } else {
                                        state
                                    }
                                }
                                enqueueImageWarmup(urls.take(2), widthPx = 300, heightPx = 400)
                                _searchCategoryCovers.update { currentMap ->
                                    if (currentMap.containsKey(topic.title)) {
                                        currentMap
                                    } else {
                                        val (staticCandidates, liveCandidates) = buildCandidatesForCategory(topic.title)
                                        val chosen = pickNextRandomCover(
                                            staticCandidates = staticCandidates,
                                            liveCandidates = liveCandidates,
                                            previousUrl = null,
                                        ) ?: return@update currentMap
                                        currentMap + (topic.title to chosen)
                                    }
                                }
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun buildStaticTagScopeKey(
        selectedCategory: String?,
        filter: WallpaperFilter,
    ): String {
        val cat = selectedCategory?.trim()?.lowercase().orEmpty()
        return "${filter.resolution.name}_${filter.ratio.name}_${filter.sort.name}|$cat"
    }

    /**
     * Verifies candidate static 4K Artworks #Tags against local Room cache (Phase 1, 0ms) and
     * the real Wallhaven API (Phase 2, quota-protected) so ONLY hashtags confirmed to have > 0
     * images are ever rendered in the UI — both on cold start and after Pull-to-Refresh.
     */
    private fun warmVerifiedStaticTags(
        selectedCategory: String?,
        filter: WallpaperFilter,
        rotationSeed: Int,
        targetNewProbes: Int = 6,
    ) {
        val scopeKey = buildStaticTagScopeKey(selectedCategory, filter)
        val candidatePool = getStaticTagCandidatePool(selectedCategory)
        if (candidatePool.isEmpty()) return

        staticTagProbeJob?.cancel()
        staticTagProbeJob = viewModelScope.launch(Dispatchers.IO) {
            val verifiedMap = verifiedStaticTagsByScope.computeIfAbsent(scopeKey) { LinkedHashMap() }
            val emptySet = emptyStaticTagsByScope.computeIfAbsent(scopeKey) { ConcurrentHashMap.newKeySet() }

            // Phase 1: Instant local Room hydration across the whole pool (0 network requests)
            for (chip in candidatePool) {
                val keyLower = chip.query.lowercase()
                if (keyLower in emptySet) continue
                if (synchronized(verifiedMap) { verifiedMap.containsKey(keyLower) }) continue
                val cachedCount = runCatching {
                    repository.getCachedStaticTagCount(
                        tag = chip.query,
                        selectedCategory = selectedCategory,
                        filter = filter,
                    )
                }.getOrDefault(0)
                if (cachedCount > 0) {
                    synchronized(verifiedMap) {
                        verifiedMap[keyLower] = chip.copy(count = cachedCount)
                    }
                }
            }

            fun publishVerifiedSnapshot() {
                val snapshot = synchronized(verifiedMap) { verifiedMap.values.toList() }
                _rawState.update { state ->
                    if (state.filter != filter ||
                        !state.staticFilter.selectedCategory.equals(selectedCategory, ignoreCase = true)
                    ) {
                        state
                    } else {
                        state.copy(verifiedStaticTags = snapshot)
                    }
                }
            }

            publishVerifiedSnapshot()

            // Determine which unprobed candidates to check next, rotating offset on pull-to-refresh
            // so fresh themes get verified and added to the verified pool.
            val startOffset = if (rotationSeed > 0) (rotationSeed * 4) % candidatePool.size else 0
            val orderedCandidates = List(candidatePool.size) { idx ->
                candidatePool[(startOffset + idx) % candidatePool.size]
            }
            val alreadyVerifiedCount = synchronized(verifiedMap) { verifiedMap.size }
            val desiredProbes = if (alreadyVerifiedCount >= 10 && rotationSeed == 0) {
                0
            } else {
                targetNewProbes
            }
            if (desiredProbes <= 0) return@launch

            val toProbe = orderedCandidates
                .filter { chip ->
                    val keyLower = chip.query.lowercase()
                    keyLower !in emptySet && synchronized(verifiedMap) { !verifiedMap.containsKey(keyLower) }
                }
                .take(desiredProbes)
            if (toProbe.isEmpty()) return@launch

            // Phase 2: Bounded parallel Wallhaven probing (2 concurrent, strictly respects 45 req/min quota)
            val semaphore = Semaphore(permits = 2)
            coroutineScope {
                toProbe.map { chip ->
                    async {
                        semaphore.withPermit {
                            val probe = try {
                                repository.probeStaticTagAvailability(
                                    tag = chip.query,
                                    selectedCategory = selectedCategory,
                                    filter = filter,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                com.example.data.repository.StaticTagProbeResult(
                                    tag = chip.query,
                                    matchCount = 0,
                                    confirmedEmpty = false,
                                )
                            }
                            val keyLower = chip.query.lowercase()
                            if (probe.matchCount > 0) {
                                synchronized(verifiedMap) {
                                    verifiedMap[keyLower] = chip.copy(count = probe.matchCount)
                                }
                                publishVerifiedSnapshot()
                            } else if (probe.confirmedEmpty) {
                                emptySet.add(keyLower)
                                synchronized(verifiedMap) {
                                    verifiedMap.remove(keyLower)
                                }
                                publishVerifiedSnapshot()
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun loadFeed(forceRefresh: Boolean) {
        val current = _rawState.value
        val activeLiveFilter = if (current.isLiveTab) current.liveFilter else null
        val activeStaticFilter = if (!current.isLiveTab) current.staticFilter else null
        loadMoreJob?.cancel()
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            val localSnapshot = repository.getCachedWallpapersSnapshot(
                query = current.activeQuery,
                isLive = current.isLiveTab,
                filter = current.filter,
                page = 1,
                liveFilter = activeLiveFilter,
                staticFilter = activeStaticFilter,
            )
            val cachedLiveTotal = if (activeLiveFilter != null) {
                liveWallpaperRepository.getCachedCatalogSnapshot(activeLiveFilter, page = 1)?.totalCount
            } else {
                null
            }
            val cacheIsFresh = if (localSnapshot.isNotEmpty()) {
                repository.isFeedCacheFresh(
                    query = current.activeQuery,
                    isLive = current.isLiveTab,
                    filter = current.filter,
                    page = 1,
                    liveFilter = activeLiveFilter,
                    staticFilter = activeStaticFilter,
                )
            } else {
                false
            }

            if (localSnapshot.isNotEmpty()) {
                val needsNetworkRefresh = forceRefresh || !cacheIsFresh
                val skipPreUpdateForStaticRefresh = forceRefresh && !current.isLiveTab
                if (!skipPreUpdateForStaticRefresh) {
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
                            liveTotalCount = cachedLiveTotal ?: state.liveTotalCount,
                            trendingBanners = banners,
                            bannerMessage = null,
                            isRefreshing = needsNetworkRefresh,
                            currentPage = 1,
                            hasMore = if (current.isLiveTab) {
                                localSnapshot.size >= com.example.data.repository.LiveWallpaperRepository.DEFAULT_PAGE_SIZE
                            } else {
                                true
                            },
                            isLoadingMore = false,
                        )
                    }
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
                        liveTotalCount = if (current.isLiveTab) null else it.liveTotalCount,
                        bannerMessage = null,
                        currentPage = 1,
                        hasMore = !current.isLiveTab,
                        isLoadingMore = false,
                    )
                }
            }

            val result = try {
                repository.refreshWallpapersIfNeeded(
                    query = current.activeQuery,
                    isLive = current.isLiveTab,
                    filter = current.filter,
                    page = 1,
                    forceRefresh = forceRefresh,
                    liveFilter = activeLiveFilter,
                    staticFilter = activeStaticFilter,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching {
                    Log.e(
                        "LIVE_DEBUG",
                        "loadFeed unexpected error (isLive=${current.isLiveTab}, query=${current.activeQuery}): ${e.message}",
                        e,
                    )
                }
                com.example.data.repository.WallpaperFetchResult(
                    wallpapers = emptyList(),
                    isFromCache = false,
                    hasMore = false,
                    warningMessage = "Unexpected error loading feed (${e.javaClass.simpleName}: ${e.localizedMessage ?: "unknown"}).",
                )
            }
            val resolvedItems = result.wallpapers.ifEmpty { localSnapshot }
            if (current.isLiveTab) {
                runCatching {
                    Log.d(
                        "LIVE_DEBUG",
                        "Live tab loaded ${resolvedItems.size} items (filter=${current.liveFilter.cacheKey()}, warning=${result.warningMessage})",
                    )
                }
            }

            // Auto-hide empty static categories or hashtags confirmed by API (HTTP 200 with 0 items)
            // and smoothly return to the parent scope instead of showing an empty screen.
            val emptySelectedStaticTag = if (!current.isLiveTab &&
                current.staticFilter.selectedTag != null &&
                resolvedItems.isEmpty() &&
                result.warningMessage == null &&
                !result.rateLimited
            ) {
                current.staticFilter.selectedTag
            } else {
                null
            }
            if (emptySelectedStaticTag != null) {
                val scopeKey = buildStaticTagScopeKey(current.staticFilter.selectedCategory, current.filter)
                val emptyKey = emptySelectedStaticTag.lowercase()
                emptyStaticTagsByScope.computeIfAbsent(scopeKey) { ConcurrentHashMap.newKeySet() }.add(emptyKey)
                val verifiedMap = verifiedStaticTagsByScope[scopeKey]
                val updatedVerified = if (verifiedMap != null) {
                    synchronized(verifiedMap) {
                        verifiedMap.remove(emptyKey)
                        verifiedMap.values.toList()
                    }
                } else {
                    emptyList()
                }
                val fallbackFilter = StaticFilterState(selectedCategory = current.staticFilter.selectedCategory)
                _rawState.update { state ->
                    state.copy(
                        staticFilter = fallbackFilter,
                        verifiedStaticTags = updatedVerified,
                        activeQuery = fallbackFilter.toQueryKey(WallpaperRepository.HOME_TRENDING_KEY),
                        isRefreshing = false,
                        isLoadingMore = false,
                    )
                }
                loadFeed(forceRefresh = false)
                return@launch
            }

            val emptySelectedStaticCategory = if (!current.isLiveTab &&
                current.staticFilter.selectedCategory != null &&
                current.staticFilter.selectedTag == null &&
                resolvedItems.isEmpty() &&
                result.warningMessage == null &&
                !result.rateLimited
            ) {
                current.staticFilter.selectedCategory
            } else {
                null
            }
            if (emptySelectedStaticCategory != null) {
                _rawState.update { state ->
                    state.copy(
                        hiddenEmptyStaticCategories = state.hiddenEmptyStaticCategories +
                                emptySelectedStaticCategory.lowercase(),
                        staticFilter = StaticFilterState(),
                        activeQuery = WallpaperRepository.HOME_TRENDING_KEY,
                        isRefreshing = false,
                        isLoadingMore = false,
                    )
                }
                loadFeed(forceRefresh = false)
                return@launch
            }

            _rawState.update { state ->
                val status = if (resolvedItems.isNotEmpty()) {
                    FeedStatus.Success(resolvedItems)
                } else {
                    val label = if (current.isLiveTab) {
                        current.liveFilter.summaryLabel().ifBlank { "Live Wallpapers" }
                    } else if (current.staticFilter.isChipFilterActive) {
                        current.staticFilter.summaryLabel().ifBlank { current.activeQuery }
                    } else if (current.activeQuery == WallpaperRepository.HOME_TRENDING_KEY) {
                        "Hot Anime"
                    } else {
                        current.activeQuery
                    }
                    val emptyReason = if (current.isLiveTab) {
                        val suggested = buildList {
                            state.liveCatalogFacets.categories.take(2).forEach { add("\"${it.label}\"") }
                            state.liveCatalogFacets.tags.take(2).forEach { add("\"${it.label}\"") }
                        }.take(3).ifEmpty { listOf("\"Anime\"", "\"Games\"", "\"#dark\"") }
                        "No live wallpapers matched \"$label\". Try ${suggested.joinToString(", ")}."
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
                    liveTotalCount = if (current.isLiveTab) (result.totalCount ?: resolvedItems.size) else null,
                    trendingBanners = banners,
                    bannerMessage = if (resolvedItems.isNotEmpty()) result.warningMessage else null,
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
        if (_rawState.value.isProcessingAction) return
        viewModelScope.launch {
            _rawState.update {
                it.copy(
                    isProcessingAction = true,
                    liveDownloadProgress = 2,
                    processingLabel = "Preparing wallpaper…",
                )
            }
            val result = bitmapCropper.setStaticWallpaper(
                imageUrl = wallpaper.imageUrl,
                target = target,
                fallbackThumbnailUrl = wallpaper.thumbnailUrl,
                onProgress = { percent, statusLabel ->
                    _rawState.update { state ->
                        state.copy(
                            isProcessingAction = true,
                            liveDownloadProgress = percent.coerceIn(0, 100),
                            processingLabel = statusLabel,
                        )
                    }
                },
            )
            _rawState.update {
                it.copy(
                    isProcessingAction = false,
                    liveDownloadProgress = null,
                    processingLabel = null,
                )
            }
            result.fold(
                onSuccess = {
                    _rawState.update { it.copy(appliedSuccessTarget = target.label) }
                    _events.emit(UiEvent.WallpaperApplied)
                },
                onFailure = { err ->
                    _events.emit(
                        UiEvent.ShowMessage(err.message ?: "Could not apply wallpaper. Please try again."),
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

        val alreadyDownloaded = liveWallpaperRepository.isDownloaded(wallpaper.id)
        _rawState.update {
            it.copy(
                isProcessingAction = true,
                liveDownloadProgress = if (alreadyDownloaded) 100 else 0,
                processingLabel = if (alreadyDownloaded) {
                    "Ready from offline storage (100%)…"
                } else {
                    "Preparing Live Wallpaper…"
                },
            )
        }

        viewModelScope.launch {
            val downloadResult = liveWallpaperRepository.downloadFullVideo(
                wallpaperId = wallpaper.id,
                fullUrl = videoUrl,
                onProgress = { percent ->
                    // MutableStateFlow.update is thread-safe when invoked from Dispatchers.IO.
                    _rawState.update { state ->
                        state.copy(
                            isProcessingAction = true,
                            liveDownloadProgress = percent.coerceIn(0, 100),
                            processingLabel = "Downloading Live Video... ${percent.coerceIn(0, 100)}%",
                        )
                    }
                },
            )

            downloadResult.fold(
                onSuccess = { downloadedFile ->
                    MediaDownloadManager.setActiveLiveVideoFile(
                        context = getApplication(),
                        file = downloadedFile,
                    )
                    settingsStore.setActiveLiveWallpaper(downloadedFile.absolutePath, wallpaper.title)
                    _rawState.update {
                        it.copy(
                            isProcessingAction = false,
                            liveDownloadProgress = null,
                            processingLabel = null,
                            appliedSuccessTarget = null,
                        )
                    }
                    _events.emit(UiEvent.LaunchLiveWallpaperChooser(target))
                },
                onFailure = { err ->
                    _rawState.update {
                        it.copy(
                            isProcessingAction = false,
                            liveDownloadProgress = null,
                            processingLabel = null,
                        )
                    }
                    _events.emit(
                        UiEvent.ShowMessage(err.localizedMessage ?: "Failed to download live wallpaper."),
                    )
                },
            )
        }
    }

    fun clearDownloadedLiveVideos() {
        viewModelScope.launch {
            val activePath = settings.value.activeLiveWallpaperPath
            val deletedCount = withContext(Dispatchers.IO) {
                liveWallpaperRepository.clearDownloadedVideos(keepFilePath = activePath)
            }
            val message = if (deletedCount > 0) {
                "Cleared $deletedCount downloaded live video${if (deletedCount == 1) "" else "s"} (active wallpaper kept)."
            } else {
                "No unused downloaded live videos to clear."
            }
            _events.emit(UiEvent.ShowMessage(message))
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
            com.example.ui.navigation.DetailRoute.FEED_COLLECTION -> collectionDetailState.value.items
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
        private const val LIVE_COVER_PROBABILITY = 0.45f

        /**
         * Selects the next random cover candidate for a SearchScreen Trending Category card,
         * mixing static artwork covers and live wallpaper covers while avoiding [previousUrl]
         * whenever alternatives exist so returning to SearchScreen visibly refreshes covers.
         */
        internal fun pickNextRandomCover(
            staticCandidates: List<CategoryCoverCandidate>,
            liveCandidates: List<CategoryCoverCandidate>,
            previousUrl: String?,
            random: Random = Random.Default,
        ): CategoryCoverCandidate? {
            if (staticCandidates.isEmpty() && liveCandidates.isEmpty()) return null

            val staticAlternatives = staticCandidates.filter { it.imageUrl != previousUrl }
            val liveAlternatives = liveCandidates.filter { it.imageUrl != previousUrl }

            return when {
                staticCandidates.isNotEmpty() && liveCandidates.isNotEmpty() -> {
                    val preferLive = random.nextFloat() < LIVE_COVER_PROBABILITY
                    val primaryPool = if (preferLive) liveAlternatives else staticAlternatives
                    val secondaryPool = if (preferLive) staticAlternatives else liveAlternatives
                    when {
                        primaryPool.isNotEmpty() -> primaryPool[random.nextInt(primaryPool.size)]
                        secondaryPool.isNotEmpty() -> secondaryPool[random.nextInt(secondaryPool.size)]
                        else -> (liveCandidates + staticCandidates).first()
                    }
                }
                liveCandidates.isNotEmpty() -> {
                    val pool = liveAlternatives.ifEmpty { liveCandidates }
                    pool[random.nextInt(pool.size)]
                }
                else -> {
                    val pool = staticAlternatives.ifEmpty { staticCandidates }
                    pool[random.nextInt(pool.size)]
                }
            }
        }

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainViewModel(app)
            }
        }
    }
}
