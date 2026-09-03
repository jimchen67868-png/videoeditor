package com.example.videoeditor.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import com.example.videoeditor.model.ImageOverlay
import com.google.common.collect.ImmutableList
import kotlin.math.max
import kotlin.math.min

/**
 * Converts our app-level [ImageOverlay] list into a Media3 [OverlayEffect],
 * using the same proven pattern as [TextOverlayEffectFactory] (same
 * TextureOverlay/OverlaySettings mechanism, same global-to-clip-local
 * windowing), just with a decoded Bitmap instead of rendered text.
 *
 * Sizing note: rather than depending on an OverlaySettings scale API we
 * haven't been able to verify in this environment, the bitmap is pre-scaled
 * to its target pixel size ourselves before handing it to Media3 -- this
 * keeps image overlays on the same solid ground as the already-working text
 * overlay pipeline (which only relies on setAlphaScale + the two frame
 * anchors, all confirmed working). One tradeoff: since the scale is relative
 * to a fixed reference width rather than the actual output resolution, an
 * overlay's on-screen size may look slightly different between the preview
 * surface and a very differently-sized export -- a known imprecision, not
 * worth the added risk of an unverified API for this build.
 */
@UnstableApi
object ImageOverlayEffectFactory {

    private const val BASE_WIDTH_PX = 400

    fun overlaysForWindow(all: List<ImageOverlay>, windowStartMs: Long, windowDurationMs: Long): List<ImageOverlay> {
        val windowEndMs = windowStartMs + windowDurationMs
        return all.mapNotNull { overlay ->
            val overlapStart = max(overlay.startMs, windowStartMs)
            val overlapEnd = min(overlay.endMs, windowEndMs)
            if (overlapEnd <= overlapStart) return@mapNotNull null
            overlay.copy(startMs = overlapStart - windowStartMs, endMs = overlapEnd - windowStartMs)
        }
    }

    fun build(context: Context, overlays: List<ImageOverlay>): OverlayEffect? {
        if (overlays.isEmpty()) return null

        val media3Overlays = overlays.mapNotNull { overlay ->
            val bitmap = loadScaledBitmap(context, overlay) ?: return@mapNotNull null

            object : BitmapOverlay() {
                override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

                override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
                    val timeMs = presentationTimeUs / 1000
                    val alpha = if (timeMs in overlay.startMs..overlay.endMs) overlay.opacity else 0f
                    return OverlaySettings.Builder()
                        .setAlphaScale(alpha)
                        .setBackgroundFrameAnchor(overlay.x * 2 - 1f, 1f - overlay.y * 2)
                        .setOverlayFrameAnchor(0f, 0f)
                        .build()
                }
            }
        }

        if (media3Overlays.isEmpty()) return null
        return OverlayEffect(ImmutableList.copyOf(media3Overlays))
    }

    private fun loadScaledBitmap(context: Context, overlay: ImageOverlay): Bitmap? {
        return try {
            context.contentResolver.openInputStream(overlay.sourceUri)?.use { input ->
                val original = BitmapFactory.decodeStream(input) ?: return null
                val targetWidth = (BASE_WIDTH_PX * overlay.scale).toInt().coerceAtLeast(10)
                val aspect = original.height.toFloat() / original.width.toFloat()
                val targetHeight = (targetWidth * aspect).toInt().coerceAtLeast(10)
                Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)
            }
        } catch (e: Exception) {
            null
        }
    }
}
