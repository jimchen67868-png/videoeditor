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
 * Converts our app-level [TextOverlay] list into a Media3 [OverlayEffect].
 *
 * Media3 renders each TextOverlay as a bitmap composited over the frame via GL,
 * so this runs the same in live preview and in final export -- no separate
 * "preview renderer" vs "export renderer" to keep in sync.
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

    fun build(overlays: List<TextOverlay>): OverlayEffect? {
        if (overlays.isEmpty()) return null

        val media3Overlays = overlays.map { overlay ->
            val spannable = SpannableString(overlay.text).apply {
                setSpan(ForegroundColorSpan(overlay.colorArgb), 0, overlay.text.length, 0)
                setSpan(AbsoluteSizeSpan(overlay.sizeSp.toInt(), true), 0, overlay.text.length, 0)
                if (overlay.hasBackground) {
                    setSpan(BackgroundColorSpan(android.graphics.Color.argb(160, 0, 0, 0)), 0, overlay.text.length, 0)
                }
            }

            object : Media3TextOverlay() {
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
        }

        return OverlayEffect(ImmutableList.copyOf(media3Overlays))
    }
}
