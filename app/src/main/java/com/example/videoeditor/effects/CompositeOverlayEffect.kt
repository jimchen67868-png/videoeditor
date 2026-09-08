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
 * onto ONE composited bitmap, using plain Android Canvas drawing, then hands
 * Media3 exactly ONE BitmapOverlay -- never more than one, regardless of how
 * many items are conceptually active. See class-level history in git log for
 * why (two earlier approaches assumed things about Media3's OverlayEffect
 * multi-item behavior that didn't hold up on real-device testing).
 *
 * CRITICAL PERFORMANCE NOTE: getBitmap() is called by Media3's render
 * pipeline for potentially every single frame (30-60 times/second). An
 * earlier version of this file allocated a brand-new several-megabyte bitmap
 * on every call, which flooded memory and crashed/froze playback entirely
 * (black screen, unresponsive player) -- a real regression that shipped
 * briefly. This version CACHES the composited bitmap and only regenerates it
 * when the actual SET of active overlays changes (i.e., when something
 * starts or stops being visible), which happens rarely compared to the
 * frame rate -- the overwhelming majority of getBitmap() calls now just
 * return the same cached Bitmap instance instead of allocating a new one.
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
            // Cache: only regenerate the composite when the active set changes.
            private var cachedBitmap: Bitmap? = null
            private var cachedKey: String? = null

            override fun getBitmap(presentationTimeUs: Long): Bitmap {
                return try {
                    val timeMs = presentationTimeUs / 1000
                    val activeText = textOverlays.filter { timeMs in it.startMs..it.endMs }
                    val activeImages = imageOverlays.filter { timeMs in it.startMs..it.endMs }

                    // Key changes only when WHICH items are visible changes, not
                    // every frame -- e.g. "text-id-1,text-id-2|image-id-1".
                    val key = buildString {
                        append(activeText.joinToString(",") { it.id })
                        append('|')
                        append(activeImages.joinToString(",") { it.id })
                    }

                    val existing = cachedBitmap
                    if (key == cachedKey && existing != null) {
                        existing
                    } else {
                        val composite = renderComposite(activeText, activeImages, decodedImages)
                        cachedBitmap = composite
                        cachedKey = key
                        composite
                    }
                } catch (e: Exception) {
                    // This runs on Media3's render thread, not caught by any
                    // try/catch at the call site that builds this effect --
                    // a failure here must not propagate into the render
                    // pipeline (that's exactly what caused a black-screen/
                    // frozen-player regression once already). Fall back to a
                    // reused transparent bitmap instead of crashing.
                    fallbackBitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { fallbackBitmap = it }
                }
            }

            private var fallbackBitmap: Bitmap? = null

            override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
                OverlaySettings.Builder()
                    .setAlphaScale(1f)
                    // Full-frame composite anchored center-to-center; individual
                    // item visibility/position is already baked into the bitmap
                    // above, not handled via per-item anchors anymore.
                    .setBackgroundFrameAnchor(0f, 0f)
                    .setOverlayFrameAnchor(0f, 0f)
                    .build()
        }

        return OverlayEffect(ImmutableList.of(media3Overlay))
    }

    private fun renderComposite(
        activeText: List<TextOverlay>,
        activeImages: List<ImageOverlay>,
        decodedImages: Map<String, Bitmap>
    ): Bitmap {
        val composite = Bitmap.createBitmap(CANVAS_WIDTH, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composite)

        // Images first (more "background"-like), text/stickers on top.
        for (overlay in activeImages) {
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

        for (overlay in activeText) {
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = overlay.colorArgb
                // sizeSp was tuned for a phone-density UI preview; scale it
                // relative to our fixed reference canvas width instead.
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
