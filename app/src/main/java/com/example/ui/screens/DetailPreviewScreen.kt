package com.example.ui.screens


import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.kuroanimelivewallpapers.app.R
import com.example.ads.AdMobConstants
import com.example.ads.DetailPagerEntry
import com.example.ads.NativeAdCard
import com.example.ads.NativeAdStyle
import com.example.ads.buildDetailPagerEntries
import com.example.ads.mapWallpaperIndexToPagerPosition
import com.example.domain.model.AppWallpaper
import com.example.domain.model.WallpaperTarget
import com.example.service.DelogoEffect
import com.example.ui.components.PremiumGoldBorderBrush
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianVoid
import com.example.ui.theme.PremiumGoldLight
import com.example.ui.theme.PremiumGoldSurface
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue


@Composable
fun DetailPreviewScreen(
    wallpapers: List<AppWallpaper>,
    initialIndex: Int,
    favoriteIdsState: State<Set<String>>,
    isProcessing: Boolean, // Giữ lại để chặn click đúp
    processingLabel: String?,
    liveDownloadProgress: Int? = null,
    appliedSuccessTarget: String?,
    muteLiveAudio: Boolean,
    onBack: () -> Unit,
    onSetStaticWallpaper: (AppWallpaper, WallpaperTarget) -> Unit,
    onSetLiveWallpaper: (AppWallpaper) -> Unit,
    onToggleFavorite: (AppWallpaper) -> Unit,
    onDismissAppliedSuccess: () -> Unit,
    onDetailImageViewed: (String) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()


    if (wallpapers.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianVoid),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No wallpaper selected", color = InkWhite)
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = onBack) { Text("Back") }
            }
        }
        return
    }


    val pagerEntries = remember(wallpapers) {
        buildDetailPagerEntries(
            wallpapers = wallpapers,
            interval = AdMobConstants.DETAIL_PAGER_NATIVE_AD_INTERVAL,
        )
    }
    val initialPagerIndex = remember(pagerEntries, initialIndex) {
        mapWallpaperIndexToPagerPosition(pagerEntries, initialIndex)
    }
    val pagerState = rememberPagerState(
        initialPage = initialPagerIndex,
        pageCount = { pagerEntries.size },
    )
    var isFullScreen by remember { mutableStateOf(false) }
    var showTargetDialog by remember { mutableStateOf(false) }


    BackHandler {
        when {
            appliedSuccessTarget != null -> onDismissAppliedSuccess()
            showTargetDialog -> showTargetDialog = false
            isFullScreen -> isFullScreen = false
            else -> onBack()
        }
    }


    val context = LocalContext.current
    val density = LocalDensity.current
    val prefetchSizePx = remember(density) {
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.coerceIn(480, 1440)
        val h = metrics.heightPixels.coerceIn(800, 2560)
        w to h
    }


    val currentPage by remember(pagerState) { derivedStateOf { pagerState.currentPage } }
    val settledPage by remember(pagerState) { derivedStateOf { pagerState.settledPage } }


    LaunchedEffect(settledPage, pagerEntries) {
        showTargetDialog = false
        val entry = pagerEntries.getOrNull(settledPage)
        if (entry is DetailPagerEntry.WallpaperPage) {
            onDetailImageViewed(entry.wallpaper.id)
        } else {
            isFullScreen = false
        }


        // Pre-warm neighbor pages
        val loader = context.imageLoader
        listOf(settledPage - 1, settledPage + 1).forEach { neighborIdx ->
            val neighbor = (pagerEntries.getOrNull(neighborIdx) as? DetailPagerEntry.WallpaperPage)?.wallpaper
            if (neighbor != null) {
                loader.enqueue(
                    ImageRequest.Builder(context)
                        .data(neighbor.thumbnailUrl)
                        .size(prefetchSizePx.first / 2, prefetchSizePx.second / 2)
                        .precision(Precision.INEXACT)
                        .scale(Scale.FILL)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                )
                if (!neighbor.isLive) {
                    loader.enqueue(
                        ImageRequest.Builder(context)
                            .data(neighbor.imageUrl)
                            .size(prefetchSizePx.first, prefetchSizePx.second)
                            .precision(Precision.INEXACT)
                            .scale(Scale.FILL)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .build(),
                    )
                }
            }
        }
    }


    val currentEntry = pagerEntries.getOrNull(currentPage)
    val currentWallpaperPage = currentEntry as? DetailPagerEntry.WallpaperPage
    val currentWallpaper = currentWallpaperPage?.wallpaper


    // ĐIỀU KIỆN ẨN DIALOG MP4: Bắt buộc currentWallpaper.isLive phải bằng True!
    if (showTargetDialog && currentWallpaper != null && !currentWallpaper.isLive) {
        WallpaperTargetDialog(
            onDismiss = { showTargetDialog = false },
            onSelectTarget = { target ->
                showTargetDialog = false
                onSetStaticWallpaper(currentWallpaper, target)
            },
        )
    }


    fun triggerSetWallpaper(wallpaper: AppWallpaper) {
        // Vẫn chặn nếu đang processing ngầm để tránh người dùng nhấn 2-3 lần liên tiếp gây crash
        if (isProcessing) return


        if (wallpaper.isLive) {
            onSetLiveWallpaper(wallpaper) // Sẽ mở màn hình của hệ điều hành
        } else {
            showTargetDialog = true // Mở dialog 3 options của mình
        }
    }


    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0C0B12), ObsidianVoid, Color(0xFF0B0914)),
                ),
            )
            .testTag("detail_preview_screen"),
    ) {
        if (isFullScreen && currentWallpaper != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        isFullScreen = false
                    },
            ) {
                WallpaperMediaContent(
                    item = currentWallpaper,
                    isActive = true,
                    muted = muteLiveAudio,
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = { isFullScreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.52f)),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FullscreenExit,
                        contentDescription = "Exit fullscreen preview",
                        tint = InkWhite,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(ObsidianSurfaceElevated.copy(alpha = 0.72f))
                            .testTag("detail_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Navigate back",
                            tint = InkWhite,
                        )
                    }


                    val isCurrentLive = currentWallpaperPage?.wallpaper?.isLive == true
                    Surface(
                        color = if (isCurrentLive) {
                            PremiumGoldSurface.copy(alpha = 0.88f)
                        } else {
                            ObsidianSurfaceElevated.copy(alpha = 0.85f)
                        },
                        shape = RoundedCornerShape(50),
                        border = if (isCurrentLive) {
                            BorderStroke(1.dp, PremiumGoldBorderBrush)
                        } else {
                            BorderStroke(1.dp, HairlineBorder)
                        },
                    ) {
                        val badgeLabel = if (currentWallpaperPage != null) {
                            "${currentWallpaperPage.wallpaper.resolutionLabel} • ${currentWallpaperPage.wallpaperIndex + 1}/${wallpapers.size}"
                        } else {
                            "Sponsored • Ad"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            if (isCurrentLive) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_premium_crown),
                                    contentDescription = "Premium",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(width = 18.dp, height = 13.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = badgeLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = when {
                                    currentWallpaperPage == null -> ElectricViolet
                                    isCurrentLive -> PremiumGoldLight
                                    else -> InkWhite
                                },
                            )
                        }
                    }


                    if (currentWallpaper != null) {
                        IconButton(
                            onClick = { isFullScreen = true },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(ObsidianSurfaceElevated.copy(alpha = 0.72f))
                                .testTag("detail_fullscreen_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Fullscreen,
                                contentDescription = "Full screen preview",
                                tint = InkWhite,
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.size(48.dp))
                    }
                }


                HorizontalPager(
                    state = pagerState,
                    key = { page ->
                        when (val entry = pagerEntries[page]) {
                            is DetailPagerEntry.WallpaperPage -> entry.wallpaper.id
                            is DetailPagerEntry.NativeAdPage -> entry.slotKey
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 42.dp),
                    pageSpacing = 14.dp,
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .heightIn(min = 240.dp)
                        .testTag("detail_carousel_pager"),
                ) { page ->
                    val entry = pagerEntries[page]
                    val isCurrentPage by remember(pagerState, page) {
                        derivedStateOf { pagerState.currentPage == page }
                    }


                    val cardTransformModifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 6.dp)
                        .graphicsLayer {
                            val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                                .absoluteValue
                                .coerceIn(0f, 1f)
                            val cardScale = lerp(1f, 0.91f, pageOffset)
                            scaleX = cardScale
                            scaleY = cardScale
                            alpha = lerp(1f, 0.68f, pageOffset)
                        }


                    when (entry) {
                        is DetailPagerEntry.WallpaperPage -> {
                            val item = entry.wallpaper
                            val itemIsFavorite by remember(item.id, favoriteIdsState) {
                                derivedStateOf { item.id in favoriteIdsState.value }
                            }
                            CarouselWallpaperCard(
                                item = item,
                                isActive = isCurrentPage,
                                isFavorite = itemIsFavorite,
                                muted = muteLiveAudio,
                                onCardClick = {
                                    if (isCurrentPage) {
                                        isFullScreen = true
                                    } else {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(page)
                                        }
                                    }
                                },
                                onSetWallpaperClick = {
                                    if (isCurrentPage) {
                                        triggerSetWallpaper(item)
                                    } else {
                                        coroutineScope.launch {
                                            pagerState.animateScrollToPage(page)
                                        }
                                    }
                                },
                                onToggleFavoriteClick = {
                                    onToggleFavorite(item)
                                },
                                modifier = cardTransformModifier,
                            )
                        }


                        is DetailPagerEntry.NativeAdPage -> {
                            NativeAdCard(
                                style = NativeAdStyle.FULL_PAGER,
                                slotKey = entry.slotKey,
                                modifier = cardTransformModifier,
                            )
                        }
                    }
                }


                if (currentWallpaper != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(top = 6.dp, bottom = if (currentWallpaper.isLive) 6.dp else 10.dp)
                            .testTag("detail_bottom_dock"),
                    ) {
                        NativeAdCard(
                            style = NativeAdStyle.DETAIL_BOTTOM,
                            slotKey = "detail_bottom_dock",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (currentWallpaper.isLive) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .padding(bottom = 10.dp)
                                .testTag("live_wallpaper_install_note"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = CyberCyan.copy(alpha = 0.85f),
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(id = R.string.live_wallpaper_keep_installed_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = InkMuted,
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
        }


        AnimatedVisibility(
            visible = isProcessing,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            WallpaperProcessingOverlay(
                label = processingLabel ?: "Preparing Live Wallpaper…",
                progress = liveDownloadProgress,
            )
        }


        AnimatedVisibility(
            visible = appliedSuccessTarget != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            WallpaperAppliedSuccessPopup(
                targetLabel = appliedSuccessTarget.orEmpty(),
                onDismiss = onDismissAppliedSuccess,
            )
        }
    }
}


@Composable
private fun WallpaperAppliedSuccessPopup(
    targetLabel: String,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .testTag("wallpaper_applied_popup"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 20.dp,
            border = BorderStroke(1.dp, InkWhite.copy(alpha = 0.24f)),
            color = Color.Transparent,
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .widthIn(max = 380.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF7395D9),
                                Color(0xFF7B64C0),
                            ),
                        ),
                    )
                    .padding(horizontal = 22.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Congratulations",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = InkWhite,
                )


                Spacer(modifier = Modifier.height(14.dp))


                Text(
                    text = "Wallpaper applied successfully!",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = InkWhite.copy(alpha = 0.95f),
                )


                if (targetLabel.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = targetLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = InkWhite.copy(alpha = 0.78f),
                    )
                }


                Spacer(modifier = Modifier.height(24.dp))


                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF3F6FA),
                        contentColor = Color(0xFF1A1924),
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("wallpaper_applied_ok_button"),
                ) {
                    Text(
                        text = "OK",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
        }
    }
}


@Composable
private fun WallpaperProcessingOverlay(
    label: String,
    progress: Int?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.56f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .testTag("detail_processing_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = ObsidianSurfaceElevated,
            shadowElevation = 20.dp,
            border = BorderStroke(
                width = 1.dp,
                color = if (progress != null) CyberCyan.copy(alpha = 0.42f) else HairlineBorder,
            ),
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .widthIn(max = 360.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            color = CyberCyan,
                            trackColor = InkWhite.copy(alpha = 0.14f),
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = if (progress != null) "Downloading Live Wallpaper" else "Applying Wallpaper",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = InkWhite,
                        )
                    }
                    if (progress != null) {
                        Text(
                            text = "${progress.coerceIn(0, 100)}%",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = CyberCyan,
                        )
                    }
                }

                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0, 100) / 100f },
                        color = CyberCyan,
                        trackColor = InkWhite.copy(alpha = 0.14f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .testTag("detail_processing_bar"),
                    )
                } else {
                    LinearProgressIndicator(
                        color = ElectricViolet,
                        trackColor = InkWhite.copy(alpha = 0.14f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .testTag("detail_processing_bar"),
                    )
                }

                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkMuted,
                )
            }
        }
    }
}


@Composable
private fun CarouselWallpaperCard(
    item: AppWallpaper,
    isActive: Boolean,
    isFavorite: Boolean,
    muted: Boolean,
    onCardClick: () -> Unit,
    onSetWallpaperClick: () -> Unit,
    onToggleFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(24.dp)


    Surface(
        shape = cardShape,
        color = ObsidianSurfaceElevated,
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) InkWhite.copy(alpha = 0.24f) else HairlineBorder,
        ),
        shadowElevation = if (isActive) 14.dp else 4.dp,
        modifier = modifier
            .clip(cardShape)
            .clickable(onClick = onCardClick),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            WallpaperMediaContent(
                item = item,
                isActive = isActive,
                muted = muted,
                modifier = Modifier.fillMaxSize(),
            )


            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(110.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                        ),
                    ),
            )


            IconButton(
                onClick = onToggleFavoriteClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 68.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (isFavorite) {
                            CrimsonBlade.copy(alpha = 0.26f)
                        } else {
                            Color.Black.copy(alpha = 0.48f)
                        },
                    )
                    .testTag(if (isActive) "detail_favorite_button" else "detail_favorite_side_${item.id}"),
            ) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove favorite" else "Save favorite",
                    tint = if (isFavorite) CrimsonBlade else InkWhite,
                    modifier = Modifier.size(24.dp),
                )
            }


            Surface(
                color = if (item.isLive) {
                    Color(0xE63D196E)
                } else {
                    Color(0xCC2A2836)
                },
                shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                border = if (item.isLive) BorderStroke(1.dp, PremiumGoldBorderBrush) else null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(54.dp)
                    .clickable(onClick = onSetWallpaperClick)
                    .testTag(if (isActive) "set_wallpaper_button" else "set_wallpaper_side_${item.id}"),
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (item.isLive) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_premium_crown),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(width = 21.dp, height = 15.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Wallpaper,
                            contentDescription = null,
                            tint = InkWhite,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (item.isLive) "Apply Live Wallpaper" else "Apply Wallpaper",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = InkWhite,
                    )
                }
            }
        }
    }
}


@Composable
private fun WallpaperMediaContent(
    item: AppWallpaper,
    isActive: Boolean,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    if (item.isLive && !item.videoUrl.isNullOrBlank()) {
        LiveMp4FullScreenPlayer(
            videoUrl = item.videoUrl,
            thumbnailUrl = item.thumbnailUrl,
            isActive = isActive,
            muted = muted,
            modifier = modifier,
        )
    } else {
        var isHighResLoading by remember(item.id, item.imageUrl) { mutableStateOf(true) }
        BoxWithConstraints(modifier = modifier) {
            val targetWidthPx = with(density) { maxWidth.roundToPx().coerceIn(480, 1440) }
            val targetHeightPx = with(density) { maxHeight.roundToPx().coerceIn(640, 2560) }


            val thumbRequest = remember(item.thumbnailUrl, targetWidthPx, targetHeightPx) {
                ImageRequest.Builder(context)
                    .data(item.thumbnailUrl)
                    .size((targetWidthPx / 2).coerceAtLeast(360), (targetHeightPx / 2).coerceAtLeast(480))
                    .precision(Precision.INEXACT)
                    .scale(Scale.FILL)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .allowHardware(true)
                    .build()
            }
            val fullRequest = remember(item.imageUrl, targetWidthPx, targetHeightPx) {
                ImageRequest.Builder(context)
                    .data(item.imageUrl)
                    .size(targetWidthPx, targetHeightPx)
                    .precision(Precision.INEXACT)
                    .scale(Scale.FILL)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .allowHardware(true)
                    .crossfade(160)
                    .build()
            }


            val thumbPainter = rememberAsyncImagePainter(
                model = thumbRequest,
                contentScale = ContentScale.Crop,
            )
            AsyncImage(
                model = fullRequest,
                contentDescription = "${item.title} full resolution preview",
                placeholder = thumbPainter,
                error = thumbPainter,
                contentScale = ContentScale.Crop,
                onSuccess = { isHighResLoading = false },
                onError = { isHighResLoading = false },
                modifier = Modifier.fillMaxSize(),
            )
            if (isHighResLoading && isActive) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = CrimsonBlade,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(34.dp),
                    )
                }
            }
        }
    }
}


@OptIn(UnstableApi::class)
@Composable
private fun LiveMp4FullScreenPlayer(
    videoUrl: String,
    thumbnailUrl: String,
    isActive: Boolean,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isHostStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }


    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> isHostStarted = true
                Lifecycle.Event.ON_STOP -> isHostStarted = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }


    val shouldPlay = isActive && isHostStarted


    BoxWithConstraints(modifier = modifier) {
        val targetWidthPx = with(density) { maxWidth.roundToPx().coerceIn(360, 1080) }
        val targetHeightPx = with(density) { maxHeight.roundToPx().coerceIn(480, 1920) }
        val thumbRequest = remember(thumbnailUrl, targetWidthPx, targetHeightPx) {
            ImageRequest.Builder(context)
                .data(thumbnailUrl)
                .size(targetWidthPx, targetHeightPx)
                .precision(Precision.INEXACT)
                .scale(Scale.FILL)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .allowHardware(true)
                .build()
        }


        AsyncImage(
            model = thumbRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )


        if (shouldPlay) {
            ActiveExoPlayerOverlay(
                videoUrl = videoUrl,
                muted = muted,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}


@OptIn(UnstableApi::class)
@Composable
private fun ActiveExoPlayerOverlay(
    videoUrl: String,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Enable Media3 GPU DelogoEffect on physical devices; fall back to direct hardware
    // MediaCodec TextureView output on emulators (EGL_emulation) or after a GL error.
    var useEffects by remember(videoUrl) { mutableStateOf(DelogoEffect.isSupportedOnDevice()) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }


    val screenBounds = remember(context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm == null) {
            1080 to 1920
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width().coerceAtLeast(480) to bounds.height().coerceAtLeast(640)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay?.getRealMetrics(metrics)
            (metrics.widthPixels.takeIf { it > 0 } ?: 1080) to
                    (metrics.heightPixels.takeIf { it > 0 } ?: 1920)
        }
    }


    val player = remember(videoUrl, useEffects) {
        val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 3000, 500, 1000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val trackParams = TrackSelectionParameters.Builder(context)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, muted)
            .setMaxVideoSize(screenBounds.first, screenBounds.second)
            .build()


        ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                trackSelectionParameters = trackParams
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                repeatMode = Player.REPEAT_MODE_ONE
                volume = if (muted) 0f else 1f
                if (useEffects) {
                    setVideoEffects(listOf(DelogoEffect()))
                }
                addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            if (useEffects) {
                                useEffects = false
                            }
                        }
                    },
                )
                setMediaItem(MediaItem.fromUri(videoUrl))
                prepare()
                playWhenReady = true
                play()
            }
    }


    DisposableEffect(player, muted) {
        player.volume = if (muted) 0f else 1f
        player.trackSelectionParameters = TrackSelectionParameters.Builder(context)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, muted)
            .setMaxVideoSize(screenBounds.first, screenBounds.second)
            .build()
        onDispose {
            player.pause()
        }
    }


    DisposableEffect(player) {
        onDispose {
            playerViewRef?.let { view ->
                if (view.player === player) {
                    view.player = null
                }
            }
            player.clearVideoSurface()
            player.stop()
            player.release()
        }
    }


    AndroidView(
        factory = { ctx ->
            val inflated = LayoutInflater.from(ctx)
                .inflate(R.layout.view_live_texture_player, null, false) as PlayerView
            inflated.apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                playerViewRef = this
                this.player = player
            }
        },
        update = { view ->
            playerViewRef = view
            if (view.player !== player) {
                view.player = player
            }
        },
        onRelease = { view ->
            view.player = null
            if (playerViewRef === view) {
                playerViewRef = null
            }
        },
        modifier = modifier,
    )
}


@Composable
private fun WallpaperTargetDialog(
    onDismiss: () -> Unit,
    onSelectTarget: (WallpaperTarget) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ObsidianSurface,
        titleContentColor = InkWhite,
        textContentColor = InkMuted,
        title = {
            Text("Apply Cropped Wallpaper", style = MaterialTheme.typography.headlineMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Bitmap will be center-cropped to your exact screen resolution before applying.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                TargetOptionRow(
                    icon = Icons.Rounded.Home,
                    label = WallpaperTarget.HOME.label,
                    onClick = { onSelectTarget(WallpaperTarget.HOME) },
                )
                TargetOptionRow(
                    icon = Icons.Rounded.Lock,
                    label = WallpaperTarget.LOCK.label,
                    onClick = { onSelectTarget(WallpaperTarget.LOCK) },
                )
                TargetOptionRow(
                    icon = Icons.Rounded.PhoneAndroid,
                    label = WallpaperTarget.BOTH.label,
                    onClick = { onSelectTarget(WallpaperTarget.BOTH) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = InkMuted)
            }
        },
    )
}


@Composable
private fun TargetOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        color = ObsidianSurfaceElevated,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, HairlineBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CrimsonBlade,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = InkWhite,
            )
        }
    }
}



