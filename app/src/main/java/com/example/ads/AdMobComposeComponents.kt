package com.example.ads

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.KuroWallApplication
import com.kuroanimelivewallpapers.app.R
import com.example.domain.model.AnimeCategory
import com.example.domain.model.AppWallpaper
import com.example.ui.components.ShimmerBox
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.ElectricViolet
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianVoid
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.VideoOptions
import com.google.android.gms.ads.nativead.AdChoicesView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView

enum class NativeAdStyle {
    /** Full-width split landscape/media card interspersed every 6-8 items in the grid. */
    GRID_CARD,

    /** Full-height swipeable Native Ad page shown every 3 items in Detail HorizontalPager. */
    FULL_PAGER,

    /** Compact Native Ad card pinned below metadata & actions on the Detail screen. */
    DETAIL_BOTTOM,
}

sealed interface WallpaperGridEntry {
    data class WallpaperItem(
        val wallpaper: AppWallpaper,
        val originalIndex: Int,
    ) : WallpaperGridEntry

    data class NativeAdSlot(
        val slotKey: String,
    ) : WallpaperGridEntry
}

sealed interface DetailPagerEntry {
    data class WallpaperPage(
        val wallpaper: AppWallpaper,
        val wallpaperIndex: Int,
    ) : DetailPagerEntry

    data class NativeAdPage(
        val slotKey: String,
    ) : DetailPagerEntry
}

/**
 * Intersperses 1 NativeAdSlot after every [interval] wallpaper items (6-8 range).
 */
fun buildFeedWithNativeAds(
    wallpapers: List<AppWallpaper>,
    interval: Int = AdMobConstants.GRID_NATIVE_AD_INTERVAL,
): List<WallpaperGridEntry> {
    if (wallpapers.isEmpty()) return emptyList()
    val safeInterval = interval.coerceAtLeast(1)
    val result = ArrayList<WallpaperGridEntry>(wallpapers.size + wallpapers.size / safeInterval + 1)
    wallpapers.forEachIndexed { index, wallpaper ->
        result += WallpaperGridEntry.WallpaperItem(wallpaper = wallpaper, originalIndex = index)
        if ((index + 1) % safeInterval == 0 && index < wallpapers.lastIndex) {
            result += WallpaperGridEntry.NativeAdSlot(slotKey = "grid_ad_${index + 1}")
        }
    }
    return result
}

/**
 * Intersperses 1 full-page Native Ad after every [interval] wallpapers in the Detail HorizontalPager.
 */
fun buildDetailPagerEntries(
    wallpapers: List<AppWallpaper>,
    interval: Int = AdMobConstants.DETAIL_PAGER_NATIVE_AD_INTERVAL,
): List<DetailPagerEntry> {
    if (wallpapers.isEmpty()) return emptyList()
    val safeInterval = interval.coerceAtLeast(1)
    val entries = ArrayList<DetailPagerEntry>(wallpapers.size + wallpapers.size / safeInterval + 1)
    wallpapers.forEachIndexed { index, wallpaper ->
        entries += DetailPagerEntry.WallpaperPage(wallpaper = wallpaper, wallpaperIndex = index)
        if ((index + 1) % safeInterval == 0 && index < wallpapers.lastIndex) {
            entries += DetailPagerEntry.NativeAdPage(slotKey = "pager_ad_${index + 1}")
        }
    }
    return entries
}

fun mapWallpaperIndexToPagerPosition(
    entries: List<DetailPagerEntry>,
    wallpaperIndex: Int,
): Int {
    if (entries.isEmpty()) return 0
    val found = entries.indexOfFirst {
        it is DetailPagerEntry.WallpaperPage && it.wallpaperIndex == wallpaperIndex
    }
    return found.coerceAtLeast(0)
}

sealed interface CategoryGridEntry {
    data class CategoryItem(
        val category: AnimeCategory,
        val originalIndex: Int,
    ) : CategoryGridEntry

    data class NativeAdSlot(
        val slotKey: String,
        val afterItemCount: Int,
    ) : CategoryGridEntry
}

/**
 * Calculates progressive item counts after which Native Ads are inserted to prevent user fatigue.
 * Sequence for initialGap=6, gapStep=2:
 * - Ad 1 after 6 items (item threshold 6)
 * - Ad 2 after 8 more items (item threshold 14)
 * - Ad 3 after 10 more items (item threshold 24)
 * - Ad 4 after 12 more items (item threshold 36), etc.
 * Only thresholds strictly less than [totalItems] are returned so no orphan ad trails at the end.
 */
fun calculateProgressiveAdPositions(
    totalItems: Int,
    initialGap: Int = AdMobConstants.ANIME_200_INITIAL_AD_GAP,
    gapStep: Int = AdMobConstants.ANIME_200_AD_GAP_STEP,
): List<Int> {
    if (totalItems <= 0 || initialGap <= 0) return emptyList()
    val positions = mutableListOf<Int>()
    var currentGap = initialGap
    var nextThreshold = currentGap
    while (nextThreshold < totalItems) {
        positions += nextThreshold
        currentGap += gapStep.coerceAtLeast(0)
        nextThreshold += currentGap
    }
    return positions
}

/**
 * Intersperses full-width Native Ad slots inside the Anime 200 list/grid at progressive gaps
 * (6, +8 = 14, +10 = 24, +12 = 36, ...).
 */
fun buildCategoriesWithProgressiveAds(
    categories: List<AnimeCategory>,
    initialGap: Int = AdMobConstants.ANIME_200_INITIAL_AD_GAP,
    gapStep: Int = AdMobConstants.ANIME_200_AD_GAP_STEP,
): List<CategoryGridEntry> {
    if (categories.isEmpty()) return emptyList()
    val thresholds = calculateProgressiveAdPositions(
        totalItems = categories.size,
        initialGap = initialGap,
        gapStep = gapStep,
    ).toSet()
    if (thresholds.isEmpty()) {
        return categories.mapIndexed { index, category ->
            CategoryGridEntry.CategoryItem(category = category, originalIndex = index)
        }
    }

    val entries = ArrayList<CategoryGridEntry>(categories.size + thresholds.size)
    categories.forEachIndexed { index, category ->
        entries += CategoryGridEntry.CategoryItem(category = category, originalIndex = index)
        val countSoFar = index + 1
        if (countSoFar in thresholds) {
            entries += CategoryGridEntry.NativeAdSlot(
                slotKey = "anime200_ad_$countSoFar",
                afterItemCount = countSoFar,
            )
        }
    }
    return entries
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Anchored Adaptive Banner Ad that scales dynamically to available screen width.
 * Placed inside dedicated layout space (e.g. Scaffold bottomBar) with divider + safe margin
 * so it NEVER overlaps navigation bars, buttons, or wallpaper grid content.
 */
@Composable
fun AdaptiveBannerAd(
    adUnitId: String = AdMobConstants.TEST_ADAPTIVE_BANNER_UNIT_ID,
    topSafeMargin: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isPreview = LocalInspectionMode.current
    val density = LocalDensity.current

    var isAdFailed by remember(adUnitId) { mutableStateOf(false) }
    var isAdLoaded by remember(adUnitId) { mutableStateOf(false) }

    if (isAdFailed) return

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(ObsidianVoid)
            .testTag("adaptive_banner_container"),
        contentAlignment = Alignment.Center,
    ) {
        val adWidthDp = maxWidth.value.toInt().coerceAtLeast(320)
        val adSize = remember(adWidthDp) {
            AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, adWidthDp)
        }
        val bannerHeightDp = remember(adSize, density) {
            val px = runCatching { adSize.getHeightInPixels(context) }.getOrDefault(0)
            if (px > 0) with(density) { px.toDp() } else adSize.height.coerceAtLeast(50).dp
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            if (topSafeMargin > 0.dp) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topSafeMargin)
                        .background(ObsidianVoid),
                )
            }
            HorizontalDivider(thickness = 1.dp, color = HairlineBorder)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bannerHeightDp)
                    .background(Color(0xFF100E18)),
                contentAlignment = Alignment.Center,
            ) {
                if (isPreview) {
                    Text(
                        text = "Ad • Adaptive Banner (${adSize.width}x${adSize.height})",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkMuted,
                    )
                } else {
                    val adView = remember(adUnitId, adWidthDp) {
                        AdView(context).apply {
                            this.adUnitId = adUnitId
                            setAdSize(adSize)
                            adListener = object : AdListener() {
                                override fun onAdLoaded() {
                                    isAdLoaded = true
                                    isAdFailed = false
                                }

                                override fun onAdFailedToLoad(error: LoadAdError) {
                                    isAdLoaded = false
                                    isAdFailed = true
                                }
                            }
                            loadAd(AdRequest.Builder().build())
                        }
                    }

                    DisposableEffect(lifecycleOwner, adView) {
                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_RESUME -> adView.resume()
                                Lifecycle.Event.ON_PAUSE -> adView.pause()
                                else -> Unit
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                            adView.destroy()
                        }
                    }

                    if (!isAdLoaded) {
                        Text(
                            text = "Sponsored",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = InkMuted.copy(alpha = 0.6f),
                        )
                    }

                    AndroidView(
                        factory = { adView },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(bannerHeightDp),
                    )
                }
            }
        }
    }
}

private data class ManagedNativeAdState(
    val nativeAd: NativeAd? = null,
    val isLoading: Boolean = true,
    val isFailed: Boolean = false,
)

private class ManagedNativeAdHolder(
    initialAd: NativeAd?,
    isPreview: Boolean,
) : RememberObserver {
    var heldAd: NativeAd? = initialAd
        private set

    var state by mutableStateOf(
        ManagedNativeAdState(
            nativeAd = initialAd,
            isLoading = !isPreview && initialAd == null,
            isFailed = false,
        ),
    )
        private set

    fun replaceAd(newAd: NativeAd) {
        if (heldAd === newAd) return
        heldAd?.destroy()
        heldAd = newAd
        state = ManagedNativeAdState(
            nativeAd = newAd,
            isLoading = false,
            isFailed = false,
        )
    }

    fun markFailed() {
        if (heldAd == null) {
            state = ManagedNativeAdState(
                nativeAd = null,
                isLoading = false,
                isFailed = true,
            )
        }
    }

    fun release() {
        val toDestroy = heldAd
        heldAd = null
        toDestroy?.destroy()
    }

    override fun onRemembered() = Unit

    override fun onForgotten() {
        release()
    }

    override fun onAbandoned() {
        release()
    }
}

private fun loadSingleNativeAdDirect(
    context: Context,
    adUnitId: String,
    onLoaded: (NativeAd) -> Unit,
    onFailed: () -> Unit,
) {
    val videoOptions = VideoOptions.Builder()
        .setStartMuted(true)
        .build()
    val adOptions = NativeAdOptions.Builder()
        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
        .setVideoOptions(videoOptions)
        .build()

    runCatching {
        val adLoader = AdLoader.Builder(context, adUnitId)
            .forNativeAd { loadedAd -> onLoaded(loadedAd) }
            .withAdListener(
                object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        onFailed()
                    }
                },
            )
            .withNativeAdOptions(adOptions)
            .build()

        adLoader.loadAd(AdRequest.Builder().build())
    }.onFailure {
        onFailed()
    }
}

/**
 * Memory-safe NativeAd loader backed by [NativeAdManager]'s preload queue:
 * - Synchronously pulls a preloaded [NativeAd] from the buffer on first composition when ready
 *   (zero layout shift / blank flash) and triggers immediate background refill
 * - Falls back to queuing a waiter on [NativeAdManager] if the buffer was momentarily empty
 * - Destroys active [NativeAd] strictly via [DisposableEffect] (and [RememberObserver]) whenever
 *   the item scrolls off-screen or the parent screen leaves composition
 */
@Composable
private fun rememberManagedNativeAd(
    adUnitId: String,
    slotKey: String,
    adManager: NativeAdManager?,
): ManagedNativeAdState {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current

    val holder = remember(adUnitId, slotKey, adManager, isPreview) {
        val preloaded = if (!isPreview && adManager != null && adUnitId == adManager.adUnitId) {
            adManager.popAd()
        } else {
            null
        }
        ManagedNativeAdHolder(
            initialAd = preloaded,
            isPreview = isPreview,
        )
    }

    if (isPreview) return holder.state

    DisposableEffect(adUnitId, slotKey, adManager, holder) {
        var disposed = false
        var cancelPendingRequest: (() -> Unit)? = null

        if (holder.heldAd == null) {
            if (adManager != null && adUnitId == adManager.adUnitId) {
                cancelPendingRequest = adManager.requestAd(
                    onLoaded = { loadedAd ->
                        if (disposed) {
                            loadedAd.destroy()
                        } else {
                            holder.replaceAd(loadedAd)
                        }
                    },
                    onFailed = {
                        if (!disposed) {
                            holder.markFailed()
                        }
                    },
                )
            } else {
                loadSingleNativeAdDirect(
                    context = context,
                    adUnitId = adUnitId,
                    onLoaded = { loadedAd ->
                        if (disposed) {
                            loadedAd.destroy()
                        } else {
                            holder.replaceAd(loadedAd)
                        }
                    },
                    onFailed = {
                        if (!disposed) {
                            holder.markFailed()
                        }
                    },
                )
            }
        }

        onDispose {
            disposed = true
            cancelPendingRequest?.invoke()
            cancelPendingRequest = null
            holder.release()
        }
    }

    return holder.state
}

/**
 * Policy-compliant Reusable Compose NativeAdView Wrapper:
 * - Pulls preloaded ads instantaneously from [NativeAdManager]'s buffer queue
 * - Distinct background (#1F1A2E) and ElectricViolet border separating ad from wallpaper cards
 * - High-contrast "Ad" label (>= 10sp) + Google AdChoicesView in the top-right corner
 * - Full memory-safe NativeAd.destroy() lifecycle management when scrolled off-screen
 */
@Composable
fun NativeAdCard(
    style: NativeAdStyle,
    slotKey: String,
    modifier: Modifier = Modifier,
    adUnitId: String = AdMobConstants.TEST_NATIVE_ADVANCED_UNIT_ID,
    nativeAdManager: NativeAdManager? = null,
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val resolvedManager = remember(context, nativeAdManager) {
        nativeAdManager ?: (context.applicationContext as? KuroWallApplication)?.container?.nativeAdManager
    }
    val adState = rememberManagedNativeAd(
        adUnitId = adUnitId,
        slotKey = slotKey,
        adManager = resolvedManager,
    )

    if (adState.isFailed && style != NativeAdStyle.FULL_PAGER) {
        return
    }

    val distinctContainerColor = Color(0xFF1F1A2E)
    val distinctBorderColor = ElectricViolet.copy(alpha = 0.58f)
    val cornerRadius = when (style) {
        NativeAdStyle.GRID_CARD -> 18.dp
        NativeAdStyle.FULL_PAGER -> 24.dp
        NativeAdStyle.DETAIL_BOTTOM -> 18.dp
    }
    val shape = RoundedCornerShape(cornerRadius)

    val containerHeightModifier = when (style) {
        NativeAdStyle.GRID_CARD -> Modifier.fillMaxWidth().heightIn(min = 216.dp)
        NativeAdStyle.FULL_PAGER -> Modifier.fillMaxSize()
        NativeAdStyle.DETAIL_BOTTOM -> Modifier.fillMaxWidth().wrapContentHeight()
    }

    Surface(
        shape = shape,
        color = distinctContainerColor,
        border = BorderStroke(1.5.dp, distinctBorderColor),
        shadowElevation = 6.dp,
        modifier = modifier
            .then(containerHeightModifier)
            .clip(shape)
            .testTag("native_ad_card_${style.name.lowercase()}_$slotKey"),
    ) {
        when {
            isPreview -> {
                NativeAdPreviewPlaceholder(style = style)
            }

            adState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            when (style) {
                                NativeAdStyle.GRID_CARD -> 216.dp
                                NativeAdStyle.FULL_PAGER -> 420.dp
                                NativeAdStyle.DETAIL_BOTTOM -> 116.dp
                            },
                        )
                        .padding(12.dp),
                ) {
                    ShimmerBox(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp)),
                    )
                    AdBadgeChip(modifier = Modifier.align(Alignment.TopStart))
                }
            }

            adState.isFailed -> {
                // Only reached for FULL_PAGER when offline so user doesn't see a blank page
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AdBadgeChip(modifier = Modifier.align(Alignment.TopStart))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(id = R.string.ad_sponsored_label),
                            style = MaterialTheme.typography.titleMedium,
                            color = InkWhite,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Swipe left or right to continue exploring wallpapers",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkMuted,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            adState.nativeAd != null -> {
                val ad = adState.nativeAd
                val adLabelText = stringResource(id = R.string.ad_badge_label)
                val ctaFallbackText = stringResource(id = R.string.ad_cta_fallback)
                AndroidView(
                    factory = { ctx ->
                        buildNativeAdView(
                            context = ctx,
                            style = style,
                            adBadgeText = adLabelText,
                            ctaFallback = ctaFallbackText,
                        )
                    },
                    update = { view ->
                        bindNativeAd(view, ad, ctaFallbackText)
                    },
                    onRelease = { view ->
                        view.destroy()
                    },
                    modifier = if (style == NativeAdStyle.DETAIL_BOTTOM) {
                        Modifier.fillMaxWidth().wrapContentHeight()
                    } else {
                        Modifier.fillMaxSize()
                    },
                )
            }
        }
    }
}

@Composable
private fun AdBadgeChip(modifier: Modifier = Modifier) {
    Surface(
        color = Color(0xFFFFC107),
        shape = RoundedCornerShape(6.dp),
        modifier = modifier,
    ) {
        Text(
            text = stringResource(id = R.string.ad_badge_label),
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF14121D),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun NativeAdPreviewPlaceholder(style: NativeAdStyle) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AdBadgeChip()
            Text(
                text = "AdChoices ⓘ",
                fontSize = 11.sp,
                color = CyberCyan,
            )
        }
        Text(
            text = "Native Ad (${style.name})",
            style = MaterialTheme.typography.titleMedium,
            color = InkWhite,
        )
        Text(
            text = "High-contrast Ad label & AdChoices compliant card",
            style = MaterialTheme.typography.bodyMedium,
            color = InkMuted,
        )
    }
}

private const val TAG_HEADLINE = 1001
private const val TAG_BODY = 1002
private const val TAG_ICON = 1003
private const val TAG_MEDIA = 1004
private const val TAG_CTA = 1005
private const val TAG_AD_CHOICES = 1006

private fun Int.dpToPx(context: Context): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        this.toFloat(),
        context.resources.displayMetrics,
    ).toInt()

private fun buildNativeAdView(
    context: Context,
    style: NativeAdStyle,
    adBadgeText: String,
    ctaFallback: String,
): NativeAdView {
    val nativeAdView = NativeAdView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (style == NativeAdStyle.FULL_PAGER) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            },
        )
    }

    val badgeView = TextView(context).apply {
        text = adBadgeText
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f) // Strictly >= 10sp
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextColor(AndroidColor.parseColor("#14121D"))
        setPadding(6.dpToPx(context), 2.dpToPx(context), 6.dpToPx(context), 2.dpToPx(context))
        background = GradientDrawable().apply {
            setColor(AndroidColor.parseColor("#FFC107"))
            cornerRadius = 6.dpToPx(context).toFloat()
        }
    }

    val adChoicesView = AdChoicesView(context).apply {
        tag = TAG_AD_CHOICES
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    val iconView = ImageView(context).apply {
        tag = TAG_ICON
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply {
            setColor(AndroidColor.parseColor("#2B253C"))
            cornerRadius = 10.dpToPx(context).toFloat()
        }
        clipToOutline = true
    }

    // AdMob Native Policy: Do NOT truncate headline (>= 25 chars) or body (>= 90 chars)
    val headlineView = TextView(context).apply {
        tag = TAG_HEADLINE
        setTextColor(AndroidColor.parseColor("#F5F4FA"))
        setTypeface(Typeface.DEFAULT_BOLD)
        maxLines = 3
        ellipsize = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, if (style == NativeAdStyle.DETAIL_BOTTOM) 14f else 15f)
    }

    val bodyView = TextView(context).apply {
        tag = TAG_BODY
        setTextColor(AndroidColor.parseColor("#B6B1C9"))
        maxLines = 5
        ellipsize = null
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    val ctaButton = TextView(context).apply {
        tag = TAG_CTA
        text = ctaFallback
        setTextColor(AndroidColor.WHITE)
        setTypeface(Typeface.DEFAULT_BOLD)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        isAllCaps = false
        setPadding(12.dpToPx(context), 10.dpToPx(context), 12.dpToPx(context), 10.dpToPx(context))
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                AndroidColor.parseColor("#7C4DFF"),
                AndroidColor.parseColor("#00B8D4"),
            ),
        ).apply {
            cornerRadius = 14.dpToPx(context).toFloat()
        }
    }

    val mediaView = MediaView(context).apply {
        tag = TAG_MEDIA
    }

    when (style) {
        NativeAdStyle.GRID_CARD -> {
            val rootRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = 216.dpToPx(context)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                )
            }

            // Left half: MediaView (>= 120dp x 120dp) with "Ad" badge top-left
            val leftMediaFrame = FrameLayout(context).apply {
                minimumHeight = 216.dpToPx(context)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.95f)
                setBackgroundColor(AndroidColor.parseColor("#13101D"))
            }
            mediaView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            leftMediaFrame.addView(mediaView)
            leftMediaFrame.addView(
                badgeView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    setMargins(8.dpToPx(context), 8.dpToPx(context), 0, 0)
                },
            )
            rootRow.addView(leftMediaFrame)

            // Right half: AdChoices top-right + Icon + un-truncated Headline + un-truncated Body + Install CTA
            val rightCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.05f)
                setPadding(12.dpToPx(context), 8.dpToPx(context), 12.dpToPx(context), 10.dpToPx(context))
            }

            val topRightRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            topRightRow.addView(adChoicesView)
            rightCol.addView(topRightRow)

            iconView.layoutParams = LinearLayout.LayoutParams(38.dpToPx(context), 38.dpToPx(context)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 6.dpToPx(context)
            }
            rightCol.addView(iconView)

            headlineView.gravity = Gravity.CENTER_HORIZONTAL
            headlineView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            rightCol.addView(headlineView)

            bodyView.gravity = Gravity.CENTER_HORIZONTAL
            bodyView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 4.dpToPx(context)
            }
            rightCol.addView(bodyView)

            ctaButton.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                40.dpToPx(context),
            ).apply {
                topMargin = 10.dpToPx(context)
            }
            rightCol.addView(ctaButton)

            rootRow.addView(rightCol)
            nativeAdView.addView(rootRow)
        }

        NativeAdStyle.FULL_PAGER -> {
            val rootCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }

            // Top bar inside card: Ad badge left, AdChoices right
            val topHeader = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setPadding(12.dpToPx(context), 10.dpToPx(context), 12.dpToPx(context), 8.dpToPx(context))
            }
            topHeader.addView(badgeView)
            topHeader.addView(
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                },
            )
            topHeader.addView(adChoicesView)
            rootCol.addView(topHeader)

            // Center MediaView taking remaining height
            mediaView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            rootCol.addView(mediaView)

            // Bottom details + CTA button
            val bottomPanel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                setBackgroundColor(AndroidColor.parseColor("#181424"))
                setPadding(14.dpToPx(context), 12.dpToPx(context), 14.dpToPx(context), 14.dpToPx(context))
            }

            val metaRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            iconView.layoutParams = LinearLayout.LayoutParams(48.dpToPx(context), 48.dpToPx(context)).apply {
                marginEnd = 12.dpToPx(context)
            }
            metaRow.addView(iconView)

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(headlineView)
            bodyView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 3.dpToPx(context)
            }
            textCol.addView(bodyView)
            metaRow.addView(textCol)
            bottomPanel.addView(metaRow)

            ctaButton.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dpToPx(context),
            ).apply {
                topMargin = 12.dpToPx(context)
            }
            bottomPanel.addView(ctaButton)
            rootCol.addView(bottomPanel)

            nativeAdView.addView(rootCol)
        }

        NativeAdStyle.DETAIL_BOTTOM -> {
            val rootCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                )
                setPadding(12.dpToPx(context), 8.dpToPx(context), 12.dpToPx(context), 10.dpToPx(context))
            }

            // Top micro-row: "Ad" badge left, AdChoices right
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            topRow.addView(badgeView)
            topRow.addView(
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                },
            )
            topRow.addView(adChoicesView)
            rootCol.addView(topRow)

            // Middle row: App icon + Headline + 1-line body
            val infoRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = 6.dpToPx(context)
                }
            }
            iconView.layoutParams = LinearLayout.LayoutParams(38.dpToPx(context), 38.dpToPx(context)).apply {
                marginEnd = 10.dpToPx(context)
            }
            infoRow.addView(iconView)

            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(headlineView)
            textCol.addView(bodyView)
            infoRow.addView(textCol)
            rootCol.addView(infoRow)

            // Full-width CTA button
            ctaButton.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                42.dpToPx(context),
            ).apply {
                topMargin = 8.dpToPx(context)
            }
            rootCol.addView(ctaButton)

            nativeAdView.addView(rootCol)
        }
    }

    nativeAdView.adChoicesView = adChoicesView
    nativeAdView.headlineView = headlineView
    nativeAdView.bodyView = bodyView
    nativeAdView.iconView = iconView
    nativeAdView.callToActionView = ctaButton
    if (style != NativeAdStyle.DETAIL_BOTTOM) {
        nativeAdView.mediaView = mediaView
    }

    return nativeAdView
}

private fun bindNativeAd(
    nativeAdView: NativeAdView,
    nativeAd: NativeAd,
    ctaFallback: String,
) {
    val headlineView = nativeAdView.findViewWithTag<TextView>(TAG_HEADLINE)
    val bodyView = nativeAdView.findViewWithTag<TextView>(TAG_BODY)
    val iconView = nativeAdView.findViewWithTag<ImageView>(TAG_ICON)
    val ctaView = nativeAdView.findViewWithTag<TextView>(TAG_CTA)
    val mediaView = nativeAdView.findViewWithTag<MediaView>(TAG_MEDIA)

    headlineView?.text = nativeAd.headline.orEmpty()

    if (nativeAd.body.isNullOrBlank()) {
        bodyView?.visibility = View.GONE
    } else {
        bodyView?.visibility = View.VISIBLE
        bodyView?.text = nativeAd.body
    }

    val iconDrawable = nativeAd.icon?.drawable
    if (iconDrawable == null) {
        iconView?.visibility = View.GONE
    } else {
        iconView?.visibility = View.VISIBLE
        iconView?.setImageDrawable(iconDrawable)
    }

    if (nativeAd.callToAction.isNullOrBlank()) {
        ctaView?.text = ctaFallback
    } else {
        ctaView?.text = nativeAd.callToAction
    }

    if (mediaView != null && nativeAdView.mediaView != null) {
        mediaView.mediaContent = nativeAd.mediaContent
    }

    nativeAdView.setNativeAd(nativeAd)
}
