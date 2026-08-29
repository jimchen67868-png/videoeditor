package com.example.videoeditor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.videoeditor.model.Clip
import kotlin.math.max
import kotlin.math.min

/**
 * Horizontal scrollable-free timeline strip showing clips proportionally to
 * their duration, with drag handles on the first/last visible clip's edges
 * for trimming. Thumbnails are intentionally out of scope here (would be
 * generated via MediaMetadataRetriever and cached as bitmaps per clip) --
 * this view focuses on layout + trim gesture handling, the part most specific
 * to this app's editing model.
 */
class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onClipTrimmed(clipId: String, newTrimStartMs: Long, newTrimEndMs: Long)
        fun onClipSelected(clipId: String)
        fun onPlayheadMoved(positionMs: Long)
    }

    var listener: Listener? = null

    private var clips: List<Clip> = emptyList()
    private var selectedClipId: String? = null
    private var pxPerMs: Float = 0.05f // zoom level; adjust for desired timeline density

    private val clipPaint = Paint().apply { color = Color.parseColor("#3A3A3C") }
    private val selectedClipPaint = Paint().apply { color = Color.parseColor("#FF7A00") }
    private val dividerPaint = Paint().apply { color = Color.parseColor("#1C1C1E"); strokeWidth = 4f }
    private val handlePaint = Paint().apply { color = Color.WHITE }
    private val playheadPaint = Paint().apply { color = Color.parseColor("#FFD60A"); strokeWidth = 6f }

    private var playheadMs: Long = 0L

    private var draggingHandle: Handle? = null
    private data class Handle(val clip: Clip, val isStart: Boolean, val originalMs: Long)

    private val handleWidthPx = 32f

    fun setClips(newClips: List<Clip>) {
        clips = newClips
        invalidate()
    }

    fun setPlayheadMs(ms: Long) {
        playheadMs = ms
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        var x = 0f
        val height = height.toFloat()

        for (clip in clips) {
            val width = clip.timelineDurationMs * pxPerMs
            val rect = RectF(x, 0f, x + width, height)
            canvas.drawRect(rect, if (clip.id == selectedClipId) selectedClipPaint else clipPaint)
            canvas.drawLine(x + width, 0f, x + width, height, dividerPaint)

            if (clip.id == selectedClipId) {
                canvas.drawRect(x, 0f, x + handleWidthPx, height, handlePaint)
                canvas.drawRect(x + width - handleWidthPx, 0f, x + width, height, handlePaint)
            }

            x += width
        }

        val playheadX = playheadMs * pxPerMs
        canvas.drawLine(playheadX, 0f, playheadX, height, playheadPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findHandleAt(event.x)
                if (hit != null) {
                    draggingHandle = hit
                    return true
                }
                val clip = findClipAt(event.x)
                if (clip != null) {
                    selectedClipId = clip.id
                    listener?.onClipSelected(clip.id)
                    invalidate()
                    return true
                }
                // Scrub playhead
                listener?.onPlayheadMoved((event.x / pxPerMs).toLong())
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                draggingHandle?.let { handle ->
                    val deltaMs = ((event.x - clipStartX(handle.clip)) / pxPerMs).toLong()
                    val newStart: Long
                    val newEnd: Long
                    if (handle.isStart) {
                        newStart = max(0L, min(handle.clip.trimEndMs - 100, deltaMsAbsolute(handle, deltaMs, true)))
                        newEnd = handle.clip.trimEndMs
                    } else {
                        newStart = handle.clip.trimStartMs
                        newEnd = max(handle.clip.trimStartMs + 100, deltaMsAbsolute(handle, deltaMs, false))
                    }
                    listener?.onClipTrimmed(handle.clip.id, newStart, newEnd)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingHandle = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun deltaMsAbsolute(handle: Handle, deltaMs: Long, isStart: Boolean): Long {
        // Translates a drag position back into an absolute trim point on the source clip.
        return if (isStart) handle.originalMs + deltaMs else handle.originalMs + deltaMs
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
            xPos in startX..(startX + handleWidthPx) -> Handle(selected, true, selected.trimStartMs)
            xPos in (startX + width - handleWidthPx)..(startX + width) -> Handle(selected, false, selected.trimEndMs)
            else -> null
        }
    }
}
