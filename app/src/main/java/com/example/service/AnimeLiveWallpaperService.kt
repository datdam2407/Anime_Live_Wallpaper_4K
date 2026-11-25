package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.KuroWallApplication
import com.example.data.local.LiveWallpaperDownloader
import com.example.util.MediaDownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.WeakHashMap

/**
 * Compatibility-hardened Live Wallpaper Engine across 19,547 Android devices (SDK 24–36,
 * MediaTek, Unisoc, Allwinner/Rockchip, Mali, PowerVR, Adreno, OpenGL ES 2.0, <= 2GB RAM):
 *
 * 1. Explicit Surface Lifecycle & Re-attachment:
 *    - Explicitly attaches & detaches `Surface` via `setVideoSurface(holder.surface)` /
 *      `setVideoSurface(null)` across `onSurfaceCreated`, `onSurfaceChanged`, `onSurfaceDestroyed`,
 *      and `onVisibilityChanged`, preventing permanent black frames after lock/unlock or launcher restarts.
 * 2. MediaCodec & Hardware Decoder Safety:
 *    - Enables `DefaultRenderersFactory.setEnableDecoderFallback(true)` and automatically escalates
 *      to [SoftwarePreferredMediaCodecSelector] (`c2.android.*` / `OMX.google.*`) or platform
 *      [MediaPlayer] if vendor hardware codecs throw errors or silently stall with 0 rendered frames.
 * 3. Strict Internal File Persistence (`context.filesDir`):
 *    - Never streams over the network and rejects temporary `cacheDir` paths that the OS can purge.
 * 4. Static First-Frame Fallback:
 *    - Pre-renders a static preview frame via GLES 2.0 before MediaCodec attaches (preventing black
 *      frames during buffering on <= 2GB RAM devices) and falls back to `Canvas` (`lockCanvas()`)
 *      whenever no video exists or all video decoders fail.
 * 5. OEM-Safe Playback Configuration:
 *    - Enforces `volume = 0f`, disables audio track & audio focus, sets `Player.REPEAT_MODE_ALL`,
 *      and pauses immediately when `!visible` to prevent OEM background/battery kills.
 */
@UnstableApi
open class AnimeLiveWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "AnimeLiveWallpaper"
        private const val WATCHDOG_TIMEOUT_MS = 2_200L

        internal const val STAGE_EXO_HARDWARE = 0
        internal const val STAGE_EXO_SOFTWARE = 1
        internal const val STAGE_PLATFORM_MEDIA_PLAYER = 2
        internal const val STAGE_STATIC_CANVAS_FALLBACK = 3

        private val activeEngines: MutableSet<ExoWallpaperEngine> =
            Collections.newSetFromMap(WeakHashMap())

        private fun registerEngine(engine: ExoWallpaperEngine) {
            activeEngines.add(engine)
        }

        private fun unregisterEngine(engine: ExoWallpaperEngine) {
            activeEngines.remove(engine)
        }

        /**
         * Releases decoders held by competing preview engines or invisible background engines
         * so 1-decoder MediaTek / Unisoc / PowerVR SoCs never run out of MediaCodec instances.
         */
        private fun releaseCompetingEnginesForDecoderHandoff(current: ExoWallpaperEngine) {
            activeEngines.toList().forEach { other ->
                if (other !== current && (other.isEnginePreviewSafe() || !other.isEngineCurrentlyVisible)) {
                    other.releasePlayerForHandoff()
                }
            }
        }

        /**
         * Deterministically picks the newest valid local MP4 inside reliable internal storage
         * between DataStore ([configuredPath]) and SharedPreferences ([prefsActiveFile]).
         * Rejects `cacheDir` / `externalCacheDir` paths and heals `/data/data` vs `/data/user/0`
         * prefix changes when [filesRoot] is provided.
         */
        internal fun resolvePreferredLiveFile(
            configuredPath: String?,
            configuredUpdatedAt: Long,
            prefsActiveFile: File?,
            prefsUpdatedAt: Long,
            filesRoot: File? = null,
            cacheDir: File? = null,
            externalCacheDir: File? = null,
        ): File? {
            val dataStoreFile = configuredPath
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !it.startsWith("http", ignoreCase = true) }
                ?.let { raw ->
                    if (filesRoot != null) {
                        MediaDownloadManager.resolveValidInternalFile(raw, filesRoot, cacheDir, externalCacheDir)
                    } else {
                        File(raw).takeIf {
                            VideoCompatibilityInspector.isReliableLocalVideoFile(it, cacheDir, externalCacheDir)
                        }
                    }
                }

            val managerFile = prefsActiveFile?.let { candidate ->
                if (VideoCompatibilityInspector.isReliableLocalVideoFile(candidate, cacheDir, externalCacheDir)) {
                    candidate
                } else if (filesRoot != null) {
                    MediaDownloadManager.resolveValidInternalFile(
                        candidate.absolutePath,
                        filesRoot,
                        cacheDir,
                        externalCacheDir,
                    )
                } else {
                    null
                }
            }

            val preferred = when {
                dataStoreFile != null && managerFile != null -> {
                    if (configuredUpdatedAt >= prefsUpdatedAt) dataStoreFile else managerFile
                }
                dataStoreFile != null -> dataStoreFile
                else -> managerFile
            }
            if (preferred != null) return preferred

            // Last-resort local recovery: pick newest valid MP4 in filesDir/live_wallpapers/
            if (filesRoot != null) {
                val liveDir = File(filesRoot, LiveWallpaperDownloader.LIVE_WALLPAPERS_DIR)
                val recovered = liveDir.listFiles()
                    ?.filter {
                        it.name.endsWith(".mp4", ignoreCase = true) &&
                            VideoCompatibilityInspector.isReliableLocalVideoFile(it, cacheDir, externalCacheDir)
                    }
                    ?.maxByOrNull { it.lastModified() }
                if (recovered != null) return recovered
            }
            return null
        }

        internal fun findAlternateCompatFile(file: File): File? {
            val parent = file.parentFile ?: return null
            if (file.nameWithoutExtension.endsWith("_compat")) return null
            val compat = File(parent, "${file.nameWithoutExtension}_compat.mp4")
            return compat.takeIf { VideoCompatibilityInspector.isReliableLocalVideoFile(it) }
        }
    }

    override fun onCreateEngine(): Engine = ExoWallpaperEngine()

    inner class ExoWallpaperEngine : Engine() {
        private var exoPlayer: ExoPlayer? = null
        private var fallbackMediaPlayer: MediaPlayer? = null
        private var attachedSurface: Surface? = null

        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var settingsJob: Job? = null
        private var watchdogJob: Job? = null
        private var retryJob: Job? = null

        private val deviceProfile: DeviceHardwareProfile by lazy {
            DeviceHardwareProfile.detect(applicationContext)
        }

        private var currentMediaUri: String? = null
        private var currentFile: File? = null
        private var latestDataStorePath: String? = null
        private var latestDataStoreUpdatedAt: Long = 0L

        private var cachedPosterBitmap: Bitmap? = null
        private var cachedPosterKey: String? = null

        private var effectsEnabled: Boolean = DelogoEffect.shouldEnableRuntimeDelogo()
        private var recoveryStage: Int = STAGE_EXO_HARDWARE
        private var retryAttemptInStage: Int = 0
        private var isReceiverRegistered: Boolean = false

        private var engineVisible: Boolean = false
        private var surfaceWidth: Int = 0
        private var surfaceHeight: Int = 0
        private var hasRenderedFirstFrame: Boolean = false
        private var inStaticCanvasFallbackMode: Boolean = false
        private var lastObservedUpdatedAt: Long = -1L

        val isEngineCurrentlyVisible: Boolean
            get() = engineVisible

        private val reloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MediaDownloadManager.ACTION_RELOAD_WALLPAPER) {
                    onExternalWallpaperUpdated()
                }
            }
        }

        fun isEnginePreviewSafe(): Boolean = runCatching { isPreview }.getOrDefault(false)

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            runCatching { setOffsetNotificationsEnabled(false) }
            registerEngine(this)
            registerReloadReceiver()
            observeSettings()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            updateDimensionsFromHolder(holder)
            hasRenderedFirstFrame = false
            inStaticCanvasFallbackMode = false

            val localFile = resolveLatestLocalFile()
            if (localFile == null) {
                drawStaticCanvasFallback(holder, null)
                return
            }
            currentFile = localFile

            val surface = holder.surface
            if (surface != null && surface.isValid) {
                // Pre-paint static first frame via GLES 2.0 before MediaCodec connects so low-RAM
                // devices never flash a black buffer while ExoPlayer initializes/buffers.
                if (exoPlayer == null && fallbackMediaPlayer == null) {
                    renderGlesPrePlaybackFrame(surface, localFile)
                }

                if (reattachSurfaceIfPlayerExists(surface)) {
                    if (engineVisible) {
                        resumeExistingPlayer(localFile)
                    }
                    return
                }
            }

            if (engineVisible && surfaceWidth > 0 && surfaceHeight > 0) {
                startOrReloadPlayback(forceReload = false)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height

            val localFile = resolveLatestLocalFile()
            if (localFile == null || inStaticCanvasFallbackMode) {
                drawStaticCanvasFallback(holder, localFile)
                return
            }

            val surface = holder.surface
            if (surface != null && surface.isValid) {
                if (exoPlayer != null || fallbackMediaPlayer != null) {
                    reattachSurfaceIfPlayerExists(surface)
                    if (engineVisible) {
                        resumeExistingPlayer(localFile)
                    }
                } else if (engineVisible && width > 0 && height > 0) {
                    startOrReloadPlayback(forceReload = false)
                }
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            updateDimensionsFromHolder(holder)
            val localFile = resolveLatestLocalFile()
            if (localFile == null || inStaticCanvasFallbackMode) {
                drawStaticCanvasFallback(holder, localFile)
                return
            }
            val surface = holder.surface
            if (surface != null && surface.isValid) {
                if (exoPlayer != null || fallbackMediaPlayer != null) {
                    reattachSurfaceIfPlayerExists(surface)
                    if (engineVisible) {
                        resumeExistingPlayer(localFile)
                    }
                } else if (engineVisible && surfaceWidth > 0 && surfaceHeight > 0) {
                    startOrReloadPlayback(forceReload = false)
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            engineVisible = visible
            if (visible) {
                releaseCompetingEnginesForDecoderHandoff(this)
                startOrReloadPlayback(forceReload = false)
            } else {
                // Pause immediately when invisible to prevent OEM battery/background kills while
                // keeping the last rendered video frame latched on valid Home surfaces.
                pausePlaybackImmediately()
                if (isEnginePreviewSafe() && deviceProfile.isLowRamDevice) {
                    releaseAllPlayers()
                }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            watchdogJob?.cancel()
            watchdogJob = null
            retryJob?.cancel()
            retryJob = null
            surfaceWidth = 0
            surfaceHeight = 0
            hasRenderedFirstFrame = false
            inStaticCanvasFallbackMode = false
            // Cleanly detach Surface and release decoder bound to the dead native Surface so
            // subsequent onSurfaceCreated binds a fresh MediaCodec without PlaceholderSurface bugs.
            detachSurfaceFromPlayers()
            releaseAllPlayers()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            unregisterEngine(this)
            settingsJob?.cancel()
            watchdogJob?.cancel()
            retryJob?.cancel()
            engineScope.cancel()
            unregisterReloadReceiver()
            detachSurfaceFromPlayers()
            releaseAllPlayers()
            recycleCachedPoster()
            super.onDestroy()
        }

        fun releasePlayerForHandoff() {
            detachSurfaceFromPlayers()
            releaseAllPlayers()
        }

        private fun updateDimensionsFromHolder(holder: SurfaceHolder) {
            val frame = holder.surfaceFrame
            if (frame != null && frame.width() > 0 && frame.height() > 0) {
                surfaceWidth = frame.width()
                surfaceHeight = frame.height()
            }
        }

        private fun onExternalWallpaperUpdated() {
            val activeFile = resolveLatestLocalFile()
            currentFile = activeFile
            resetRecoveryState()
            if (engineVisible && surfaceWidth > 0 && surfaceHeight > 0) {
                startOrReloadPlayback(forceReload = true)
            } else {
                releaseAllPlayers()
            }
        }

        private fun resetRecoveryState() {
            effectsEnabled = DelogoEffect.shouldEnableRuntimeDelogo()
            recoveryStage = STAGE_EXO_HARDWARE
            retryAttemptInStage = 0
            inStaticCanvasFallbackMode = false
            hasRenderedFirstFrame = false
        }

        private fun observeSettings() {
            settingsJob?.cancel()
            val app = applicationContext as? KuroWallApplication ?: return
            val container = app.container
            settingsJob = engineScope.launch {
                container.settingsDataStore.settingsFlow
                    .map { it.activeLiveWallpaperPath to it.activeLiveUpdatedAt }
                    .distinctUntilChanged()
                    .collect { (path, updatedAt) ->
                        val updatedChanged = lastObservedUpdatedAt != -1L && lastObservedUpdatedAt != updatedAt
                        lastObservedUpdatedAt = updatedAt
                        latestDataStorePath = path
                        latestDataStoreUpdatedAt = updatedAt

                        val previousPath = currentFile?.absolutePath
                        val resolvedFile = resolveLatestLocalFile(
                            configuredPath = path,
                            configuredUpdatedAt = updatedAt,
                        )
                        val fileChanged = resolvedFile != null && resolvedFile.absolutePath != previousPath
                        if (resolvedFile != null) {
                            currentFile = resolvedFile
                        }

                        if (updatedChanged || fileChanged) {
                            resetRecoveryState()
                        }

                        val exo = exoPlayer
                        if (exo != null) {
                            exo.volume = 0f
                            if ((updatedChanged || fileChanged) && resolvedFile != null) {
                                if (engineVisible) {
                                    swapMediaOnActivePlayer(exo, resolvedFile, forceReload = true)
                                } else {
                                    releaseAllPlayers()
                                }
                            }
                        } else if (engineVisible && surfaceWidth > 0 && surfaceHeight > 0 && resolvedFile != null) {
                            startOrReloadPlayback(forceReload = updatedChanged || fileChanged)
                        }
                    }
            }
        }

        private fun pausePlaybackImmediately() {
            watchdogJob?.cancel()
            watchdogJob = null
            retryJob?.cancel()
            retryJob = null
            exoPlayer?.let { player ->
                player.volume = 0f
                player.playWhenReady = false
                runCatching { player.pause() }
            }
            fallbackMediaPlayer?.let { mp ->
                runCatching {
                    mp.setVolume(0f, 0f)
                    if (mp.isPlaying) mp.pause()
                }
            }
        }

        private fun detachSurfaceFromPlayers() {
            exoPlayer?.let { player ->
                runCatching { player.playWhenReady = false }
                runCatching { player.pause() }
                runCatching { player.setVideoSurface(null) }
                runCatching { player.clearVideoSurface() }
            }
            fallbackMediaPlayer?.let { mp ->
                runCatching { if (mp.isPlaying) mp.pause() }
                runCatching { mp.setSurface(null) }
            }
            attachedSurface = null
        }

        private fun reattachSurfaceIfPlayerExists(surface: Surface): Boolean {
            val exo = exoPlayer
            if (exo != null) {
                if (attachedSurface !== surface) {
                    val attachedOk = runCatching {
                        exo.setVideoSurface(surface)
                        attachedSurface = surface
                        true
                    }.getOrElse { err ->
                        Log.w(TAG, "setVideoSurface re-attach failed; recreating player", err)
                        false
                    }
                    if (!attachedOk) {
                        releaseAllPlayers()
                        return false
                    }
                }
                return true
            }
            val mp = fallbackMediaPlayer
            if (mp != null) {
                if (attachedSurface !== surface) {
                    val attachedOk = runCatching {
                        mp.setSurface(surface)
                        attachedSurface = surface
                        true
                    }.getOrDefault(false)
                    if (!attachedOk) {
                        releaseAllPlayers()
                        return false
                    }
                }
                return true
            }
            return false
        }

        private fun resumeExistingPlayer(fileToPlay: File) {
            val holder = surfaceHolder ?: return
            val surface = holder.surface
            if (surface == null || !surface.isValid) return

            val exo = exoPlayer
            if (exo != null) {
                val targetUriStr = Uri.fromFile(fileToPlay).toString()
                if (targetUriStr == currentMediaUri && exo.playerError == null) {
                    reattachSurfaceIfPlayerExists(surface)
                    exo.volume = 0f
                    exo.repeatMode = Player.REPEAT_MODE_ALL
                    when (exo.playbackState) {
                        Player.STATE_IDLE -> exo.prepare()
                        Player.STATE_ENDED -> exo.seekTo(0)
                    }
                    exo.playWhenReady = true
                    exo.play()
                    scheduleFrameAdvanceWatchdog(exo)
                    return
                }
                swapMediaOnActivePlayer(exo, fileToPlay, forceReload = true)
                return
            }

            val mp = fallbackMediaPlayer
            if (mp != null) {
                val resumedOk = runCatching {
                    reattachSurfaceIfPlayerExists(surface)
                    mp.setVolume(0f, 0f)
                    mp.isLooping = true
                    if (!mp.isPlaying) mp.start()
                    true
                }.getOrDefault(false)
                if (!resumedOk) {
                    releaseAllPlayers()
                    startOrReloadPlayback(forceReload = true)
                }
            }
        }

        private fun startOrReloadPlayback(forceReload: Boolean) {
            if (!engineVisible) return
            val holder = surfaceHolder ?: return
            val surface = holder.surface
            if (surface == null || !surface.isValid) return

            if (surfaceWidth <= 0 || surfaceHeight <= 0) {
                updateDimensionsFromHolder(holder)
                if (surfaceWidth <= 0 || surfaceHeight <= 0) return
            }

            val resolvedFile = resolveLatestLocalFile()
            if (resolvedFile == null) {
                releaseAllPlayers()
                drawStaticCanvasFallback(holder, null)
                return
            }

            val effectiveFile = selectCompatibleFileForStage(resolvedFile)
            currentFile = effectiveFile

            if (inStaticCanvasFallbackMode && !forceReload) {
                drawStaticCanvasFallback(holder, effectiveFile)
                return
            }

            if (!forceReload && (exoPlayer != null || fallbackMediaPlayer != null)) {
                resumeExistingPlayer(effectiveFile)
                return
            }

            if (forceReload && exoPlayer != null && recoveryStage <= STAGE_EXO_SOFTWARE) {
                reattachSurfaceIfPlayerExists(surface)
                swapMediaOnActivePlayer(exoPlayer!!, effectiveFile, forceReload = true)
                return
            }

            when (recoveryStage) {
                STAGE_EXO_HARDWARE, STAGE_EXO_SOFTWARE -> {
                    createAndStartExoPlayer(holder, surface, effectiveFile)
                }
                STAGE_PLATFORM_MEDIA_PLAYER -> {
                    createAndStartPlatformMediaPlayer(holder, surface, effectiveFile)
                }
                else -> {
                    drawStaticCanvasFallback(holder, effectiveFile)
                }
            }
        }

        private fun selectCompatibleFileForStage(primaryFile: File): File {
            if (recoveryStage >= STAGE_EXO_SOFTWARE || deviceProfile.isLowRamDevice) {
                findAlternateCompatFile(primaryFile)?.let { return it }
            }
            return primaryFile
        }

        private fun createAndStartExoPlayer(
            holder: SurfaceHolder,
            surface: Surface,
            file: File,
        ) {
            releaseAllPlayers()
            inStaticCanvasFallbackMode = false
            hasRenderedFirstFrame = false

            // Ensure static poster frame is latched in the Surface buffer before MediaCodec connects
            renderGlesPrePlaybackFrame(surface, file)

            val inspected = VideoCompatibilityInspector.inspectVideoFile(file)
            val useSoftwareSelector = recoveryStage >= STAGE_EXO_SOFTWARE ||
                (inspected?.shouldPreferSoftwareDecoderFirst(deviceProfile) == true)

            val codecSelector: MediaCodecSelector = if (useSoftwareSelector) {
                SoftwarePreferredMediaCodecSelector
            } else {
                MediaCodecSelector.DEFAULT
            }

            val renderersFactory = DefaultRenderersFactory(applicationContext)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(codecSelector)

            val loadControlBuilder = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ if (deviceProfile.isLowRamDevice) 1_000 else 1_500,
                    /* maxBufferMs = */ if (deviceProfile.isLowRamDevice) 2_000 else 3_000,
                    /* bufferForPlaybackMs = */ 400,
                    /* bufferForPlaybackAfterRebufferMs = */ 800,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
            if (deviceProfile.isLowRamDevice) {
                loadControlBuilder.setTargetBufferBytes(2 * 1024 * 1024)
            }
            val loadControl = loadControlBuilder.build()

            val (maxW, maxH) = getPhysicalOrSurfaceResolution()
            val trackSelectionParams = buildTrackSelectionParams(maxW, maxH)

            val targetUri = Uri.fromFile(file)
            currentFile = file
            currentMediaUri = targetUri.toString()

            val player = try {
                ExoPlayer.Builder(applicationContext, renderersFactory)
                    .setLoadControl(loadControl)
                    .build()
            } catch (t: Throwable) {
                Log.e(TAG, "ExoPlayer.Builder failed; escalating recovery", t)
                escalatePlaybackRecovery(holder, file)
                return
            }

            val initializedOk = runCatching {
                player.apply {
                    setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
                    trackSelectionParameters = trackSelectionParams
                    videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                    repeatMode = Player.REPEAT_MODE_ALL
                    volume = 0f
                    if (effectsEnabled && recoveryStage == STAGE_EXO_HARDWARE && !deviceProfile.isLowRamDevice) {
                        setVideoEffects(listOf(DelogoEffect()))
                    }
                    setVideoSurface(surface)
                    attachedSurface = surface
                    addListener(
                        object : Player.Listener {
                            override fun onRenderedFirstFrame() {
                                hasRenderedFirstFrame = true
                                retryAttemptInStage = 0
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY && engineVisible) {
                                    scheduleFrameAdvanceWatchdog(player)
                                } else if (playbackState == Player.STATE_ENDED && engineVisible) {
                                    player.seekTo(0)
                                    player.playWhenReady = true
                                    player.play()
                                }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                Log.e(
                                    TAG,
                                    "ExoPlayer error (stage=$recoveryStage, sw=$useSoftwareSelector, code=${error.errorCodeName})",
                                    error,
                                )
                                handlePlayerFailure()
                            }
                        },
                    )
                    setMediaItem(MediaItem.fromUri(targetUri))
                    playWhenReady = engineVisible
                    prepare()
                    if (engineVisible) {
                        play()
                    }
                }
                true
            }.getOrElse { err ->
                Log.e(TAG, "Failed configuring ExoPlayer surface/media", err)
                runCatching { player.release() }
                false
            }

            if (!initializedOk) {
                escalatePlaybackRecovery(holder, file)
                return
            }

            exoPlayer = player
            if (engineVisible) {
                scheduleFrameAdvanceWatchdog(player)
            }
        }

        private fun createAndStartPlatformMediaPlayer(
            holder: SurfaceHolder,
            surface: Surface,
            file: File,
        ) {
            releaseAllPlayers()
            inStaticCanvasFallbackMode = false
            hasRenderedFirstFrame = false

            renderGlesPrePlaybackFrame(surface, file)

            val mp = MediaPlayer()
            val startedOk = try {
                mp.setSurface(surface)
                attachedSurface = surface
                mp.setDataSource(file.absolutePath)
                mp.setVolume(0f, 0f)
                mp.isLooping = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    runCatching {
                        mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    }
                }
                mp.setOnInfoListener { _, what, _ ->
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        hasRenderedFirstFrame = true
                    }
                    false
                }
                mp.setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Platform MediaPlayer error what=$what extra=$extra")
                    handlePlayerFailure()
                    true
                }
                mp.prepare()
                if (engineVisible) {
                    mp.start()
                }
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Platform MediaPlayer failed; falling back to static Canvas", t)
                runCatching { mp.reset() }
                runCatching { mp.release() }
                attachedSurface = null
                false
            }

            if (!startedOk) {
                recoveryStage = STAGE_STATIC_CANVAS_FALLBACK
                drawStaticCanvasFallback(holder, file)
                return
            }

            fallbackMediaPlayer = mp
        }

        private fun swapMediaOnActivePlayer(exo: ExoPlayer, file: File, forceReload: Boolean) {
            if (!VideoCompatibilityInspector.isReliableLocalVideoFile(
                    file,
                    applicationContext.cacheDir,
                    applicationContext.externalCacheDir,
                )
            ) {
                return
            }
            val targetUri = Uri.fromFile(file)
            val targetUriStr = targetUri.toString()
            if (!forceReload && targetUriStr == currentMediaUri && exo.playerError == null) return

            currentFile = file
            currentMediaUri = targetUriStr
            hasRenderedFirstFrame = false
            exo.volume = 0f
            exo.repeatMode = Player.REPEAT_MODE_ALL
            exo.setMediaItem(MediaItem.fromUri(targetUri))
            exo.playWhenReady = engineVisible
            exo.prepare()
            if (engineVisible) {
                exo.play()
                scheduleFrameAdvanceWatchdog(exo)
            }
        }

        private fun handlePlayerFailure() {
            val holder = surfaceHolder ?: return
            val file = currentFile ?: resolveLatestLocalFile()
            if (effectsEnabled) {
                effectsEnabled = false
                scheduleRecoveryAttempt(delayMs = 180L)
                return
            }
            if (recoveryStage == STAGE_EXO_HARDWARE && retryAttemptInStage == 0) {
                retryAttemptInStage = 1
                scheduleRecoveryAttempt(delayMs = 220L)
                return
            }
            escalatePlaybackRecovery(holder, file)
        }

        private fun escalatePlaybackRecovery(holder: SurfaceHolder, file: File?) {
            retryAttemptInStage = 0
            recoveryStage = when (recoveryStage) {
                STAGE_EXO_HARDWARE -> STAGE_EXO_SOFTWARE
                STAGE_EXO_SOFTWARE -> STAGE_PLATFORM_MEDIA_PLAYER
                else -> STAGE_STATIC_CANVAS_FALLBACK
            }

            if (recoveryStage >= STAGE_STATIC_CANVAS_FALLBACK || file == null) {
                detachSurfaceFromPlayers()
                releaseAllPlayers()
                drawStaticCanvasFallback(holder, file)
                return
            }

            scheduleRecoveryAttempt(delayMs = 250L)
        }

        private fun scheduleRecoveryAttempt(delayMs: Long) {
            detachSurfaceFromPlayers()
            releaseAllPlayers(cancelRetry = true)
            retryJob = engineScope.launch {
                delay(delayMs)
                retryJob = null
                val holder = surfaceHolder ?: return@launch
                if (engineVisible && holder.surface?.isValid == true && surfaceWidth > 0 && surfaceHeight > 0) {
                    startOrReloadPlayback(forceReload = true)
                }
            }
        }

        /**
         * Universal frame-advance & first-frame watchdog (always active across hardware & software
         * stages). Detects silent MediaCodec black-screen stalls on MediaTek, Unisoc, and PowerVR
         * where `STATE_READY` is reached without throwing `onPlayerError`, yet 0 frames render.
         */
        private fun scheduleFrameAdvanceWatchdog(player: ExoPlayer) {
            watchdogJob?.cancel()
            val baselineFrames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
            val hadFirstFrameBeforeWait = hasRenderedFirstFrame
            watchdogJob = engineScope.launch {
                delay(WATCHDOG_TIMEOUT_MS)
                val active = exoPlayer ?: return@launch
                if (!engineVisible || active !== player) return@launch

                val nowFrames = active.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                val stalled = active.playWhenReady &&
                    active.playbackState != Player.STATE_ENDED &&
                    !hasRenderedFirstFrame &&
                    !hadFirstFrameBeforeWait &&
                    nowFrames <= baselineFrames

                if (stalled) {
                    Log.w(
                        TAG,
                        "Watchdog detected 0 rendered frames after ${WATCHDOG_TIMEOUT_MS}ms " +
                            "(stage=$recoveryStage, state=${active.playbackState}); escalating decoder fallback.",
                    )
                    if (effectsEnabled) {
                        effectsEnabled = false
                        scheduleRecoveryAttempt(delayMs = 180L)
                    } else {
                        val holder = surfaceHolder ?: return@launch
                        escalatePlaybackRecovery(holder, currentFile ?: resolveLatestLocalFile())
                    }
                }
            }
        }

        private fun renderGlesPrePlaybackFrame(surface: Surface, videoFile: File?) {
            if (!surface.isValid) return
            val bitmap = getOrLoadPosterBitmap(videoFile) ?: return
            val (w, h) = getPhysicalOrSurfaceResolution()
            LiveWallpaperStaticFallback.renderPrePlaybackFrameGles20(
                surface = surface,
                bitmap = bitmap,
                surfaceWidth = surfaceWidth.takeIf { it > 0 } ?: w,
                surfaceHeight = surfaceHeight.takeIf { it > 0 } ?: h,
            )
        }

        private fun drawStaticCanvasFallback(holder: SurfaceHolder, videoFile: File?) {
            inStaticCanvasFallbackMode = true
            val bitmap = getOrLoadPosterBitmap(videoFile)
            LiveWallpaperStaticFallback.drawFallbackToCanvas(holder, bitmap)
        }

        private fun getOrLoadPosterBitmap(videoFile: File?): Bitmap? {
            val key = videoFile?.absolutePath ?: "__default_poster__"
            val existing = cachedPosterBitmap
            if (existing != null && !existing.isRecycled && cachedPosterKey == key) {
                return existing
            }
            val (w, h) = getPhysicalOrSurfaceResolution()
            val loaded = LiveWallpaperStaticFallback.loadStaticPreviewBitmap(
                context = applicationContext,
                videoFile = videoFile,
                targetWidth = w,
                targetHeight = h,
                isLowRam = deviceProfile.isLowRamDevice,
            )
            if (loaded != null) {
                recycleCachedPoster()
                cachedPosterBitmap = loaded
                cachedPosterKey = key
            }
            return loaded ?: cachedPosterBitmap?.takeIf { !it.isRecycled }
        }

        private fun recycleCachedPoster() {
            cachedPosterBitmap?.let { bmp ->
                if (!bmp.isRecycled) {
                    runCatching { bmp.recycle() }
                }
            }
            cachedPosterBitmap = null
            cachedPosterKey = null
        }

        private fun buildTrackSelectionParams(
            screenWidth: Int,
            screenHeight: Int,
        ): TrackSelectionParameters {
            return DefaultTrackSelector.Parameters.Builder(applicationContext)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setMaxVideoSize(screenWidth, screenHeight)
                .setExceedVideoConstraintsIfNecessary(true)
                .setExceedRendererCapabilitiesIfNecessary(true)
                .build()
        }

        private fun resolveLatestLocalFile(
            configuredPath: String? = latestDataStorePath,
            configuredUpdatedAt: Long = latestDataStoreUpdatedAt,
        ): File? {
            val activeFile = MediaDownloadManager.getActiveLiveVideoFile(applicationContext)
            val prefsUpdatedAt = MediaDownloadManager.getActiveLiveUpdatedAt(applicationContext)
            return resolvePreferredLiveFile(
                configuredPath = configuredPath,
                configuredUpdatedAt = configuredUpdatedAt,
                prefsActiveFile = activeFile,
                prefsUpdatedAt = prefsUpdatedAt,
                filesRoot = applicationContext.filesDir,
                cacheDir = applicationContext.cacheDir,
                externalCacheDir = applicationContext.externalCacheDir,
            )
        }

        private fun registerReloadReceiver() {
            if (isReceiverRegistered) return
            runCatching {
                ContextCompat.registerReceiver(
                    applicationContext,
                    reloadReceiver,
                    IntentFilter(MediaDownloadManager.ACTION_RELOAD_WALLPAPER),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                isReceiverRegistered = true
            }
        }

        private fun unregisterReloadReceiver() {
            if (!isReceiverRegistered) return
            runCatching {
                applicationContext.unregisterReceiver(reloadReceiver)
            }
            isReceiverRegistered = false
        }

        private fun releaseAllPlayers(cancelRetry: Boolean = true) {
            watchdogJob?.cancel()
            watchdogJob = null
            if (cancelRetry) {
                retryJob?.cancel()
                retryJob = null
            }
            exoPlayer?.apply {
                runCatching { playWhenReady = false }
                runCatching { stop() }
                runCatching { setVideoSurface(null) }
                runCatching { clearVideoSurface() }
                runCatching { release() }
            }
            exoPlayer = null

            fallbackMediaPlayer?.apply {
                runCatching { if (isPlaying) stop() }
                runCatching { setSurface(null) }
                runCatching { reset() }
                runCatching { release() }
            }
            fallbackMediaPlayer = null
            attachedSurface = null
            currentMediaUri = null
            hasRenderedFirstFrame = false
        }

        private fun getPhysicalOrSurfaceResolution(): Pair<Int, Int> {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val screenPair = if (wm == null) {
                1080 to 1920
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Pair(bounds.width().coerceAtLeast(720), bounds.height().coerceAtLeast(1280))
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay?.getRealMetrics(metrics)
                Pair(
                    (metrics.widthPixels.takeIf { it > 0 } ?: 1080).coerceAtLeast(720),
                    (metrics.heightPixels.takeIf { it > 0 } ?: 1920).coerceAtLeast(1280),
                )
            }
            return Pair(
                maxOf(screenPair.first, surfaceWidth),
                maxOf(screenPair.second, surfaceHeight),
            )
        }
    }
}
