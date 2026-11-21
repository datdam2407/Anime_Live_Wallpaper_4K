package com.example.service

import android.content.Context
import android.opengl.GLES20
import android.os.Build
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * GPU OpenGL ES fragment shader effect (Media3 GlEffect) that performs FFmpeg-style bilinear
 * pixel interpolation over the bottom watermark area ("mylivewallpapers.com") without cropping
 * or resizing the original video frame.
 */
@UnstableApi
class DelogoEffect(
    // Normalized coordinates (0.0..1.0) covering the "mylivewallpapers.com" text box.
    // In OpenGL texture space, Y = 0.0 is at the bottom edge of the frame.
    val boxLeft: Float = 0.20f,
    val boxRight: Float = 0.80f,
    val boxBottom: Float = 0.015f,
    val boxTop: Float = 0.055f,
) : GlEffect {

    init {
        require(boxLeft in 0f..1f && boxRight in 0f..1f && boxLeft < boxRight) {
            "Invalid horizontal delogo bounds: [$boxLeft, $boxRight]"
        }
        require(boxBottom in 0f..1f && boxTop in 0f..1f && boxBottom < boxTop) {
            "Invalid vertical delogo bounds: [$boxBottom, $boxTop]"
        }
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return try {
            DelogoShaderProgram(useHdr, boxLeft, boxRight, boxBottom, boxTop)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        /**
         * Media3's DefaultVideoFrameProcessor / DefaultGlObjectsProvider relies on EGL attributes
         * that fail with EGL_BAD_ATTRIBUTE (0x3004) / EGL_BAD_CONTEXT (0x3006) on Android
         * Emulator's EGL_emulation bridge, while physical Android GPUs support it cleanly.
         */
        fun isEmulatorDevice(
            fingerprint: String = Build.FINGERPRINT.orEmpty(),
            model: String = Build.MODEL.orEmpty(),
            manufacturer: String = Build.MANUFACTURER.orEmpty(),
            brand: String = Build.BRAND.orEmpty(),
            device: String = Build.DEVICE.orEmpty(),
            product: String = Build.PRODUCT.orEmpty(),
            hardware: String = Build.HARDWARE.orEmpty(),
        ): Boolean {
            val fp = fingerprint.lowercase()
            val mdl = model.lowercase()
            val mfg = manufacturer.lowercase()
            val brd = brand.lowercase()
            val dev = device.lowercase()
            val prd = product.lowercase()
            val hw = hardware.lowercase()
            return fp.startsWith("generic") ||
                    fp.startsWith("unknown") ||
                    fp.contains("emulator") ||
                    mdl.contains("google_sdk") ||
                    mdl.contains("emulator") ||
                    mdl.contains("android sdk built for") ||
                    mfg.contains("genymotion") ||
                    (brd.startsWith("generic") && dev.startsWith("generic")) ||
                    prd.contains("sdk_gphone") ||
                    prd.contains("emulator") ||
                    prd.contains("simulator") ||
                    hw.contains("goldfish") ||
                    hw.contains("ranchu")
        }

        /**
         * Disabled by default because R2 live wallpapers are pre-cleaned via FFmpeg before upload.
         * Keeping runtime OpenGL/EGL GlEffect off lets Media3 ExoPlayer output Zero-Copy directly
         * from MediaCodec to the hardware Surface (~20-40% lower GPU & battery draw).
         */
        const val ENABLED_BY_DEFAULT: Boolean = false

        fun isSupportedOnDevice(): Boolean = !isEmulatorDevice()

        fun shouldEnableRuntimeDelogo(): Boolean = ENABLED_BY_DEFAULT && isSupportedOnDevice()
    }
}

@UnstableApi
private class DelogoShaderProgram(
    useHdr: Boolean,
    left: Float,
    right: Float,
    bottom: Float,
    top: Float,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val glProgram: GlProgram

    init {
        val vertexShader = """
            attribute vec4 aFramePosition;
            varying vec2 vTexSamplingCoord;
            void main() {
                gl_Position = aFramePosition;
                vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
            }
        """.trimIndent()

        // Vertical + horizontal border interpolation matching FFmpeg delogo
        val fragmentShader = """
            precision mediump float;
            uniform sampler2D uTexSampler;
            uniform vec4 uBox; // x: left, y: bottom, z: right, w: top
            varying vec2 vTexSamplingCoord;

            void main() {
                vec2 uv = vTexSamplingCoord;

                if (uv.x >= uBox.x && uv.x <= uBox.z && uv.y >= uBox.y && uv.y <= uBox.w) {
                    float weightY = (uv.y - uBox.y) / max(uBox.w - uBox.y, 0.0001);
                    float weightX = (uv.x - uBox.x) / max(uBox.z - uBox.x, 0.0001);

                    vec4 colorBottom = texture2D(uTexSampler, vec2(uv.x, max(uBox.y - 0.004, 0.001)));
                    vec4 colorTop = texture2D(uTexSampler, vec2(uv.x, min(uBox.w + 0.004, 0.999)));
                    vec4 colorLeft = texture2D(uTexSampler, vec2(max(uBox.x - 0.004, 0.001), uv.y));
                    vec4 colorRight = texture2D(uTexSampler, vec2(min(uBox.z + 0.004, 0.999), uv.y));

                    vec4 interpY = mix(colorBottom, colorTop, weightY);
                    vec4 interpX = mix(colorLeft, colorRight, weightX);

                    // Favor vertical interpolation (75%) since watermark text sits horizontally along the bottom edge
                    gl_FragColor = mix(interpY, interpX, 0.25);
                } else {
                    gl_FragColor = texture2D(uTexSampler, uv);
                }
            }
        """.trimIndent()

        glProgram = GlProgram(vertexShader, fragmentShader)
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        glProgram.setFloatsUniform("uBox", floatArrayOf(left, bottom, right, top))
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (_: GlUtil.GlException) {
            // Safe teardown on GL thread
        }
    }
}
