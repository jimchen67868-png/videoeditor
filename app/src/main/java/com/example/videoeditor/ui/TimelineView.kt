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
import com.example.videoeditor.model.ImageOverlay
import com.example.videoeditor.model.TextOverlay
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-track timeline strip:
 *   - video row: clips proportional to duration, with drag handles for
 *     trimming and real video-frame thumbnails.
 *   - one stacked lane PER text overlay (covers "stickers" too, which are
 *     large-emoji TextOverlays under the hood), PER music track, and PER
 *     image overlay -- matching CapCut's one-row-per-item layout. Every
 *     lane is independently selectable by tap and trimmable via the same
 *     drag-handle mechanism as video clips.
 *
 * Internally these three item kinds share one generic lane-layout/hit-test
 * path (see [TrackItem]) to avoid tripling the same code, while the public
 * [Listener] still exposes one pair of callbacks per kind for clarity at
 * the call site.
 *
 * Must be hosted directly inside a HorizontalScrollView for content wider
 * than the screen to be reachable -- this view reports its true content
 * width AND height via onMeasure (height grows with the number of items in
 * any lane group), AND auto-scrolls the HorizontalScrollView when a trim
 * handle is dragged near the visible edge.
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
        fun onAudioTrackSelected(trackId: String) {}
        fun onAudioTrackTrimmed(trackId: String, newTimelineStartMs: Long, newDurationMs: Long) {}
        fun onImageOverlaySelected(overlayId: String) {}
        fun onImageOverlayTrimmed(overlayId: String, newStartMs: Long, newEndMs: Long) {}
    }

    var listener: Listener? = null

    private enum class TrackKind { TEXT, MUSIC, IMAGE }

    /** Generic lane item used for layout/hit-testing across all three overlay-like tracks. */
    private data class TrackItem(val id: String, val startMs: Long, val endMs: Long, val label: String, val kind: TrackKind)

    private var clips: List<Clip> = emptyList()
    private var audioTracks: List<AudioTrack> = emptyList()
    private var textOverlays: List<TextOverlay> = emptyList()
    private var imageOverlays: List<ImageOverlay> = emptyList()
    private var selectedClipId: String? = null
    private var selectedItemId: String? = null // selection is shared across text/music/image since IDs are UUIDs (never collide)
    private var pxPerMs: Float = 0.05f // zoom level; adjust for desired timeline density

    /** All lane items in draw/hit-test order: text overlays, then music tracks, then image overlays. */
    private fun buildTrackItems(): List<TrackItem> {
        val items = mutableListOf<TrackItem>()
        textOverlays.forEach { items += TrackItem(it.id, it.startMs, it.endMs, it.text, TrackKind.TEXT) }
        audioTracks.forEach { items += TrackItem(it.id, it.timelineStartMs, it.timelineStartMs + it.durationMs, "\u266A Music", TrackKind.MUSIC) }
        imageOverlays.forEach { items += TrackItem(it.id, it.startMs, it.endMs, "\uD83D\uDDBC Image", TrackKind.IMAGE) }
        return items
    }

    // --- Row heights ---
    private val videoRowHeightPx = 80f * resources.displayMetrics.density
    private val laneHeightPx = 32f * resources.displayMetrics.density // one per lane item
    private val laneGapPx = 2f * resources.displayMetrics.density
    private val rowGapPx = 2f * resources.displayMetrics.density

    private val clipPaint = Paint().apply { color = Color.parseColor("#3A3A3C") }
    private val selectedBorderPaint = Paint().apply {
        color = Color.parseColor("#FFFFFF")
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
    private val imageSegmentPaint = Paint().apply { color = Color.parseColor("#AB47BC") }
    private val trackLabelPaint = Paint().apply {
        color = Color.WHITE
        isAntiAlias = true
        textSize = 11f * resources.displayMetrics.density
    }

    private fun segmentPaintFor(kind: TrackKind): Paint = when (kind) {
        TrackKind.TEXT -> textSegmentPaint
        TrackKind.MUSIC -> musicSegmentPaint
        TrackKind.IMAGE -> imageSegmentPaint
    }

    private var playheadMs: Long = 0L

    // Handle hit target sized in dp so it's actually grabbable on high-density screens.
    private val handleWidthPx = 24f * resources.displayMetrics.density
    // Lane items are shorter than the video row, so their handles are a bit
    // narrower to still leave room to tap the segment itself.
    private val laneHandleWidthPx = 16f * resources.displayMetrics.density

    private var draggingHandle: Handle? = null
    private data class Handle(val clip: Clip, val isStart: Boolean, val originalMs: Long, val downX: Float)

    private var draggingLaneHandle: LaneHandle? = null
    private data class LaneHandle(val item: TrackItem, val isStart: Boolean, val originalMs: Long, val downX: Float)

    // Thumbnail loading: one representative frame per clip, generated off
    // the main thread and cached by clip id. Re-generated only when a
    // clip's source or trim start changes (see loadThumbnailIfNeeded).
    private val thumbnailCache = mutableMapOf<String, Bitmap>()
    private val thumbnailTrimStamp = mutableMapOf<String, Long>()
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- Auto-scroll while dragging a handle (clip or lane item) near the visible edge ---
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
        requestLayout() // number of lanes (and thus total height) depends on item count
        invalidate()
    }

    fun setAudioTracks(tracks: List<AudioTrack>) {
        audioTracks = tracks
        requestLayout()
        invalidate()
    }

    fun setImageOverlays(overlays: List<ImageOverlay>) {
        imageOverlays = overlays
        requestLayout()
        invalidate()
    }

    fun setPlayheadMs(ms: Long) {
        playheadMs = ms
        invalidate()
    }

    private fun lanesTop(): Float = videoRowHeightPx + rowGapPx

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalContentWidth = clips.sumOf { it.timelineDurationMs } * pxPerMs
        val minWidth = MeasureSpec.getSize(widthMeasureSpec)
        val desiredWidth = max(minWidth, totalContentWidth.toInt())

        val itemCount = buildTrackItems().size
        val lanesHeight = if (itemCount == 0) 0f else itemCount * laneHeightPx + (itemCount - 1) * laneGapPx
        val desiredHeight = (lanesTop() + lanesHeight).toInt()
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val height = if (heightMode == MeasureSpec.EXACTLY) MeasureSpec.getSize(heightMeasureSpec) else desiredHeight

        setMeasuredDimension(desiredWidth, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawVideoRow(canvas)
        drawLanes(canvas)

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

    /** Draws one lane per text overlay/sticker, music track, and image overlay -- each independently selectable and trimmable. */
    private fun drawLanes(canvas: Canvas) {
        val items = buildTrackItems()
        if (items.isEmpty()) return
        val top0 = lanesTop()

        items.forEachIndexed { index, item ->
            val top = top0 + index * (laneHeightPx + laneGapPx)
            val bottom = top + laneHeightPx
            canvas.drawRect(RectF(0f, top, width.toFloat(), bottom), trackBackgroundPaint)

            val segStartX = item.startMs * pxPerMs
            val segEndX = item.endMs * pxPerMs
            val segRect = RectF(segStartX, top, segEndX, bottom)
            canvas.drawRect(segRect, segmentPaintFor(item.kind))

            canvas.save()
            canvas.clipRect(segRect)
            canvas.drawText(item.label, segStartX + 4f, bottom - 8f, trackLabelPaint)
            canvas.restore()

            if (item.id == selectedItemId) {
                canvas.drawRect(RectF(segStartX + 2f, top + 2f, segEndX - 2f, bottom - 2f), selectedBorderPaint)
                canvas.drawRect(segStartX, top, segStartX + laneHandleWidthPx, bottom, handlePaint)
                canvas.drawRect(segEndX - laneHandleWidthPx, top, segEndX, bottom, handlePaint)
            }
        }
    }

    /** Returns the lane item whose row contains [y], or null if none does. */
    private fun itemAtY(y: Float): TrackItem? {
        val items = buildTrackItems()
        if (items.isEmpty()) return null
        val relativeY = y - lanesTop()
        if (relativeY < 0) return null
        val laneStride = laneHeightPx + laneGapPx
        val index = (relativeY / laneStride).toInt()
        if (index !in items.indices) return null
        val laneTop = index * laneStride
        return if (relativeY in laneTop..(laneTop + laneHeightPx)) items[index] else null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val laneItem = itemAtY(event.y)

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
                    laneItem != null -> {
                        val laneHit = findLaneHandleAt(event.x, laneItem)
                        if (laneHit != null) {
                            draggingLaneHandle = laneHit
                            parent.requestDisallowInterceptTouchEvent(true)
                            listener?.onTrimGestureStart()
                            updateLastTouchInScrollView(event.x)
                            return true
                        }
                        val segStartX = laneItem.startMs * pxPerMs
                        val segEndX = laneItem.endMs * pxPerMs
                        if (event.x in segStartX..segEndX) {
                            selectedItemId = laneItem.id
                            when (laneItem.kind) {
                                TrackKind.TEXT -> listener?.onTextOverlaySelected(laneItem.id)
                                TrackKind.MUSIC -> listener?.onAudioTrackSelected(laneItem.id)
                                TrackKind.IMAGE -> listener?.onImageOverlaySelected(laneItem.id)
                            }
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
                draggingLaneHandle = null
                stopAutoScroll()
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isDraggingAnyHandle(): Boolean = draggingHandle != null || draggingLaneHandle != null

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

    /** Dispatches to whichever kind of handle (clip or lane item) is currently being dragged. */
    private fun updateDragFromX(x: Float) {
        if (draggingHandle != null) updateClipTrimFromX(x)
        if (draggingLaneHandle != null) updateLaneTrimFromX(x)
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

    private fun updateLaneTrimFromX(x: Float) {
        val handle = draggingLaneHandle ?: return
        val pixelDelta = x - handle.downX
        val msDelta = (pixelDelta / pxPerMs).toLong()

        val newStart: Long
        val newEnd: Long
        if (handle.isStart) {
            newStart = max(0L, min(handle.item.endMs - 200, handle.originalMs + msDelta))
            newEnd = handle.item.endMs
        } else {
            newStart = handle.item.startMs
            newEnd = max(handle.item.startMs + 200, handle.originalMs + msDelta)
        }

        when (handle.item.kind) {
            TrackKind.TEXT -> listener?.onTextOverlayTrimmed(handle.item.id, newStart, newEnd)
            // AudioTrack's model uses (timelineStartMs, durationMs) rather than
            // (startMs, endMs) directly -- convert at the boundary.
            TrackKind.MUSIC -> listener?.onAudioTrackTrimmed(handle.item.id, newStart, newEnd - newStart)
            TrackKind.IMAGE -> listener?.onImageOverlayTrimmed(handle.item.id, newStart, newEnd)
        }
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

    /** Checks for a trim handle hit on [item]'s own lane -- only meaningful if it's the selected one. */
    private fun findLaneHandleAt(xPos: Float, item: TrackItem): LaneHandle? {
        if (item.id != selectedItemId) return null
        val startX = item.startMs * pxPerMs
        val endX = item.endMs * pxPerMs
        return when {
            xPos in startX..(startX + laneHandleWidthPx) -> LaneHandle(item, true, item.startMs, xPos)
            xPos in (endX - laneHandleWidthPx)..endX -> LaneHandle(item, false, item.endMs, xPos)
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
