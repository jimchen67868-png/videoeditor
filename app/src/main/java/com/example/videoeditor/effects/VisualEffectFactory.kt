package com.example.videoeditor.effects

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix
import com.example.videoeditor.model.EffectType
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animated visual effects (distinct from [FilterShaderEffect]'s static color
 * grading). Two Media3 building blocks, both time-varying per-frame via
 * presentationTimeUs, same convention as filters/transitions (relative to the
 * clip's own trimmed window, not speed-adjusted):
 *   - RgbMatrix for brightness-based effects (Flash, Pulse) -- the exact same
 *     mechanism already proven working for color filters in this codebase.
 *   - MatrixTransformation for geometric effects (Shake, Zoom) -- a simpler,
 *     single-item Media3 API (unlike the multi-sequence compositing avoided
 *     for crossfade transitions), used in Media3's own demo app for this
 *     exact purpose. Matrix operations are in NDC space (-1..1, origin at
 *     frame center), so postScale/postTranslate values are small fractions,
 *     not pixel counts.
 */
@UnstableApi
object VisualEffectFactory {

    fun forType(type: EffectType, clipRawDurationMs: Long): List<Effect> = when (type) {
        EffectType.NONE -> emptyList()
        EffectType.FLASH -> listOf(flashEffect())
        EffectType.PULSE -> listOf(pulseEffect())
        EffectType.SHAKE -> listOf(shakeEffect())
        EffectType.ZOOM_IN -> listOf(zoomEffect(startScale = 1f, endScale = 1.3f, durationMs = clipRawDurationMs))
        EffectType.ZOOM_OUT -> listOf(zoomEffect(startScale = 1.3f, endScale = 1f, durationMs = clipRawDurationMs))
    }

    /** Brief bright white flash over the first 150ms of the clip. */
    private fun flashEffect(): RgbMatrix {
        val flashDurationMs = 150L
        return RgbMatrix { presentationTimeUs, _ ->
            val timeMs = presentationTimeUs / 1000
            val intensity = if (timeMs < flashDurationMs) {
                (1f - timeMs.toFloat() / flashDurationMs).coerceIn(0f, 1f)
            } else 0f
            val scale = 1f + intensity * 2f
            floatArrayOf(
                scale, 0f, 0f, intensity,
                0f, scale, 0f, intensity,
                0f, 0f, scale, intensity,
                0f, 0f, 0f, 1f
            )
        }
    }

    /** Rhythmic brightness pulsing (+/-15%) for the whole clip. */
    private fun pulseEffect(): RgbMatrix = RgbMatrix { presentationTimeUs, _ ->
        val timeMs = presentationTimeUs / 1000
        val scale = 1f + 0.15f * sin(timeMs / 200.0).toFloat()
        floatArrayOf(
            scale, 0f, 0f, 0f,
            0f, scale, 0f, 0f,
            0f, 0f, scale, 0f,
            0f, 0f, 0f, 1f
        )
    }

    /** Small continuous jitter, like handheld-camera shake. */
    private fun shakeEffect(): MatrixTransformation = MatrixTransformation { presentationTimeUs ->
        val timeMs = presentationTimeUs / 1000
        val dx = 0.02f * sin(timeMs / 60.0).toFloat()
        val dy = 0.02f * cos(timeMs / 47.0).toFloat()
        Matrix().apply { postTranslate(dx, dy) }
    }

    /** Smooth scale animation from [startScale] to [endScale] across the clip's duration. */
    private fun zoomEffect(startScale: Float, endScale: Float, durationMs: Long): MatrixTransformation =
        MatrixTransformation { presentationTimeUs ->
            val timeMs = presentationTimeUs / 1000
            val safeDuration = if (durationMs > 0) durationMs else 1L
            val progress = (timeMs.toFloat() / safeDuration).coerceIn(0f, 1f)
            val scale = startScale + (endScale - startScale) * progress
            Matrix().apply { postScale(scale, scale) }
        }
}
