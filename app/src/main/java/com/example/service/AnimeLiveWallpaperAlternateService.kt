package com.example.service

import androidx.media3.common.util.UnstableApi

/**
 * Secondary WallpaperService alias sharing the exact same ExoPlayer engine as
 * [AnimeLiveWallpaperService]. Alternating between primary and alternate components
 * prevents Android's system LiveWallpaperPreview from skipping the screen-destination
 * prompt ("Home screen" / "Home screen and lock screen") when a live wallpaper from
 * this app is already active.
 */
@UnstableApi
class AnimeLiveWallpaperAlternateService : AnimeLiveWallpaperService()
