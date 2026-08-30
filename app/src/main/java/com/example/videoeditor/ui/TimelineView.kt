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
import com.example.videoeditor.model.Clip
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * Horizontal timeline strip showing clips proportionally to their duration,
 * with drag handles on the selected clip's edges for trimming, and a real
 * video-frame thumbnail per clip (loaded off the main thread and cached).
 *
 * Must be hosted directly inside a HorizontalScrollView for clips wider than
 * the screen to be reachable -- this view reports its true content width via
 * onMeasure so the scroll container knows how far it can scroll, AND
 * auto-scrolls that container itself when a trim handle is dragged near the
 * visible edge, so long clips can be trimmed all the way to their true end
 * without the user needing a separate swipe-to-scroll step mid-drag.
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
    }

    var listener: Listener? = null

    private var clips: List<Clip> = emptyList()
    private var selectedClipId: String? = null
    private var pxPerMs: Float = 0.05f // zoom level; adjust for desired timeline density

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

    private var playheadMs: Long = 0L

    // Handle hit target sized in dp so it's actually grabbable on high-density screens.
    private val handleWidthPx = 24f * resources.displayMetrics.density

    private var draggingHandle: Handle? = null
    private data class Handle(val clip: Clip, val isStart: Boolean, val originalMs: Long, val downX: Float)

    // Thumbnail loading: one representative frame per clip, generated off
    // the main thread and cached by clip id. Re-generated only when a
    // clip's source or trim start changes (see loadThumbnailIfNeeded).
    private val thumbnailCache = mutableMapOf<String, Bitmap>()
    private val thumbnailTrimStamp = mutableMapOf<String, Long>()
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    // --- Auto-scroll while dragging a handle near the visible edge ---
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
            if (draggingHandle == null || autoScrollDirection == 0) return

            scrollView.scrollBy(autoScrollDirection * autoScrollStepPx, 0)
            // Recompute the drag position using the new scroll offset, since the
            // finger hasn't necessarily moved (no fresh MotionEvent is coming).
            val newLocalX = lastTouchXInScrollView + scrollView.scrollX
            updateTrimFromX(newLocalX)

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
        invalidate()
        newClips.forEach { loadThumbnailIfNeeded(it) }
    }

    fun setPlayheadMs(ms: Long) {
        playheadMs = ms
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalContentWidth = clips.sumOf { it.timelineDurationMs } * pxPerMs
        val minWidth = MeasureSpec.getSize(widthMeasureSpec)
        val desiredWidth = max(minWidth, totalContentWidth.toInt())
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(desiredWidth, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var x = 0f
        val h = height.toFloat()

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

        val playheadX = playheadMs * pxPerMs
        canvas.drawLine(playheadX, 0f, playheadX, h, playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
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
                listener?.onPlayheadMoved((event.x / pxPerMs).toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (draggingHandle != null) {
                    updateLastTouchInScrollView(event.x)
                    updateAutoScrollDirection(event.x)
                    updateTrimFromX(event.x)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (draggingHandle != null) {
                    listener?.onTrimGestureEnd()
                }
                draggingHandle = null
                stopAutoScroll()
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

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

    /** Shared trim-calculation logic, used both by real touch moves and by the auto-scroll tick. */
    private fun updateTrimFromX(x: Float) {
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
