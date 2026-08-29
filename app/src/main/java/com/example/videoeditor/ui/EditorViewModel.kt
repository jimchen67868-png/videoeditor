package com.example.videoeditor.ui

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.videoeditor.model.AudioTrack
import com.example.videoeditor.model.Clip
import com.example.videoeditor.model.FilterType
import com.example.videoeditor.model.Project
import com.example.videoeditor.model.TextOverlay
import com.example.videoeditor.model.TransitionType
import java.util.UUID

/**
 * Holds the current [Project] as the single source of truth for the editor screen,
 * plus undo/redo history as a simple snapshot stack.
 *
 * Every mutation goes through [applyUpdate], which pushes the pre-change state
 * onto the undo stack and clears the redo stack (standard undo/redo semantics:
 * making a new edit after undoing invalidates the "future" you undid away from).
 * This is a straightforward but memory-heavier approach than diff-based undo --
 * fine at this project's scale (a handful of clips), worth revisiting if
 * projects grow to have many clips with large per-clip data.
 */
class EditorViewModel : ViewModel() {

    private val _project = MutableLiveData(Project(id = UUID.randomUUID().toString(), name = "Untitled"))
    val project: LiveData<Project> = _project

    private val _selectedClipId = MutableLiveData<String?>(null)
    val selectedClipId: LiveData<String?> = _selectedClipId

    private val _exportProgress = MutableLiveData<Int>(-1)
    val exportProgress: LiveData<Int> = _exportProgress

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()
    private val maxHistorySize = 50

    private val _canUndo = MutableLiveData(false)
    val canUndo: LiveData<Boolean> = _canUndo

    private val _canRedo = MutableLiveData(false)
    val canRedo: LiveData<Boolean> = _canRedo

    fun addClip(uri: Uri, durationMs: Long) {
        val newClip = Clip(
            id = UUID.randomUUID().toString(),
            sourceUri = uri,
            trimStartMs = 0L,
            trimEndMs = durationMs
        )
        applyUpdate { it.copy(clips = it.clips + newClip) }
    }

    fun removeClip(clipId: String) {
        applyUpdate { it.copy(clips = it.clips.filterNot { c -> c.id == clipId }) }
    }

    fun reorderClip(fromIndex: Int, toIndex: Int) {
        applyUpdate { current ->
            val mutable = current.clips.toMutableList()
            if (fromIndex !in mutable.indices || toIndex !in mutable.indices) return@applyUpdate current
            val item = mutable.removeAt(fromIndex)
            mutable.add(toIndex, item)
            current.copy(clips = mutable)
        }
    }

    fun trimClip(clipId: String, newStartMs: Long, newEndMs: Long) {
        updateClip(clipId) { it.copy(trimStartMs = newStartMs, trimEndMs = newEndMs) }
    }

    /**
     * Applies a trim update WITHOUT pushing undo history -- used while a drag
     * gesture is in progress (fires many times per second). Call
     * [beginBatchEdit] at drag-start and [endBatchEdit] at drag-end to
     * collapse the whole gesture into a single undo step.
     */
    fun trimClipLive(clipId: String, newStartMs: Long, newEndMs: Long) {
        val current = _project.value ?: return
        _project.value = current.copy(
            clips = current.clips.map {
                if (it.id == clipId) it.copy(trimStartMs = newStartMs, trimEndMs = newEndMs) else it
            }
        )
    }

    private var batchEditBaseline: Project? = null

    /** Captures the current state before a multi-step gesture (e.g. a trim drag) begins. */
    fun beginBatchEdit() {
        batchEditBaseline = _project.value
    }

    /** Pushes the captured baseline onto undo history if the gesture actually changed anything. */
    fun endBatchEdit() {
        val baseline = batchEditBaseline ?: return
        val current = _project.value
        if (current != null && baseline != current) {
            undoStack.addLast(baseline)
            if (undoStack.size > maxHistorySize) undoStack.removeFirst()
            redoStack.clear()
            updateHistoryFlags()
        }
        batchEditBaseline = null
    }

    fun setClipFilter(clipId: String, filter: FilterType) {
        updateClip(clipId) { it.copy(filter = filter) }
    }

    fun setClipSpeed(clipId: String, speed: Float) {
        updateClip(clipId) { it.copy(speed = speed.coerceIn(0.25f, 4f)) }
    }

    fun setClipTransition(clipId: String, transition: TransitionType, durationMs: Long = 500L) {
        updateClip(clipId) { it.copy(transitionToNext = transition, transitionDurationMs = durationMs) }
    }

    fun addTextOverlay(clipId: String, overlay: TextOverlay) {
        updateClip(clipId) { it.copy(textOverlays = it.textOverlays + overlay) }
    }

    fun removeTextOverlay(clipId: String, overlayId: String) {
        updateClip(clipId) { clip ->
            clip.copy(textOverlays = clip.textOverlays.filterNot { it.id == overlayId })
        }
    }

    fun setAudioTrack(uri: Uri) {
        applyUpdate { it.copy(audioTrack = AudioTrack(sourceUri = uri)) }
    }

    fun setAudioTrackVolume(volume: Float) {
        applyUpdate { current ->
            val track = current.audioTrack ?: return@applyUpdate current
            current.copy(audioTrack = track.copy(volume = volume.coerceIn(0f, 1f)))
        }
    }

    fun removeAudioTrack() {
        applyUpdate { it.copy(audioTrack = null) }
    }

    fun selectClip(clipId: String?) {
        // Selection is UI state, not an edit -- doesn't go through undo history.
        _selectedClipId.value = clipId
    }

    fun setExportProgress(progress: Int) {
        _exportProgress.value = progress
    }

    /** Reverts to the previous project state. No-op if there's no history. */
    fun undo() {
        val current = _project.value ?: return
        val previous = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(current)
        _project.value = previous
        updateHistoryFlags()
    }

    /** Re-applies a state that was undone. No-op if there's nothing to redo. */
    fun redo() {
        val current = _project.value ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        _project.value = next
        updateHistoryFlags()
    }

    private inline fun updateClip(clipId: String, crossinline transform: (Clip) -> Clip) {
        applyUpdate { current ->
            current.copy(clips = current.clips.map { if (it.id == clipId) transform(it) else it })
        }
    }

    /**
     * Applies [transform] to the current project, pushing the pre-change
     * state onto the undo stack first and clearing any redo history (a new
     * edit invalidates whatever "future" redo would have restored).
     */
    private inline fun applyUpdate(transform: (Project) -> Project) {
        val current = _project.value ?: return
        val updated = transform(current)
        if (updated == current) return // no-op edits shouldn't clutter history

        undoStack.addLast(current)
        if (undoStack.size > maxHistorySize) undoStack.removeFirst()
        redoStack.clear()

        _project.value = updated
        updateHistoryFlags()
    }

    private fun updateHistoryFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }
}
