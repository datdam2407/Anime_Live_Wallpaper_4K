package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayCircleFilled
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuroanimelivewallpapers.app.R
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Scale
import com.example.domain.model.AppWallpaper
import com.example.domain.model.RatioFilter
import com.example.domain.model.ResolutionTier
import com.example.domain.model.SortOption
import com.example.domain.model.WallpaperFilter
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianSurfaceHighest
import com.example.ui.theme.PremiumGold
import com.example.ui.theme.PremiumGoldDeep
import com.example.ui.theme.PremiumGoldLight
import com.example.ui.theme.PremiumGoldSurface
import com.example.ui.theme.SilverDeep
import com.example.ui.theme.SilverLight
import com.example.ui.theme.SilverMetallic

val PremiumGoldBorderBrush: Brush = Brush.linearGradient(
    colors = listOf(PremiumGoldLight, PremiumGold, PremiumGoldDeep, PremiumGold),
)

val SilverBorderBrush: Brush = Brush.linearGradient(
    colors = listOf(SilverLight, SilverMetallic, SilverDeep, SilverMetallic),
)

@Composable
fun PremiumGoldBadge(
    label: String = "PREMIUM",
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = PremiumGoldSurface.copy(alpha = 0.90f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, PremiumGoldBorderBrush),
        shadowElevation = if (compact) 0.dp else 4.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(
                horizontal = if (compact) 5.5.dp else 8.dp,
                vertical = if (compact) 2.5.dp else 4.dp,
            ),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_premium_crown),
                contentDescription = "Premium",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(
                    width = if (compact) 14.dp else 18.dp,
                    height = if (compact) 10.dp else 13.dp,
                ),
            )
            Spacer(modifier = Modifier.width(if (compact) 3.dp else 4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (compact) 8.sp else 9.5.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.6.sp,
                ),
                color = PremiumGoldLight,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Staggered Grid card that strictly loads thumbnailUrl (never full 4K imageUrl)
 * with ContentScale.Crop to prevent OOM and keep scrolling fluid.
 */
@Composable
fun WallpaperStaggeredCard(
    wallpaper: AppWallpaper,
    staggeredRatio: Float,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)

    val density = LocalDensity.current
    var isLoading by remember(wallpaper.id, wallpaper.thumbnailUrl) { mutableStateOf(true) }
    var useVideoFrameFallback by remember(wallpaper.id, wallpaper.thumbnailUrl) { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(staggeredRatio)
            .clip(shape)
            .background(ObsidianSurfaceElevated)
            .then(
                if (wallpaper.isLive) {
                    Modifier.border(1.3.dp, PremiumGoldBorderBrush, shape)
                } else {
                    Modifier.border(1.2.dp, SilverBorderBrush, shape)
                },
            )
            .clickable(onClick = onClick)
            .testTag("wallpaper_card_${wallpaper.id}"),
    ) {
        val targetWidthPx = with(density) { maxWidth.roundToPx().coerceIn(240, 960) }
        val targetHeightPx = with(density) { maxHeight.roundToPx().coerceIn(320, 1280) }
        val activeUrl = if (useVideoFrameFallback && !wallpaper.videoUrl.isNullOrBlank()) {
            wallpaper.videoUrl
        } else {
            wallpaper.thumbnailUrl
        }
        val imageRequest = remember(activeUrl, targetWidthPx, targetHeightPx) {
            ImageRequest.Builder(context)
                .data(activeUrl)
                .size(targetWidthPx, targetHeightPx)
                .precision(Precision.INEXACT)
                .scale(Scale.FILL)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .allowHardware(true)
                .crossfade(180)
                .build()
        }

        if (isLoading) {
            ShimmerBox(modifier = Modifier.fillMaxSize())
        }

        if (useVideoFrameFallback && wallpaper.videoUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ObsidianSurfaceHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = null,
                    tint = InkMuted,
                    modifier = Modifier.size(32.dp),
                )
            }
        } else {
            AsyncImage(
                model = imageRequest,
                contentDescription = "${wallpaper.title} wallpaper thumbnail",
                contentScale = ContentScale.Crop,
                onSuccess = { isLoading = false },
                onError = {
                    isLoading = false
                    useVideoFrameFallback = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Clean card with clear quality/resolution badge + quick favorite button
        val isUhd = wallpaper.resolutionLabel == "4K UHD"
        val isQhd = wallpaper.resolutionLabel == "2K QHD"
        Surface(
            color = if (wallpaper.isLive) {
                Color.Black.copy(alpha = 0.76f)
            } else {
                Color.Black.copy(alpha = 0.68f)
            },
            shape = RoundedCornerShape(50),
            border = if (wallpaper.isLive) {
                BorderStroke(1.dp, PremiumGoldBorderBrush)
            } else {
                BorderStroke(
                    width = 1.dp,
                    color = when {
                        isUhd -> CyberCyan.copy(alpha = 0.70f)
                        isQhd -> ElectricViolet.copy(alpha = 0.65f)
                        else -> HairlineBorder
                    },
                )
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .testTag("card_resolution_badge_${wallpaper.id}"),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                if (wallpaper.isLive) {
                    Icon(
                        imageVector = Icons.Rounded.PlayCircleFilled,
                        contentDescription = "Live video wallpaper",
                        tint = PremiumGoldLight,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = wallpaper.resolutionLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isUhd && !wallpaper.isLive) CyberCyan else InkWhite,
                )
            }
        }

        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.42f))
                .testTag("card_favorite_${wallpaper.id}"),
        ) {
            Icon(
                imageVector = if (wallpaper.isFavorite) {
                    Icons.Rounded.Favorite
                } else {
                    Icons.Rounded.FavoriteBorder
                },
                contentDescription = if (wallpaper.isFavorite) {
                    "Remove from favorites"
                } else {
                    "Save to favorites"
                },
                tint = if (wallpaper.isFavorite) CrimsonBlade else InkWhite.copy(alpha = 0.92f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
fun ShimmerBox(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_offset",
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            ObsidianSurfaceElevated,
            ObsidianSurfaceHighest,
            ObsidianSurfaceElevated,
        ),
        start = Offset(translateAnim - 300f, translateAnim - 300f),
        end = Offset(translateAnim, translateAnim),
    )
    Box(modifier = modifier.background(brush))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterBottomSheet(
    currentFilter: WallpaperFilter,
    onDismiss: () -> Unit,
    onApply: (WallpaperFilter) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedResolution by remember(currentFilter) { mutableStateOf(currentFilter.resolution) }
    var selectedSort by remember(currentFilter) { mutableStateOf(currentFilter.sort) }
    var selectedRatio by remember(currentFilter) { mutableStateOf(currentFilter.ratio) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ObsidianSurface,
        contentColor = InkWhite,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = CrimsonBlade,
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Quality & Feed Filters",
                        style = MaterialTheme.typography.headlineMedium,
                        color = InkWhite,
                    )
                }
                TextButton(
                    onClick = {
                        selectedResolution = ResolutionTier.ALL
                        selectedSort = SortOption.RELEVANCE
                        selectedRatio = RatioFilter.ALL
                    },
                ) {
                    Text("Reset", color = InkMuted)
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "MINIMUM RESOLUTION",
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ResolutionTier.entries.forEach { tier ->
                    FilterChip(
                        selected = selectedResolution == tier,
                        onClick = { selectedResolution = tier },
                        label = { Text(tier.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CrimsonBlade,
                            selectedLabelColor = InkWhite,
                            containerColor = ObsidianSurfaceElevated,
                            labelColor = InkMuted,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "SORTING",
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SortOption.entries.forEach { option ->
                    FilterChip(
                        selected = selectedSort == option,
                        onClick = { selectedSort = option },
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ElectricViolet,
                            selectedLabelColor = InkWhite,
                            containerColor = ObsidianSurfaceElevated,
                            labelColor = InkMuted,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "PORTRAIT ASPECT RATIO",
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RatioFilter.entries.forEach { ratio ->
                    FilterChip(
                        selected = selectedRatio == ratio,
                        onClick = { selectedRatio = ratio },
                        label = { Text(ratio.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CyberCyan.copy(alpha = 0.22f),
                            selectedLabelColor = CyberCyan,
                            containerColor = ObsidianSurfaceElevated,
                            labelColor = InkMuted,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    onApply(
                        WallpaperFilter(
                            resolution = selectedResolution,
                            sort = selectedSort,
                            ratio = selectedRatio,
                        ),
                    )
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CrimsonBlade,
                    contentColor = InkWhite,
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("apply_filter_button"),
            ) {
                Text(
                    text = "Apply Filters",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
