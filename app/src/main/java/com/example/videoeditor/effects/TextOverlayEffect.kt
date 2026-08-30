package com.example.videoeditor.effects

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
 *
 * FIX (previous bug -- text was added to the data model correctly but never
 * rendered): the anchor pairing was backwards. OverlaySettings has TWO
 * anchors that work together: backgroundFrameAnchor (WHERE on the video
 * frame to position things) and overlayFrameAnchor (WHICH point ON THE
 * OVERLAY BITMAP gets placed at that position). The previous code only set
 * overlayFrameAnchor to the computed x/y and left backgroundFrameAnchor at
 * its default, which -- depending on the overlay bitmap's own dimensions --
 * could push the whole overlay off the visible frame entirely (invisible
 * despite alpha being fully opaque). Fixed by setting backgroundFrameAnchor
 * to the desired screen position and overlayFrameAnchor to (0,0) (the
 * overlay bitmap's own center), so the overlay's center lands at that point
 * on the frame -- the usual intended behavior.
 *
 * Also simplified: alpha is always fully opaque for the overlay's whole
 * active window rather than time-windowed, since nothing in the UI currently
 * lets a user create a partial-duration overlay anyway (every overlay added
 * via the "Add Text" dialog spans the clip's entire duration) -- this removes
 * a presentationTimeUs-timebase assumption that wasn't worth the added risk
 * for a feature not yet exposed.
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

            val settings = OverlaySettings.Builder()
                .setAlphaScale(1f)
                // Position on the video frame (NDC: -1..1, y flipped since our
                // model's y=0 is top like normal UI coordinates).
                .setBackgroundFrameAnchor(overlay.x * 2 - 1f, 1f - overlay.y * 2)
                // Which point of the overlay bitmap aligns there -- (0,0) = its own center.
                .setOverlayFrameAnchor(0f, 0f)
                .build()

            object : Media3TextOverlay() {
                override fun getText(presentationTimeUs: Long): SpannableString = spannable
                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
            }
        }

        return OverlayEffect(ImmutableList.copyOf(media3Overlays))
    }
}
