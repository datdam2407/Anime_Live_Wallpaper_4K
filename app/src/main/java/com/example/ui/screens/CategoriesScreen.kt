package com.example.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.KuroWallApplication
import com.kuroanimelivewallpapers.app.R
import com.example.ads.CategoryGridEntry
import com.example.ads.NativeAdCard
import com.example.ads.NativeAdManager
import com.example.ads.NativeAdStyle
import com.example.ads.buildCategoriesWithProgressiveAds
import com.example.domain.model.AnimeCategory
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.HairlineBorder
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianVoid

/**
 * Displays all 200 curated anime series with zero automatic network API calls during scroll
 * to protect the Wallhaven 45 req/min rate limit. API search executes only when user taps a series.
 */
@Composable
fun CategoriesScreen(
    categories: List<AnimeCategory>,
    filterText: String,
    onFilterChange: (String) -> Unit,
    onCategoryClick: (AnimeCategory) -> Unit,
    modifier: Modifier = Modifier,
    nativeAdManager: NativeAdManager? = null,
) {
    val context = LocalContext.current
    val resolvedAdManager = remember(context, nativeAdManager) {
        nativeAdManager ?: (context.applicationContext as? KuroWallApplication)?.container?.nativeAdManager
    }
    val gridEntries = remember(categories) {
        buildCategoriesWithProgressiveAds(categories)
    }
    // Pre-blur the shared cover bitmap once off the main thread instead of running
    // Modifier.blur() GPU passes on 200 grid cards during scroll.
    val blurredCover by produceState<ImageBitmap?>(initialValue = null, key1 = context) {
        value = withContext(Dispatchers.Default) {
            createBlurredCategoryCover(context)
        }
    }

    LaunchedEffect(resolvedAdManager) {
        resolvedAdManager?.preloadBuffer()
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
                            modifier = Modifier.size(32.dp),
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
                columns = GridCells.Fixed(columnCount),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
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
                            AnimeCategoryCard(
                                category = entry.category,
                                blurredCover = blurredCover,
                                onClick = { onCategoryClick(entry.category) },
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
            }
        }
    }
}

@Composable
private fun AnimeCategoryCard(
    category: AnimeCategory,
    blurredCover: ImageBitmap?,
    onClick: () -> Unit,
) {
    val accentColor = Color(category.accentHex)
    val shape = RoundedCornerShape(20.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(shape)
            .background(ObsidianSurfaceElevated)
            .border(1.dp, accentColor.copy(alpha = 0.38f), shape)
            .clickable(onClick = onClick)
            .testTag("category_card_${category.id}"),
        contentAlignment = Alignment.Center,
    ) {
        // Shared pre-blurred cover bitmap — zero GPU blur passes and zero API calls on scroll
        if (blurredCover != null) {
            Image(
                bitmap = blurredCover,
                contentDescription = "${category.title} stylized cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            ObsidianVoid.copy(alpha = 0.78f),
                            accentColor.copy(alpha = 0.28f),
                            ObsidianVoid.copy(alpha = 0.84f),
                        ),
                    ),
                ),
        )

        Text(
            text = category.title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = InkWhite,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
        )
    }
}

private fun createBlurredCategoryCover(context: Context): ImageBitmap? {
    val options = BitmapFactory.Options().apply {
        inSampleSize = 4
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeResource(
        context.resources,
        R.drawable.img_hero_anime_night,
        options,
    ) ?: return null

    val targetW = 128
    val targetH = 80
    val scaled = if (decoded.width != targetW || decoded.height != targetH) {
        Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
            if (it !== decoded) decoded.recycle()
        }
    } else {
        decoded
    }

    val blurred = fastBoxBlur(scaled, radius = 4, passes = 2)
    if (blurred !== scaled) scaled.recycle()
    return blurred.asImageBitmap()
}

private fun fastBoxBlur(source: Bitmap, radius: Int, passes: Int): Bitmap {
    val w = source.width
    val h = source.height
    if (w <= 0 || h <= 0 || radius <= 0) return source

    val total = w * h
    var src = IntArray(total)
    var dst = IntArray(total)
    source.getPixels(src, 0, w, 0, 0, w, h)

    val windowSize = radius * 2 + 1
    repeat(passes) {
        // Horizontal pass
        for (y in 0 until h) {
            val rowOffset = y * w
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (dx in -radius..radius) {
                val px = src[rowOffset + dx.coerceIn(0, w - 1)]
                aSum += (px ushr 24) and 0xFF
                rSum += (px ushr 16) and 0xFF
                gSum += (px ushr 8) and 0xFF
                bSum += px and 0xFF
            }
            for (x in 0 until w) {
                dst[rowOffset + x] =
                    ((aSum / windowSize) shl 24) or
                            ((rSum / windowSize) shl 16) or
                            ((gSum / windowSize) shl 8) or
                            (bSum / windowSize)

                val outPx = src[rowOffset + (x - radius).coerceIn(0, w - 1)]
                val inPx = src[rowOffset + (x + radius + 1).coerceIn(0, w - 1)]
                aSum += ((inPx ushr 24) and 0xFF) - ((outPx ushr 24) and 0xFF)
                rSum += ((inPx ushr 16) and 0xFF) - ((outPx ushr 16) and 0xFF)
                gSum += ((inPx ushr 8) and 0xFF) - ((outPx ushr 8) and 0xFF)
                bSum += (inPx and 0xFF) - (outPx and 0xFF)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            var aSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0
            for (dy in -radius..radius) {
                val px = dst[dy.coerceIn(0, h - 1) * w + x]
                aSum += (px ushr 24) and 0xFF
                rSum += (px ushr 16) and 0xFF
                gSum += (px ushr 8) and 0xFF
                bSum += px and 0xFF
            }
            for (y in 0 until h) {
                src[y * w + x] =
                    ((aSum / windowSize) shl 24) or
                            ((rSum / windowSize) shl 16) or
                            ((gSum / windowSize) shl 8) or
                            (bSum / windowSize)

                val outPx = dst[(y - radius).coerceIn(0, h - 1) * w + x]
                val inPx = dst[(y + radius + 1).coerceIn(0, h - 1) * w + x]
                aSum += ((inPx ushr 24) and 0xFF) - ((outPx ushr 24) and 0xFF)
                rSum += ((inPx ushr 16) and 0xFF) - ((outPx ushr 16) and 0xFF)
                gSum += ((inPx ushr 8) and 0xFF) - ((outPx ushr 8) and 0xFF)
                bSum += (inPx and 0xFF) - (outPx and 0xFF)
            }
        }
    }

    return Bitmap.createBitmap(src, w, h, Bitmap.Config.ARGB_8888)
}
