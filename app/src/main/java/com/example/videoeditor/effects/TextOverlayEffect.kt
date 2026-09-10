package com.example.videoeditor.effects

import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay as Media3TextOverlay
import com.example.videoeditor.model.TextOverlay
import com.google.common.collect.ImmutableList
import kotlin.math.max
import kotlin.math.min

/**
 * Converts our app-level [TextOverlay] list into a LIST of Media3 [OverlayEffect]s
 * -- one per overlay, chained together in the video effects list -- rather
 * than bundling all overlays into a single OverlayEffect's internal list.
 *
 * This was changed after finding that multiple simultaneous overlays didn't
 * actually render together when passed as one OverlayEffect's list (only one
 * showed, others stayed hidden) -- the exact multi-item compositing behavior
 * of that API wasn't something this environment could verify ahead of time.
 * Chaining single-overlay effects instead relies only on the simpler,
 * already-proven single-overlay path (used successfully throughout this
 * codebase for filters/effects too), applied N times via normal effect
 * chaining, which is a much better-understood mechanism.
 *
 * Text overlays live at the PROJECT level with GLOBAL timeline positions (see
 * model/Timeline.kt), but Media3's per-clip Effect pipeline only sees
 * presentationTimeUs relative to THAT clip's own trimmed window. [overlaysForWindow]
 * bridges the two: given a clip's position on the global timeline, it returns
 * only the overlays that are actually active during that clip, remapped to
 * clip-local ms so [build]'s alpha windowing lines up correctly.
 */
@UnstableApi
object TextOverlayEffectFactory {

    /**
     * Filters [all] down to overlays that overlap [windowStartMs]..[windowStartMs]+[windowDurationMs],
     * and remaps their startMs/endMs to be relative to that window (0-based)
     * instead of the global timeline.
     */
    fun overlaysForWindow(all: List<TextOverlay>, windowStartMs: Long, windowDurationMs: Long): List<TextOverlay> {
        val windowEndMs = windowStartMs + windowDurationMs
        return all.mapNotNull { overlay ->
            val overlapStart = max(overlay.startMs, windowStartMs)
            val overlapEnd = min(overlay.endMs, windowEndMs)
            if (overlapEnd <= overlapStart) return@mapNotNull null
            overlay.copy(startMs = overlapStart - windowStartMs, endMs = overlapEnd - windowStartMs)
        }
    }

    /**
     * Returns one OverlayEffect per overlay (empty list if [overlays] is empty).
     *
     * @param referenceWidthPx used to scale text size in ABSOLUTE PIXELS rather
     *   than density-dependent sp. AbsoluteSizeSpan's dip=true mode needs a
     *   valid display-density context to convert sp to pixels -- Transformer's
     *   offline export pipeline may not reliably have that available, which
     *   could silently render text at near-zero size (technically "there" but
     *   invisible) rather than throwing an error. Using dip=false with a size
     *   pre-scaled relative to the actual output width removes that
     *   dependency entirely.
     */
    fun build(overlays: List<TextOverlay>, referenceWidthPx: Int = 1080): List<OverlayEffect> {
        return overlays.map { overlay ->
            val pixelSize = (overlay.sizeSp * (referenceWidthPx / 360f)).toInt().coerceAtLeast(1)
            val spannable = SpannableString(overlay.text).apply {
                setSpan(ForegroundColorSpan(overlay.colorArgb), 0, overlay.text.length, 0)
                setSpan(AbsoluteSizeSpan(pixelSize, false), 0, overlay.text.length, 0)
                if (overlay.hasBackground) {
                    setSpan(BackgroundColorSpan(android.graphics.Color.argb(160, 0, 0, 0)), 0, overlay.text.length, 0)
                }
            }

            val media3Overlay = object : Media3TextOverlay() {
                override fun getText(presentationTimeUs: Long): SpannableString = spannable

                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                    val timeMs = presentationTimeUs / 1000
                    val alpha = if (timeMs in overlay.startMs..overlay.endMs) 1f else 0f
                    return OverlaySettings.Builder()
                        .setAlphaScale(alpha)
                        // backgroundFrameAnchor = position on the video frame;
                        // overlayFrameAnchor = which point of the overlay bitmap
                        // aligns there (0,0 = its own center).
                        .setBackgroundFrameAnchor(overlay.x * 2 - 1f, 1f - overlay.y * 2)
                        .setOverlayFrameAnchor(0f, 0f)
                        .build()
                }
            }

            OverlayEffect(ImmutableList.of(media3Overlay))
        }
    }
}
