package com.example.util

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.service.AnimeLiveWallpaperAlternateService
import com.example.service.AnimeLiveWallpaperService
import java.io.File

object LiveWallpaperHelper {

    fun hasSelectedLiveWallpaper(
        context: Context,
        activeLiveWallpaperPath: String?,
    ): Boolean {
        val appCtx = context.applicationContext ?: context
        val path = activeLiveWallpaperPath?.trim().orEmpty()
        if (path.isEmpty()) return false
        val resolved = MediaDownloadManager.resolveValidInternalFile(
            rawPath = path,
            filesRoot = appCtx.filesDir,
            cacheDir = appCtx.cacheDir,
            externalCacheDir = appCtx.externalCacheDir,
        )
        if (resolved != null) {
            return true
        }
        val activeSlotFile = MediaDownloadManager.getActiveLiveVideoFile(appCtx)
        return com.example.service.VideoCompatibilityInspector.isReliableLocalVideoFile(
            file = activeSlotFile,
            cacheDir = appCtx.cacheDir,
            externalCacheDir = appCtx.externalCacheDir,
        )
    }

    fun isAnimeLiveWallpaperActive(context: Context): Boolean {
        val wm = runCatching { WallpaperManager.getInstance(context) }.getOrNull() ?: return false
        val expectedPackage = context.packageName

        val homeInfo = wm.wallpaperInfo
        if (homeInfo != null &&
            isKnownAnimeService(homeInfo.packageName, homeInfo.serviceName, expectedPackage)
        ) {
            return true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val lockInfo = runCatching { wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK) }.getOrNull()
            if (lockInfo != null &&
                isKnownAnimeService(lockInfo.packageName, lockInfo.serviceName, expectedPackage)
            ) {
                return true
            }
        }

        return false
    }

    fun isLiveWallpaperOnLockScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val wm = runCatching { WallpaperManager.getInstance(context) }.getOrNull() ?: return false
        val lockInfo = runCatching { wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK) }.getOrNull()
        return lockInfo != null &&
            isKnownAnimeService(lockInfo.packageName, lockInfo.serviceName, context.packageName)
    }

    /**
     * Picks the WallpaperService component that is NOT currently active on the Home/Lock screen.
     * Because Android's system LiveWallpaperPreview suppresses the "Home screen / Home screen and
     * lock screen" destination dialog when the requested ComponentName matches the already-active
     * wallpaper service, alternating between [AnimeLiveWallpaperService] and
     * [AnimeLiveWallpaperAlternateService] ensures the system destination dialog is presented every time.
     */
    fun getNextLiveWallpaperComponent(context: Context): ComponentName {
        val wm = runCatching { WallpaperManager.getInstance(context) }.getOrNull()
        val serviceA = ComponentName(context, AnimeLiveWallpaperService::class.java)
        val serviceB = ComponentName(context, AnimeLiveWallpaperAlternateService::class.java)

        val homeInfo = wm?.wallpaperInfo
        val homeClass = if (homeInfo?.packageName == context.packageName) homeInfo.serviceName else null
        val lockInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { wm?.getWallpaperInfo(WallpaperManager.FLAG_LOCK) }.getOrNull()
        } else {
            null
        }
        val lockClass = if (lockInfo?.packageName == context.packageName) lockInfo.serviceName else null

        return chooseAlternateComponent(
            homeServiceName = homeClass,
            lockServiceName = lockClass,
            primaryComponent = serviceA,
            alternateComponent = serviceB,
        )
    }

    internal fun chooseAlternateComponent(
        homeServiceName: String?,
        lockServiceName: String?,
        primaryComponent: ComponentName,
        alternateComponent: ComponentName,
    ): ComponentName {
        val primaryName = primaryComponent.className
        val alternateName = alternateComponent.className
        return when {
            homeServiceName == primaryName -> alternateComponent
            homeServiceName == alternateName -> primaryComponent
            lockServiceName == primaryName -> alternateComponent
            else -> primaryComponent
        }
    }

    fun createChangeLiveWallpaperIntent(targetComponent: ComponentName): Intent {
        return Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, targetComponent)
        }
    }

    fun createLiveWallpaperChooserIntent(): Intent {
        return Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
    }

    fun isComponentCurrentlyActive(
        context: Context,
        component: ComponentName,
    ): Boolean {
        val wm = runCatching { WallpaperManager.getInstance(context) }.getOrNull() ?: return false
        val homeInfo = wm.wallpaperInfo
        if (homeInfo != null &&
            matchesComponent(homeInfo.packageName, homeInfo.serviceName, component.packageName, component.className)
        ) {
            return true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val lockInfo = runCatching { wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK) }.getOrNull()
            if (lockInfo != null &&
                matchesComponent(lockInfo.packageName, lockInfo.serviceName, component.packageName, component.className)
            ) {
                return true
            }
        }
        return false
    }

    fun wasLiveWallpaperApplied(
        context: Context,
        resultCode: Int,
        expectedComponent: ComponentName?,
    ): Boolean {
        if (resultCode == Activity.RESULT_OK) return true
        return expectedComponent != null && isComponentCurrentlyActive(context, expectedComponent)
    }

    internal fun isKnownAnimeService(
        infoPackage: String?,
        infoService: String?,
        expectedPackage: String,
    ): Boolean = matchesComponent(
        infoPackage,
        infoService,
        expectedPackage,
        AnimeLiveWallpaperService::class.java.name,
    ) || matchesComponent(
        infoPackage,
        infoService,
        expectedPackage,
        AnimeLiveWallpaperAlternateService::class.java.name,
    )

    internal fun matchesComponent(
        infoPackage: String?,
        infoService: String?,
        expectedPackage: String,
        expectedService: String,
    ): Boolean = infoPackage == expectedPackage && infoService == expectedService

    fun launchLiveWallpaperPicker(
        context: Context,
        preferAllChooserIfActive: Boolean = false,
    ) {
        val nextComponent = getNextLiveWallpaperComponent(context)
        val componentIntent = createChangeLiveWallpaperIntent(nextComponent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val chooserIntent = createLiveWallpaperChooserIntent().apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val primaryIntent = if (preferAllChooserIfActive && isAnimeLiveWallpaperActive(context)) {
            chooserIntent
        } else {
            componentIntent
        }
        val secondaryIntent = if (primaryIntent === chooserIntent) componentIntent else chooserIntent

        try {
            context.startActivity(primaryIntent)
        } catch (_: Exception) {
            runCatching { context.startActivity(secondaryIntent) }
        }
    }
}
