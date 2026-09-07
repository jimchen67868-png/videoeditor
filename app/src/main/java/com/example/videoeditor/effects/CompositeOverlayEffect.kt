package com.example.videoeditor.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import com.example.videoeditor.model.ImageOverlay
import com.example.videoeditor.model.TextOverlay
import com.google.common.collect.ImmutableList

/**
 * Renders ALL currently-active text overlays, stickers, and image overlays
 * onto ONE composited bitmap per frame, using plain Android Canvas drawing,
 * then hands Media3 exactly ONE BitmapOverlay -- never more than one,
 * regardless of how many items are conceptually active.
 *
 * WHY: two earlier approaches both assumed things about Media3's OverlayEffect
 * that turned out to be wrong on real-device testing -- first, that bundling
 * multiple overlays into one OverlayEffect's internal list would composite
 * them together (only one rendered); then, that chaining multiple separate
 * OverlayEffect instances in sequence would stack them (same result: only
 * one showed). Rather than keep guessing at undocumented multi-overlay
 * behavior, this does the compositing ourselves in a domain we have complete
 * control and certainty over -- plain 2D Canvas drawing -- and only ever
 * hands Media3 a single, already-finished overlay layer.
 *
 * Tradeoffs, stated plainly:
 *  - A new bitmap is allocated and drawn every time getBitmap() is called
 *    (per relevant frame). Fine for typical use; could get expensive for
 *    very long exports with many overlays -- a caching layer keyed by
 *    rounded presentationTimeUs would be the next optimization if that
 *    becomes a real problem.
 *  - The composite is rendered at a FIXED reference resolution (see
 *    CANVAS_WIDTH/CANVAS_HEIGHT) rather than the actual output resolution,
 *    the same "known imprecision" tradeoff already accepted for image
 *    overlay sizing -- avoids depending on an exact frame-size hookup
 *    through the effect pipeline, at the cost of overlay sizing looking
 *    slightly different across very different export resolutions.
 */
@UnstableApi
object CompositeOverlayEffectFactory {

    private const val CANVAS_WIDTH = 720
    private const val CANVAS_HEIGHT = 1280

    /**
     * @param textOverlays / imageOverlays already windowed to the clip-local
     *   timeline (see TextOverlayEffectFactory/ImageOverlayEffectFactory's
     *   overlaysForWindow) -- startMs/endMs relative to the clip's own start.
     */
    fun build(
        context: Context,
        textOverlays: List<TextOverlay>,
        imageOverlays: List<ImageOverlay>
    ): OverlayEffect? {
        if (textOverlays.isEmpty() && imageOverlays.isEmpty()) return null

        // Decode image bitmaps once up front, not per-frame.
        val decodedImages: Map<String, Bitmap> = imageOverlays.mapNotNull { overlay ->
            loadBitmap(context, overlay.sourceUri)?.let { overlay.id to it }
        }.toMap()

        val media3Overlay = object : BitmapOverlay() {
            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                val timeMs = presentationTimeUs / 1000
                val composite = Bitmap.createBitmap(CANVAS_WIDTH, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(composite)

                // Images first (more "background"-like), text/stickers on top.
                for (overlay in imageOverlays) {
                    if (timeMs !in overlay.startMs..overlay.endMs) continue
                    val bitmap = decodedImages[overlay.id] ?: continue
                    val targetWidth = CANVAS_WIDTH * 0.3f * overlay.scale
                    val targetHeight = targetWidth * (bitmap.height.toFloat() / bitmap.width.toFloat())
                    val cx = overlay.x * CANVAS_WIDTH
                    val cy = overlay.y * CANVAS_HEIGHT
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        alpha = (overlay.opacity.coerceIn(0f, 1f) * 255).toInt()
                    }
                    val destRect = RectF(cx - targetWidth / 2f, cy - targetHeight / 2f, cx + targetWidth / 2f, cy + targetHeight / 2f)
                    canvas.drawBitmap(bitmap, null, destRect, paint)
                }

                for (overlay in textOverlays) {
                    if (timeMs !in overlay.startMs..overlay.endMs) continue
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = overlay.colorArgb
                        // sizeSp was tuned for a phone-density UI preview; scale
                        // it relative to our fixed reference canvas width instead.
                        textSize = overlay.sizeSp * (CANVAS_WIDTH / 360f)
                        textAlign = Paint.Align.CENTER
                    }
                    val cx = overlay.x * CANVAS_WIDTH
                    val cy = overlay.y * CANVAS_HEIGHT

                    if (overlay.hasBackground) {
                        val bounds = Rect()
                        textPaint.getTextBounds(overlay.text, 0, overlay.text.length, bounds)
                        val pad = 12f
                        val bgPaint = Paint().apply { color = Color.argb(160, 0, 0, 0) }
                        canvas.drawRect(
                            cx - bounds.width() / 2f - pad,
                            cy + bounds.top - pad,
                            cx + bounds.width() / 2f + pad,
                            cy + bounds.bottom + pad,
                            bgPaint
                        )
                    }

                    canvas.drawText(overlay.text, cx, cy, textPaint)
                }

                return composite
            }

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                OverlaySettings.Builder()
                    .setAlphaScale(1f)
                    // Full-frame composite anchored center-to-center; individual
                    // item visibility/position is already baked into the bitmap
                    // we drew above, not handled via per-item anchors anymore.
                    .setBackgroundFrameAnchor(0f, 0f)
                    .setOverlayFrameAnchor(0f, 0f)
                    .build()
        }

        return OverlayEffect(ImmutableList.of(media3Overlay))
    }

    private fun loadBitmap(context: Context, uri: android.net.Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                android.graphics.BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            null
        }
    }
}
