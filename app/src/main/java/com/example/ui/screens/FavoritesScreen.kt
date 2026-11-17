package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.domain.model.AppWallpaper
import com.example.ui.components.WallpaperStaggeredCard
import com.example.ui.theme.CrimsonBlade
import com.example.ui.theme.InkMuted
import com.example.ui.theme.InkWhite
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianVoid

private enum class FavoriteTab(val label: String) {
    ALL("All"),
    STATIC("4K Artworks"),
    LIVE("Live wallpapers"),
}

@Composable
fun FavoritesScreen(
    favorites: List<AppWallpaper>,
    onOpenDetail: (Int) -> Unit,
    onToggleFavorite: (AppWallpaper) -> Unit,
    onExploreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTab by remember { mutableStateOf(FavoriteTab.ALL) }
    val filtered = remember(favorites, selectedTab) {
        when (selectedTab) {
            FavoriteTab.ALL -> favorites
            FavoriteTab.STATIC -> favorites.filter { !it.isLive }
            FavoriteTab.LIVE -> favorites.filter { it.isLive }
        }
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
                        text = "Saved Vault",
                        style = MaterialTheme.typography.headlineLarge,
                        color = InkWhite,
                    )
                    Surface(
                        color = CrimsonBlade.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = "${favorites.size} ITEMS",
                            style = MaterialTheme.typography.labelMedium,
                            color = CrimsonBlade,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FavoriteTab.entries.forEach { tab ->
                        FilterChip(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            label = { Text(tab.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CrimsonBlade,
                                selectedLabelColor = InkWhite,
                                containerColor = ObsidianSurfaceElevated,
                                labelColor = InkMuted,
                            ),
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                        .testTag("favorites_empty_state"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        tint = CrimsonBlade,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Your anime vault is empty",
                        style = MaterialTheme.typography.headlineMedium,
                        color = InkWhite,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap the heart icon on any 4K artwork or live MP4 loop to keep it ready offline.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkMuted,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = onExploreClick,
                        colors = ButtonDefaults.buttonColors(containerColor = CrimsonBlade),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text("Discover Wallpapers", color = InkWhite)
                    }
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(columnCount),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalItemSpacing = 12.dp,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("favorites_grid"),
                ) {
                    itemsIndexed(
                        items = filtered,
                        key = { _, item -> item.id },
                    ) { _, wallpaper ->
                        val originalIndex = favorites.indexOfFirst { it.id == wallpaper.id }
                            .coerceAtLeast(0)
                        WallpaperStaggeredCard(
                            wallpaper = wallpaper,
                            staggeredRatio = wallpaper.aspectRatio,
                            onClick = { onOpenDetail(originalIndex) },
                            onToggleFavorite = { onToggleFavorite(wallpaper) },
                        )
                    }
                }
            }
        }
    }
}
