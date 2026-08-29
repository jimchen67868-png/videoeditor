package com.example.videoeditor.effects

import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay as Media3TextOverlay
import com.example.videoeditor.model.TextOverlay
import com.google.common.collect.ImmutableList

/**
 * Converts our app-level [TextOverlay] list into a Media3 [OverlayEffect].
 *
 * Media3 renders each TextOverlay as a bitmap composited over the frame via GL,
 * so this runs the same in live preview and in final export -- no separate
 * "preview renderer" vs "export renderer" to keep in sync.
 */
@UnstableApi
object TextOverlayEffectFactory {

    fun build(overlays: List<TextOverlay>): OverlayEffect? {
        if (overlays.isEmpty()) return null

        val media3Overlays = overlays.map { overlay ->
            val spannable = SpannableString(overlay.text).apply {
                setSpan(ForegroundColorSpan(overlay.colorArgb), 0, overlay.text.length, 0)
                setSpan(AbsoluteSizeSpan(overlay.sizeSp.toInt(), true), 0, overlay.text.length, 0)
            }

            object : Media3TextOverlay() {
                override fun getText(presentationTimeUs: Long): CharSequence = spannable

                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                    // Only show within this overlay's active window; otherwise fully transparent.
                    val timeMs = presentationTimeUs / 1000
                    val alpha = if (timeMs in overlay.startMs..overlay.endMs) 1f else 0f
                    return OverlaySettings.Builder()
                        .setAlphaScale(alpha)
                        .setOverlayFrameAnchor(overlay.x * 2 - 1f, 1f - overlay.y * 2)
                        .build()
                }
            }
        }

        return OverlayEffect(ImmutableList.copyOf(media3Overlays))
    }
}
