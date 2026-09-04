package com.example.videoeditor.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import com.example.videoeditor.model.AudioTrack
import com.example.videoeditor.model.Clip
import com.example.videoeditor.model.TextOverlay
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-track timeline strip:
 *   - video row: clips proportional to duration, with drag handles for
 *     trimming and real video-frame thumbnails.
 *   - text row: a colored segment per text overlay (this also covers
 *     "stickers", which are just large-emoji TextOverlays under the hood),
 *     selectable by tap and trimmable via the same drag-handle mechanism as
 *     video clips, just operating on the overlay's own start/end instead.
 *   - music row: a single segment showing the background music track's
 *     span relative to the whole project.
 *
 * Must be hosted directly inside a HorizontalScrollView for content wider
 * than the screen to be reachable -- this view reports its true content
 * width via onMeasure, AND auto-scrolls that container itself when a trim
 * handle (of either kind) is dragged near the visible edge.
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onClipTrimmed(clipId: String, newTrimStartMs: Long, newTrimEndMs: Long)
        fun onClipSelected(clipId: String)
        fun onPlayheadMoved(positionMs: Long)
        fun onTrimGestureStart() {}
        fun onTrimGestureEnd() {}
        fun onTextOverlaySelected(overlayId: String) {}
        fun onTextOverlayTrimmed(overlayId: String, newStartMs: Long, newEndMs: Long) {}
    }

    var listener: Listener? = null

    private var clips: List<Clip> = emptyList()
    private var audioTracks: List<AudioTrack> = emptyList()
    private var textOverlays: List<TextOverlay> = emptyList()
    private var selectedClipId: String? = null
    private var selectedOverlayId: String? = null
    private var pxPerMs: Float = 0.05f // zoom level; adjust for desired timeline density

    // --- Row heights (video row on top, text and music rows below it) ---
    private val videoRowHeightPx = 80f * resources.displayMetrics.density
    private val textRowHeightPx = 28f * resources.displayMetrics.density
    private val musicRowHeightPx = 28f * resources.displayMetrics.density
    private val rowGapPx = 2f * resources.displayMetrics.density

    private val clipPaint = Paint().apply { color = Color.parseColor("#3A3A3C") }
    private val selectedBorderPaint = Paint().apply {
        color = Color.parseColor("#FF7A00")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val dividerPaint = Paint().apply { color = Color.parseColor("#1C1C1E"); strokeWidth = 4f }
    private val handlePaint = Paint().apply { color = Color.WHITE }
    private val playheadPaint = Paint().apply { color = Color.parseColor("#FFD60A"); strokeWidth = 6f }
    private val bitmapPaint = Paint().apply { isFilterBitmap = true }

    private val trackBackgroundPaint = Paint().apply { color = Color.parseColor("#161617") }
    private val textSegmentPaint = Paint().apply { color = Color.parseColor("#FF9800") }
    private val musicSegmentPaint = Paint().apply { color = Color.parseColor("#2196F3") }
    private val trackLabelPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textSize = 11f * resources.displayMetrics.density
    }

    private var playheadMs: Long = 0L

    // Handle hit target sized in dp so it's actually grabbable on high-density screens.
    private val handleWidthPx = 24f * resources.displayMetrics.density
    // Overlay segments are shorter (text row is thinner than the video row), so
    // their handles are a bit narrower to still leave room to tap the segment itself.
    private val overlayHandleWidthPx = 16f * resources.displayMetrics.density

    private var draggingHandle: Handle? = null
    private data class Handle(val clip: Clip, val isStart: Boolean, val originalMs: Long, val downX: Float)

    private var draggingOverlayHandle: OverlayHandle? = null
    private data class OverlayHandle(val overlay: TextOverlay, val isStart: Boolean, val originalMs: Long, val downX: Float)

    // Thumbnail loading: one representative frame per clip, generated off
    // the main thread and cached by clip id. Re-generated only when a
    // clip's source or trim start changes (see loadThumbnailIfNeeded).
    private val thumbnailCache = mutableMapOf<String, Bitmap>()
    private val thumbnailTrimStamp = mutableMapOf<String, Long>()
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- Auto-scroll while dragging a handle (clip or overlay) near the visible edge ---
    private var scrollViewParent: HorizontalScrollView? = null
    private val edgeThresholdPx = 48f * resources.displayMetrics.density
    private val autoScrollStepPx = (10f * resources.displayMetrics.density).toInt()
    private var autoScrollDirection = 0 // -1 left, 0 none, +1 right
    // Position relative to the ScrollView's own coordinate space (i.e. independent
    // of scroll offset) -- used to keep tracking the drag while auto-scrolling
    // moves content under a stationary finger, when no new MotionEvent arrives.
    private var lastTouchXInScrollView: Float = 0f

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            val scrollView = scrollViewParent ?: return
            if (!isDraggingAnyHandle() || autoScrollDirection == 0) return

            scrollView.scrollBy(autoScrollDirection * autoScrollStepPx, 0)
            // Recompute the drag position using the new scroll offset, since the
            // finger hasn't necessarily moved (no fresh MotionEvent is coming).
            val newLocalX = lastTouchXInScrollView + scrollView.scrollX
            updateDragFromX(newLocalX)

            mainHandler.postDelayed(this, 16L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scrollViewParent = parent as? HorizontalScrollView
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(autoScrollRunnable)
    }

    fun setClips(newClips: List<Clip>) {
        clips = newClips
        requestLayout() // content width depends on clips -- must re-run onMeasure, not just redraw
        invalidate()
        newClips.forEach { loadThumbnailIfNeeded(it) }
    }

    fun setTextOverlays(overlays: List<TextOverlay>) {
        textOverlays = overlays
        invalidate()
    }

    fun setAudioTracks(tracks: List<AudioTrack>) {
        audioTracks = tracks
        invalidate()
    }

    fun setPlayheadMs(ms: Long) {
        playheadMs = ms
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalContentWidth = clips.sumOf { it.timelineDurationMs } * pxPerMs
        val minWidth = MeasureSpec.getSize(widthMeasureSpec)
        val desiredWidth = max(minWidth, totalContentWidth.toInt())

        val desiredHeight = (videoRowHeightPx + rowGapPx + textRowHeightPx + rowGapPx + musicRowHeightPx).toInt()
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val height = if (heightMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else desiredHeight

        setMeasuredDimension(desiredWidth, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawVideoRow(canvas)
        drawTextRow(canvas)
        drawMusicRow(canvas)

        val playheadX = playheadMs * pxPerMs
        canvas.drawLine(playheadX, 0f, playheadX, height.toFloat(), playheadPaint)
    }

    private fun drawVideoRow(canvas: Canvas) {
        var x = 0f
        val h = videoRowHeightPx

        for (clip in clips) {
            val width = clip.timelineDurationMs * pxPerMs
            val rect = RectF(x, 0f, x + width, h)

            val thumb = thumbnailCache[clip.id]
            if (thumb != null) {
                canvas.drawBitmap(thumb, null, rect, bitmapPaint)
            } else {
                canvas.drawRect(rect, clipPaint) // placeholder while thumbnail loads
            }
            canvas.drawLine(x + width, 0f, x + width, h, dividerPaint)

            if (clip.id == selectedClipId) {
                canvas.drawRect(RectF(x + 3f, 3f, x + width - 3f, h - 3f), selectedBorderPaint)
                canvas.drawRect(x, 0f, x + handleWidthPx, h, handlePaint)
                canvas.drawRect(x + width - handleWidthPx, 0f, x + width, h, handlePaint)
            }

            x += width
        }
    }

    /** Draws one colored segment per text overlay/sticker, selectable and trimmable like a clip. */
    private fun drawTextRow(canvas: Canvas) {
        val top = videoRowHeightPx + rowGapPx
        val bottom = top + textRowHeightPx
        canvas.drawRect(RectF(0f, top, width.toFloat(), bottom), trackBackgroundPaint)

        for (overlay in textOverlays) {
            val segStartX = overlay.startMs * pxPerMs
            val segEndX = overlay.endMs * pxPerMs
            val segRect = RectF(segStartX, top, segEndX, bottom)
            canvas.drawRect(segRect, textSegmentPaint)

            canvas.save()
            canvas.clipRect(segRect)
            canvas.drawText(overlay.text, segStartX + 4f, bottom - 8f, trackLabelPaint)
            canvas.restore()

            if (overlay.id == selectedOverlayId) {
                canvas.drawRect(RectF(segStartX + 2f, top + 2f, segEndX - 2f, bottom - 2f), selectedBorderPaint)
                canvas.drawRect(segStartX, top, segStartX + overlayHandleWidthPx, bottom, handlePaint)
                canvas.drawRect(segEndX - overlayHandleWidthPx, top, segEndX, bottom, handlePaint)
            }
        }
    }

    /** Draws a single segment showing the music track's span relative to the whole project. */
    private fun drawMusicRow(canvas: Canvas) {
        val top = videoRowHeightPx + rowGapPx + textRowHeightPx + rowGapPx
        val bottom = top + musicRowHeightPx
        canvas.drawRect(RectF(0f, top, width.toFloat(), bottom), trackBackgroundPaint)

        for (track in audioTracks) {
            val segStartX = track.timelineStartMs * pxPerMs
            val segEndX = (track.timelineStartMs + track.durationMs) * pxPerMs
            if (segEndX <= segStartX) continue

            val segRect = RectF(segStartX, top, segEndX, bottom)
            canvas.drawRect(segRect, musicSegmentPaint)

            canvas.save()
            canvas.clipRect(segRect)
            canvas.drawText("\u266A Music", segStartX + 4f, bottom - 8f, trackLabelPaint)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val textRowTop = videoRowHeightPx + rowGapPx
                val textRowBottom = textRowTop + textRowHeightPx

                when {
                    event.y <= videoRowHeightPx -> {
                        val hit = findHandleAt(event.x)
                        if (hit != null) {
                            draggingHandle = hit
                            parent.requestDisallowInterceptTouchEvent(true)
                            listener?.onTrimGestureStart()
                            updateLastTouchInScrollView(event.x)
                            return true
                        }
                        val clip = findClipAt(event.x)
                        if (clip != null) {
                            selectedClipId = clip.id
                            listener?.onClipSelected(clip.id)
                            invalidate()
                            return true
                        }
                    }
                    event.y in textRowTop..textRowBottom -> {
                        val overlayHit = findOverlayHandleAt(event.x)
                        if (overlayHit != null) {
                            draggingOverlayHandle = overlayHit
                            parent.requestDisallowInterceptTouchEvent(true)
                            listener?.onTrimGestureStart()
                            updateLastTouchInScrollView(event.x)
                            return true
                        }
                        val overlay = findOverlayAt(event.x)
                        if (overlay != null) {
                            selectedOverlayId = overlay.id
                            listener?.onTextOverlaySelected(overlay.id)
                            invalidate()
                            return true
                        }
                    }
                }
                listener?.onPlayheadMoved((event.x / pxPerMs).toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDraggingAnyHandle()) {
                    updateLastTouchInScrollView(event.x)
                    updateAutoScrollDirection(event.x)
                    updateDragFromX(event.x)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingAnyHandle()) {
                    listener?.onTrimGestureEnd()
                }
                draggingHandle = null
                draggingOverlayHandle = null
                stopAutoScroll()
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isDraggingAnyHandle(): Boolean = draggingHandle != null || draggingOverlayHandle != null

    /** Converts a local-view X into the ScrollView's own (scroll-offset-independent) coordinate space. */
    private fun updateLastTouchInScrollView(localX: Float) {
        val scrollX = scrollViewParent?.scrollX ?: 0
        lastTouchXInScrollView = localX - scrollX
    }

    /** Starts/stops/reverses auto-scroll based on how close the finger is to the visible edge. */
    private fun updateAutoScrollDirection(localX: Float) {
        val scrollView = scrollViewParent ?: return
        val visibleLeft = scrollView.scrollX
        val visibleRight = visibleLeft + scrollView.width

        val newDirection = when {
            localX < visibleLeft + edgeThresholdPx -> -1
            localX > visibleRight - edgeThresholdPx -> 1
            else -> 0
        }

        if (newDirection != 0 && autoScrollDirection == 0) {
            autoScrollDirection = newDirection
            mainHandler.post(autoScrollRunnable)
        } else if (newDirection == 0) {
            stopAutoScroll()
        } else {
            autoScrollDirection = newDirection
        }
    }

    private fun stopAutoScroll() {
        autoScrollDirection = 0
        mainHandler.removeCallbacks(autoScrollRunnable)
    }

    /** Dispatches to whichever kind of handle (clip or overlay) is currently being dragged. */
    private fun updateDragFromX(x: Float) {
        if (draggingHandle != null) updateClipTrimFromX(x)
        if (draggingOverlayHandle != null) updateOverlayTrimFromX(x)
    }

    private fun updateClipTrimFromX(x: Float) {
        val handle = draggingHandle ?: return
        val pixelDelta = x - handle.downX
        val msDelta = (pixelDelta / pxPerMs).toLong()

        val newStart: Long
        val newEnd: Long
        if (handle.isStart) {
            newStart = max(0L, min(handle.clip.trimEndMs - 100, handle.originalMs + msDelta))
            newEnd = handle.clip.trimEndMs
        } else {
            newStart = handle.clip.trimStartMs
            newEnd = max(handle.clip.trimStartMs + 100, handle.originalMs + msDelta)
        }
        listener?.onClipTrimmed(handle.clip.id, newStart, newEnd)
    }

    private fun updateOverlayTrimFromX(x: Float) {
        val handle = draggingOverlayHandle ?: return
        val pixelDelta = x - handle.downX
        val msDelta = (pixelDelta / pxPerMs).toLong()

        val newStart: Long
        val newEnd: Long
        if (handle.isStart) {
            newStart = max(0L, min(handle.overlay.endMs - 200, handle.originalMs + msDelta))
            newEnd = handle.overlay.endMs
        } else {
            newStart = handle.overlay.startMs
            newEnd = max(handle.overlay.startMs + 200, handle.originalMs + msDelta)
        }
        listener?.onTextOverlayTrimmed(handle.overlay.id, newStart, newEnd)
    }

    private fun clipStartX(target: Clip): Float {
        var x = 0f
        for (clip in clips) {
            if (clip.id == target.id) return x
            x += clip.timelineDurationMs * pxPerMs
        }
        return x
    }

    private fun findClipAt(xPos: Float): Clip? {
        var x = 0f
        for (clip in clips) {
            val width = clip.timelineDurationMs * pxPerMs
            if (xPos in x..(x + width)) return clip
            x += width
        }
        return null
    }

    private fun findHandleAt(xPos: Float): Handle? {
        val selected = clips.firstOrNull { it.id == selectedClipId } ?: return null
        val startX = clipStartX(selected)
        val width = selected.timelineDurationMs * pxPerMs
        return when {
            xPos in startX..(startX + handleWidthPx) -> Handle(selected, true, selected.trimStartMs, xPos)
            xPos in (startX + width - handleWidthPx)..(startX + width) -> Handle(selected, false, selected.trimEndMs, xPos)
            else -> null
        }
    }

    private fun findOverlayAt(xPos: Float): TextOverlay? {
        return textOverlays.firstOrNull { xPos in (it.startMs * pxPerMs)..(it.endMs * pxPerMs) }
    }

    private fun findOverlayHandleAt(xPos: Float): OverlayHandle? {
        val selected = textOverlays.firstOrNull { it.id == selectedOverlayId } ?: return null
        val startX = selected.startMs * pxPerMs
        val endX = selected.endMs * pxPerMs
        return when {
            xPos in startX..(startX + overlayHandleWidthPx) -> OverlayHandle(selected, true, selected.startMs, xPos)
            xPos in (endX - overlayHandleWidthPx)..endX -> OverlayHandle(selected, false, selected.endMs, xPos)
            else -> null
        }
    }

    /**
     * Extracts one representative frame (at the clip's trim-start point) as a
     * thumbnail bitmap, off the main thread. Skips work if we already have a
     * cached thumbnail generated from the same trim start.
     */
    private fun loadThumbnailIfNeeded(clip: Clip) {
        if (thumbnailTrimStamp[clip.id] == clip.trimStartMs && thumbnailCache.containsKey(clip.id)) return

        executor.execute {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, clip.sourceUri)
                val frameTimeUs = clip.trimStartMs * 1000
                val frame = retriever.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    mainHandler.post {
                        thumbnailCache[clip.id] = frame
                        thumbnailTrimStamp[clip.id] = clip.trimStartMs
                        invalidate()
                    }
                }
            } catch (_: Exception) {
                // Unsupported/unreadable source -- leave the placeholder color block in place.
            } finally {
                retriever.release()
            }
        }
    }
}
