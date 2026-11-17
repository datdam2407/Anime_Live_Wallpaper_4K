package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.util.DisplayMetrics
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.example.KuroWallApplication
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
 * Optimized 60fps/120fps MP4 Live Wallpaper Engine backed by Media3 ExoPlayer:
 * - Waits for valid positive Surface dimensions + visibility before attaching Media3 VideoSink
 *   so DelogoEffect/GlEffect never gets initialized on a 0x0 or occluded Surface
 * - Never re-calls setVideoSurfaceHolder or mutates trackSelectionParameters inside onSurfaceChanged
 * - Releases hardware MediaCodec + EGL pipeline whenever invisible so Preview/App players never
 *   starve or reclaim Home Wallpaper decoders
 * - Includes automatic frame-advance watchdog + error fallback to direct hardware surface output
 * - Hot-swaps seamlessly via A/B alternating MP4 slots + internal broadcast + DataStore updates
 */
@UnstableApi
open class AnimeLiveWallpaperService : WallpaperService() {

    companion object {
        private const val TAG = "AnimeLiveWallpaper"
        private val activeEngines: MutableSet<ExoWallpaperEngine> =
            Collections.newSetFromMap(WeakHashMap())

        private fun registerEngine(engine: ExoWallpaperEngine) {
            activeEngines.add(engine)
        }

        private fun unregisterEngine(engine: ExoWallpaperEngine) {
            activeEngines.remove(engine)
        }

        private fun releaseCompetingPreviewEngines(current: ExoWallpaperEngine) {
            activeEngines.toList().forEach { other ->
                if (other !== current && other.isPreview) {
                    other.releasePlayerForHandoff()
                }
            }
        }
    }

    override fun onCreateEngine(): Engine = ExoWallpaperEngine()

    inner class ExoWallpaperEngine : Engine() {
        private var exoPlayer: ExoPlayer? = null
        private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var settingsJob: Job? = null
        private var watchdogJob: Job? = null
        private var retryJob: Job? = null

        private var currentMediaUri: String? = null
        private var currentFile: File? = null
        private var currentMuted: Boolean = true
        // Enables Media3 GPU DelogoEffect on physical devices and uses direct MediaCodec
        // surface output on emulators (EGL_emulation) or if VideoFrameProcessor ever errors.
        private var effectsEnabled: Boolean = DelogoEffect.isSupportedOnDevice()
        private var retriedAfterError: Boolean = false
        private var isReceiverRegistered: Boolean = false

        private var engineVisible: Boolean = false
        private var surfaceWidth: Int = 0
        private var surfaceHeight: Int = 0
        private var lastObservedUpdatedAt: Long = -1L

        private val reloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == MediaDownloadManager.ACTION_RELOAD_WALLPAPER) {
                    onExternalWallpaperUpdated()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            registerEngine(this)
            registerReloadReceiver()
            observeSettings()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            val frame = holder.surfaceFrame
            if (frame != null && frame.width() > 0 && frame.height() > 0) {
                surfaceWidth = frame.width()
                surfaceHeight = frame.height()
            }
            if (engineVisible && surfaceWidth > 0 && surfaceHeight > 0) {
                startOrReloadPlayback(forceReload = false)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            // Never re-call setVideoSurfaceHolder or mutate trackSelectionParameters here:
            // ExoPlayer's registered SurfaceHolder.Callback automatically notifies Media3 VideoSink.
            if (exoPlayer == null && engineVisible && width > 0 && height > 0) {
                startOrReloadPlayback(forceReload = false)
            }
        }

        override fun onSurfaceRedrawNeeded(holder: SurfaceHolder) {
            super.onSurfaceRedrawNeeded(holder)
            val frame = holder.surfaceFrame
            if (frame != null && frame.width() > 0 && frame.height() > 0) {
                surfaceWidth = frame.width()
                surfaceHeight = frame.height()
            }
            if (engineVisible && exoPlayer == null && surfaceWidth > 0 && surfaceHeight > 0) {
                startOrReloadPlayback(forceReload = false)
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            engineVisible = visible
            if (visible) {
                if (!isPreview) {
                    releaseCompetingPreviewEngines(this)
                }
                startOrReloadPlayback(forceReload = false)
            } else {
                // Releasing player when invisible prevents MediaCodec exhaustion & stale EGL stalls
                // when switching between Preview, DetailPreviewScreen, and Home Screen.
                releasePlayer()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            surfaceWidth = 0
            surfaceHeight = 0
            releasePlayer()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            unregisterEngine(this)
            settingsJob?.cancel()
            watchdogJob?.cancel()
            retryJob?.cancel()
            engineScope.cancel()
            unregisterReloadReceiver()
            releasePlayer()
            super.onDestroy()
        }

        fun releasePlayerForHandoff() {
            releasePlayer()
        }

        private fun onExternalWallpaperUpdated() {
            val activeFile = resolveLatestLocalFile(configuredPath = null)
            currentFile = activeFile
            effectsEnabled = DelogoEffect.isSupportedOnDevice()
            retriedAfterError = false
            if (engineVisible && surfaceWidth > 0 && surfaceHeight > 0) {
                startOrReloadPlayback(forceReload = true)
            } else {
                releasePlayer()
            }
        }

        private fun observeSettings() {
            settingsJob?.cancel()
            val container = (applicationContext as KuroWallApplication).container
            settingsJob = engineScope.launch {
                container.settingsDataStore.settingsFlow
                    .map {
                        Triple(
                            it.activeLiveWallpaperPath,
                            it.muteLiveWallpaper,
                            it.activeLiveUpdatedAt,
                        )
                    }
                    .distinctUntilChanged()
                    .collect { (path, muted, updatedAt) ->
                        val muteChanged = currentMuted != muted
                        currentMuted = muted
                        val updatedChanged = lastObservedUpdatedAt != -1L && lastObservedUpdatedAt != updatedAt
                        lastObservedUpdatedAt = updatedAt

                        val resolvedFile = resolveLatestLocalFile(path)
                        if (resolvedFile != null) {
                            currentFile = resolvedFile
                        }

                        val exo = exoPlayer
                        if (exo != null) {
                            exo.volume = if (muted) 0f else 1f
                            if (muteChanged) {
                                val (w, h) = getPhysicalOrSurfaceResolution()
                                exo.trackSelectionParameters = buildTrackSelectionParams(w, h, muted)
                            }
                            if (updatedChanged && resolvedFile != null && engineVisible) {
                                swapMediaOnActivePlayer(exo, resolvedFile, forceReload = true)
                            }
                        } else if (engineVisible && surfaceWidth > 0 && surfaceHeight > 0 && resolvedFile != null) {
                            startOrReloadPlayback(forceReload = updatedChanged)
                        }
                    }
            }
        }

        private fun startOrReloadPlayback(forceReload: Boolean) {
            if (!engineVisible) return
            val holder = surfaceHolder ?: return
            if (holder.surface?.isValid != true) return
            if (surfaceWidth <= 0 || surfaceHeight <= 0) {
                val frame = holder.surfaceFrame
                if (frame != null && frame.width() > 0 && frame.height() > 0) {
                    surfaceWidth = frame.width()
                    surfaceHeight = frame.height()
                } else {
                    return
                }
            }

            val fileToPlay = resolveLatestLocalFile(currentFile?.absolutePath) ?: return
            currentFile = fileToPlay

            val existing = exoPlayer
            if (existing != null && !forceReload) {
                val targetUriStr = Uri.fromFile(fileToPlay).toString()
                if (targetUriStr == currentMediaUri && existing.playerError == null) {
                    if (existing.playbackState == Player.STATE_IDLE) {
                        existing.prepare()
                    } else if (existing.playbackState == Player.STATE_ENDED) {
                        existing.seekTo(0)
                    }
                    existing.playWhenReady = true
                    existing.play()
                    return
                }
                swapMediaOnActivePlayer(existing, fileToPlay, forceReload = true)
                return
            }

            if (forceReload && existing != null) {
                swapMediaOnActivePlayer(existing, fileToPlay, forceReload = true)
                return
            }

            createAndStartPlayer(holder, fileToPlay)
        }

        private fun createAndStartPlayer(holder: SurfaceHolder, file: File) {
            releasePlayer()

            val (maxW, maxH) = getPhysicalOrSurfaceResolution()
            val renderersFactory = DefaultRenderersFactory(applicationContext)
                .setEnableDecoderFallback(true)

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 1500,
                    /* maxBufferMs = */ 3000,
                    /* bufferForPlaybackMs = */ 500,
                    /* bufferForPlaybackAfterRebufferMs = */ 1000,
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val trackSelectionParams = buildTrackSelectionParams(
                screenWidth = maxW,
                screenHeight = maxH,
                muted = currentMuted,
            )

            val targetUri = Uri.fromFile(file)
            currentFile = file
            currentMediaUri = targetUri.toString()

            val player = ExoPlayer.Builder(applicationContext, renderersFactory)
                .setLoadControl(loadControl)
                .build()

            player.apply {
                trackSelectionParameters = trackSelectionParams
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                repeatMode = Player.REPEAT_MODE_ONE
                volume = if (currentMuted) 0f else 1f
                if (effectsEnabled) {
                    setVideoEffects(listOf(DelogoEffect()))
                }
                // Called ONCE so ExoPlayer registers its SurfaceHolder.Callback cleanly
                setVideoSurfaceHolder(holder)
                addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_READY && engineVisible) {
                                scheduleFrameAdvanceWatchdog(player)
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            Log.e(TAG, "ExoPlayer error (effectsEnabled=$effectsEnabled)", error)
                            if (effectsEnabled) {
                                effectsEnabled = false
                                scheduleRecreatePlayerCleanly(delayMs = 200L)
                            } else if (!retriedAfterError) {
                                retriedAfterError = true
                                scheduleRecreatePlayerCleanly(delayMs = 300L)
                            }
                        }
                    },
                )
                setMediaItem(MediaItem.fromUri(targetUri))
                playWhenReady = true
                prepare()
                play()
            }

            exoPlayer = player
        }

        private fun swapMediaOnActivePlayer(exo: ExoPlayer, file: File, forceReload: Boolean) {
            if (!file.exists() || file.length() <= 0L) return
            val targetUri = Uri.fromFile(file)
            val targetUriStr = targetUri.toString()
            if (!forceReload && targetUriStr == currentMediaUri && exo.playerError == null) return

            currentFile = file
            currentMediaUri = targetUriStr
            exo.setMediaItem(MediaItem.fromUri(targetUri))
            exo.playWhenReady = engineVisible
            exo.prepare()
            if (engineVisible) {
                exo.play()
            }
        }

        private fun scheduleRecreatePlayerCleanly(delayMs: Long) {
            releasePlayer(cancelRetry = true)
            retryJob = engineScope.launch {
                delay(delayMs)
                retryJob = null
                val holder = surfaceHolder ?: return@launch
                val file = resolveLatestLocalFile(currentFile?.absolutePath) ?: return@launch
                if (engineVisible && holder.surface?.isValid == true && surfaceWidth > 0 && surfaceHeight > 0) {
                    createAndStartPlayer(holder, file)
                }
            }
        }

        private fun scheduleFrameAdvanceWatchdog(player: ExoPlayer) {
            if (!effectsEnabled) return
            watchdogJob?.cancel()
            val baselineFrames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0
            watchdogJob = engineScope.launch {
                delay(1500L)
                val active = exoPlayer ?: return@launch
                if (!engineVisible || active !== player || !effectsEnabled) return@launch
                val nowFrames = active.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                if (active.playbackState == Player.STATE_READY &&
                    active.playWhenReady &&
                    nowFrames <= baselineFrames + 1
                ) {
                    Log.w(TAG, "Frame advance stalled with DelogoEffect; falling back to direct surface")
                    effectsEnabled = false
                    scheduleRecreatePlayerCleanly(delayMs = 200L)
                }
            }
        }

        private fun buildTrackSelectionParams(
            screenWidth: Int,
            screenHeight: Int,
            muted: Boolean,
        ): TrackSelectionParameters {
            return TrackSelectionParameters.Builder(applicationContext)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, muted)
                .setMaxVideoSize(screenWidth, screenHeight)
                .build()
        }

        private fun resolveLatestLocalFile(configuredPath: String?): File? {
            val activeFile = MediaDownloadManager.getActiveLiveVideoFile(applicationContext)
            if (activeFile.exists() && activeFile.length() > 0L) {
                return activeFile
            }
            if (!configuredPath.isNullOrBlank()) {
                val configured = File(configuredPath)
                if (configured.exists() && configured.length() > 0L) {
                    return configured
                }
            }
            return null
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

        private fun releasePlayer(cancelRetry: Boolean = true) {
            watchdogJob?.cancel()
            watchdogJob = null
            if (cancelRetry) {
                retryJob?.cancel()
                retryJob = null
            }
            exoPlayer?.apply {
                runCatching { stop() }
                runCatching { clearVideoSurfaceHolder(surfaceHolder) }
                runCatching { clearVideoSurface() }
                runCatching { release() }
            }
            exoPlayer = null
            currentMediaUri = null
        }

        private fun getPhysicalOrSurfaceResolution(): Pair<Int, Int> {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val screenPair = if (wm == null) {
                1080 to 1920
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                Pair(bounds.width().coerceAtLeast(1080), bounds.height().coerceAtLeast(1920))
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm.defaultDisplay?.getRealMetrics(metrics)
                Pair(
                    (metrics.widthPixels.takeIf { it > 0 } ?: 1080).coerceAtLeast(1080),
                    (metrics.heightPixels.takeIf { it > 0 } ?: 1920).coerceAtLeast(1920),
                )
            }
            return Pair(
                maxOf(screenPair.first, surfaceWidth),
                maxOf(screenPair.second, surfaceHeight),
            )
        }
    }
}
