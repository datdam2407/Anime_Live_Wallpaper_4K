package com.example.service

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Hardware capability profile across the 19,547-device Play Console matrix (SDK 24–36):
 * - Identifies <= 2GB RAM (26.1%) and <= 4GB RAM (69.6%) memory-constrained devices
 * - Identifies OpenGL ES 2.0-only devices (969 devices)
 * - Identifies SoCs/GPUs with strict or quirky hardware MediaCodec implementations
 *   (MediaTek, Unisoc/Spreadtrum, Allwinner, Rockchip, PowerVR)
 */
data class DeviceHardwareProfile(
    val isLowRamDevice: Boolean,
    val isConstrainedRamDevice: Boolean,
    val totalRamBytes: Long,
    val glEsVersionMajor: Int,
    val isHighRiskDecoderSoc: Boolean,
    val is32BitOnlyAbi: Boolean,
) {
    val supportsGles30: Boolean
        get() = glEsVersionMajor >= 3

    companion object {
        const val LOW_RAM_THRESHOLD_BYTES: Long = 2_250L * 1024L * 1024L // ~2.2 GB
        const val CONSTRAINED_RAM_THRESHOLD_BYTES: Long = 4_300L * 1024L * 1024L // ~4.2 GB

        fun detect(context: Context): DeviceHardwareProfile {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            runCatching { am?.getMemoryInfo(memInfo) }

            val totalRam = memInfo.totalMem.takeIf { it > 0L } ?: (3_000L * 1024L * 1024L)
            val lowRamFlag = runCatching { am?.isLowRamDevice == true }.getOrDefault(false)
            val isLowRam = lowRamFlag || totalRam <= LOW_RAM_THRESHOLD_BYTES
            val isConstrainedRam = isLowRam || totalRam <= CONSTRAINED_RAM_THRESHOLD_BYTES

            val rawGlEs = runCatching { am?.deviceConfigurationInfo?.reqGlEsVersion ?: 0x00020000 }
                .getOrDefault(0x00020000)
            val glMajor = ((rawGlEs and -0x10000) ushr 16).coerceAtLeast(2)

            val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER.orEmpty()
            } else {
                ""
            }
            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.orEmpty()
            } else {
                ""
            }
            val combinedHw = buildString {
                append(Build.HARDWARE.orEmpty()).append(' ')
                append(Build.BOARD.orEmpty()).append(' ')
                append(socManufacturer).append(' ')
                append(socModel)
            }.lowercase()

            val highRiskSoc = combinedHw.contains("mt") ||
                combinedHw.contains("mediatek") ||
                combinedHw.contains("unisoc") ||
                combinedHw.contains("spreadtrum") ||
                combinedHw.contains("ums") ||
                combinedHw.contains("sp9") ||
                combinedHw.contains("sc9") ||
                combinedHw.contains("allwinner") ||
                combinedHw.contains("sun") ||
                combinedHw.contains("rockchip") ||
                combinedHw.contains("rk3") ||
                combinedHw.contains("powervr")

            val abis = Build.SUPPORTED_64_BIT_ABIS
            val is32BitOnly = abis == null || abis.isEmpty()

            return DeviceHardwareProfile(
                isLowRamDevice = isLowRam,
                isConstrainedRamDevice = isConstrainedRam,
                totalRamBytes = totalRam,
                glEsVersionMajor = glMajor,
                isHighRiskDecoderSoc = highRiskSoc,
                is32BitOnlyAbi = is32BitOnly,
            )
        }
    }
}

/**
 * Metadata and decoder-compatibility verdict for a local MP4 file.
 * Strongly prefers H.264/AVC Baseline/Main (8-bit yuv420p, <= 1080p).
 */
data class VideoCompatibilityReport(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val profile: Int,
    val isTenBitOrHdr: Boolean,
    val hasHardwareDecoderSupport: Boolean,
    val hasAnyDecoderSupport: Boolean,
) {
    val shortSide: Int
        get() = min(width, height)

    val longSide: Int
        get() = max(width, height)

    val isAvcH264: Boolean
        get() = mimeType.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true)

    val isWithin1080p: Boolean
        get() = shortSide in 1..1088 && longSide in 1..1920

    val isPreferredBaselineOrMainAvc1080p: Boolean
        get() = isAvcH264 && !isTenBitOrHdr && isWithin1080p

    /**
     * Returns true when downloading/playing the lightweight <=1080p H.264 `previewUrl` variant
     * is safer than feeding a high-profile / >1080p / HEVC / 10-bit `fullUrl` to this device.
     */
    fun shouldPreferCompatibilityStream(device: DeviceHardwareProfile): Boolean {
        if (!hasAnyDecoderSupport) return true
        if (isTenBitOrHdr && (device.isConstrainedRamDevice || device.isHighRiskDecoderSoc || !device.supportsGles30)) {
            return true
        }
        if (!isWithin1080p && (device.isLowRamDevice || !device.supportsGles30 || !hasHardwareDecoderSupport)) {
            return true
        }
        if (!isAvcH264 && (device.isLowRamDevice || !hasHardwareDecoderSupport)) {
            return true
        }
        if (frameRate > 35 && device.isLowRamDevice && device.isHighRiskDecoderSoc && !hasHardwareDecoderSupport) {
            return true
        }
        return false
    }

    /**
     * Returns true when ExoPlayer should prioritize software decoding (`c2.android.*` / `OMX.google.*`)
     * immediately rather than risking a silent black-frame hardware decoder stall.
     */
    fun shouldPreferSoftwareDecoderFirst(device: DeviceHardwareProfile): Boolean {
        if (!hasHardwareDecoderSupport) return true
        if (isTenBitOrHdr && device.isHighRiskDecoderSoc) return true
        if (!isAvcH264 && !device.supportsGles30) return true
        return false
    }
}

object VideoCompatibilityInspector {

    private val KNOWN_MP4_BOX_TYPES = setOf(
        "ftyp",
        "moov",
        "mdat",
        "free",
        "skip",
        "wide",
        "styp",
        "pdin",
    )

    /**
     * Ensures [file] exists, is non-empty, is NOT stored inside OS-purgeable `cacheDir` or
     * `externalCacheDir`, and is not a captive-portal HTML/XML/JSON error page.
     */
    fun isReliableLocalVideoFile(
        file: File?,
        cacheDir: File? = null,
        externalCacheDir: File? = null,
    ): Boolean {
        if (file == null || !file.exists() || !file.isFile || file.length() <= 0L) {
            return false
        }
        if (isInsideDirectory(file, cacheDir) || isInsideDirectory(file, externalCacheDir)) {
            return false
        }
        return !looksLikeTextErrorPayload(file)
    }

    internal fun isInsideDirectory(file: File, dir: File?): Boolean {
        if (dir == null) return false
        val filePath = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val dirPath = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
            .trimEnd(File.separatorChar)
        return filePath == dirPath || filePath.startsWith("$dirPath${File.separatorChar}")
    }

    /**
     * Rejects HTTP 200 captive-portal HTML / XML / JSON error bodies that would otherwise get
     * cached as `.mp4` files and fail ExoPlayer parsing with a black screen.
     */
    fun looksLikeTextErrorPayload(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return true
        return runCatching {
            val header = ByteArray(min(file.length(), 64L).toInt())
            FileInputStream(file).use { input ->
                val read = input.read(header)
                if (read <= 0) return@runCatching true
                val ascii = String(header, 0, read, Charsets.ISO_8859_1).trimStart().lowercase()
                ascii.startsWith("<!doctype") ||
                    ascii.startsWith("<html") ||
                    ascii.startsWith("<?xml") ||
                    ascii.startsWith("{\"error") ||
                    ascii.startsWith("access denied")
            }
        }.getOrDefault(false)
    }

    fun hasValidMp4Signature(file: File): Boolean {
        if (!file.exists() || file.length() < 12L) return false
        return runCatching {
            val header = ByteArray(12)
            FileInputStream(file).use { input ->
                if (input.read(header) < 8) return@runCatching false
            }
            val boxType = String(header, 4, 4, Charsets.US_ASCII)
            boxType in KNOWN_MP4_BOX_TYPES
        }.getOrDefault(false)
    }

    /**
     * Inspects the video track of a local MP4 file via [MediaExtractor] and matches it against
     * the device's registered [MediaCodecList] decoders. Returns `null` if [MediaExtractor]
     * cannot parse a video track (e.g. corrupt container or synthetic unit-test fixture).
     */
    fun inspectVideoFile(file: File): VideoCompatibilityReport? {
        if (!file.exists() || file.length() <= 0L) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/", ignoreCase = true)) {
                    videoFormat = format
                    break
                }
            }
            val format = videoFormat ?: return null
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            val width = format.getIntegerSafe(MediaFormat.KEY_WIDTH, 1080)
            val height = format.getIntegerSafe(MediaFormat.KEY_HEIGHT, 1920)
            val fps = format.getIntegerSafe(MediaFormat.KEY_FRAME_RATE, 30).coerceIn(1, 120)
            val profile = format.getIntegerSafe(MediaFormat.KEY_PROFILE, -1)
            val colorTransfer = format.getIntegerSafe(MediaFormat.KEY_COLOR_TRANSFER, -1)

            val isHdrTransfer = colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 ||
                colorTransfer == MediaFormat.COLOR_TRANSFER_HLG
            val isTenBitProfile = (mime == MediaFormat.MIMETYPE_VIDEO_AVC &&
                profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10) ||
                (mime == MediaFormat.MIMETYPE_VIDEO_HEVC &&
                    (profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                        profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 ||
                        profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus))

            val (hwSupported, anySupported) = queryDecoderSupport(mime, width, height, fps)
            VideoCompatibilityReport(
                mimeType = mime,
                width = width,
                height = height,
                frameRate = fps,
                profile = profile,
                isTenBitOrHdr = isHdrTransfer || isTenBitProfile,
                hasHardwareDecoderSupport = hwSupported,
                hasAnyDecoderSupport = anySupported,
            )
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun queryDecoderSupport(
        mime: String,
        width: Int,
        height: Int,
        fps: Int,
    ): Pair<Boolean, Boolean> {
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var hwSupported = false
            var anySupported = false
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                val supportedType = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) }
                    ?: continue
                val caps = runCatching { info.getCapabilitiesForType(supportedType) }.getOrNull()
                    ?: continue
                val videoCaps = caps.videoCapabilities ?: continue
                val sizeOk = videoCaps.areSizeAndRateSupported(width, height, fps.toDouble()) ||
                    videoCaps.areSizeAndRateSupported(height, width, fps.toDouble()) ||
                    videoCaps.isSizeSupported(width, height) ||
                    videoCaps.isSizeSupported(height, width)
                if (sizeOk) {
                    anySupported = true
                    if (!isSoftwareCodecName(info.name, info)) {
                        hwSupported = true
                    }
                }
            }
            hwSupported to anySupported
        }.getOrDefault(true to true)
    }

    internal fun isSoftwareCodecName(name: String, platformInfo: MediaCodecInfo? = null): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && platformInfo != null) {
            if (platformInfo.isSoftwareOnly) return true
        }
        val lower = name.lowercase()
        return lower.startsWith("omx.google.") ||
            lower.startsWith("c2.android.") ||
            lower.contains(".sw.") ||
            lower.endsWith(".sw")
    }

    private fun MediaFormat.getIntegerSafe(key: String, defaultValue: Int): Int {
        return try {
            if (containsKey(key)) getInteger(key) else defaultValue
        } catch (_: Throwable) {
            defaultValue
        }
    }
}

/**
 * Media3 [MediaCodecSelector] that promotes Google/AOSP software decoders (`c2.android.*`,
 * `OMX.google.*`) ahead of vendor hardware codecs when hardware decoding fails or stalls with
 * black frames on MediaTek, Unisoc, Allwinner, Rockchip, or PowerVR devices.
 */
@UnstableApi
object SoftwarePreferredMediaCodecSelector : MediaCodecSelector {
    override fun getDecoderInfos(
        mimeType: String,
        requiresSecureDecoder: Boolean,
        requiresTunnelingDecoder: Boolean,
    ): MutableList<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        val defaultList = MediaCodecSelector.DEFAULT.getDecoderInfos(
            mimeType,
            requiresSecureDecoder,
            requiresTunnelingDecoder,
        )
        return rankSoftwareDecodersFirst(defaultList).toMutableList()
    }

    internal fun rankSoftwareDecodersFirst(
        decoders: List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo>,
    ): List<androidx.media3.exoplayer.mediacodec.MediaCodecInfo> {
        return decoders.sortedByDescending { info ->
            info.softwareOnly || VideoCompatibilityInspector.isSoftwareCodecName(info.name)
        }
    }
}
