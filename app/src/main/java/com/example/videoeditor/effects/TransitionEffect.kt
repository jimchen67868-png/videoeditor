package com.example.videoeditor.effects

import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import kotlin.math.min

/**
 * Fade-through-black transition effect: fades a clip in from black at its
 * start and/or fades it out to black at its end, by animating a brightness
 * scale (0..1) as a per-frame RgbMatrix based on presentationTimeUs.
 *
 * IMPORTANT SCOPE NOTE: this is a fade transition, not a true cross-dissolve.
 * A real crossfade blends two overlapping clips' frames together (A fading
 * out while B fades in, at the same time, no black in between), which needs
 * Media3's multi-sequence video compositing -- a genuinely experimental part
 * of the Transformer API where getting the exact setup wrong is easy and the
 * failure mode is silent visual bugs, not a compile error. A fade-through-black
 * is the reliable, well-supported alternative and is what many simple editors
 * use as their default "transition" anyway. If a true dissolve becomes a hard
 * requirement, that's a separate, larger piece of work building on
 * Composition.Builder's multi-sequence support with a custom compositor.
 *
 * presentationTimeUs here is relative to the clip's own trimmed window
 * (starts near 0), matching the same convention TextOverlayEffectFactory uses.
 */
@UnstableApi
object TransitionEffectFactory {

    /**
     * @param fadeInDurationMs fade up from black over this many ms at the clip's start (0 = no fade-in)
     * @param fadeOutDurationMs fade down to black over this many ms at the clip's end (0 = no fade-out)
     * @param clipRawDurationMs the clip's actual playback duration (trimEndMs - trimStartMs) --
     *   NOT speed-adjusted, since presentationTimeUs reflects raw source timing during export.
     */
    fun fadeEffect(fadeInDurationMs: Long, fadeOutDurationMs: Long, clipRawDurationMs: Long): RgbMatrix? {
        if (fadeInDurationMs <= 0 && fadeOutDurationMs <= 0) return null

        return RgbMatrix { presentationTimeUs, _ ->
            val timeMs = presentationTimeUs / 1000
            var scale = 1f

            if (fadeInDurationMs > 0 && timeMs < fadeInDurationMs) {
                scale = (timeMs.toFloat() / fadeInDurationMs).coerceIn(0f, 1f)
            }

            if (fadeOutDurationMs > 0) {
                val fadeOutStart = clipRawDurationMs - fadeOutDurationMs
                if (timeMs > fadeOutStart) {
                    val fadeOutScale = ((clipRawDurationMs - timeMs).toFloat() / fadeOutDurationMs).coerceIn(0f, 1f)
                    scale = min(scale, fadeOutScale)
                }
            }

            floatArrayOf(
                scale, 0f, 0f, 0f,
                0f, scale, 0f, 0f,
                0f, 0f, scale, 0f,
                0f, 0f, 0f, 1f
            )
        }
    }
}
