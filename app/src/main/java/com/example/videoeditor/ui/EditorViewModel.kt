package com.example.videoeditor.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.videoeditor.data.ProjectRepository
import com.example.videoeditor.model.AudioTrack
import com.example.videoeditor.model.Clip
import com.example.videoeditor.model.FilterType
import com.example.videoeditor.model.Project
import com.example.videoeditor.model.TextOverlay
import com.example.videoeditor.model.TransitionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Holds the current [Project] as the single source of truth for the editor screen,
 * plus undo/redo history as a simple snapshot stack, plus persistence.
 *
 * Persistence: on every edit, a debounced auto-save writes the project to Room
 * (as a JSON blob -- see data/ProjectDatabase.kt) so work survives the app
 * being killed by the system, which is common on Android when backgrounded.
 * On creation, the most recently saved project is loaded automatically. This
 * scaffold only tracks one ongoing project (a draft), not a project library.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ProjectRepository(application)

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

    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val saveRunnable = Runnable {
        val current = _project.value ?: return@Runnable
        viewModelScope.launch(Dispatchers.IO) { repository.save(current) }
    }
    private val saveDebounceMs = 800L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = repository.loadMostRecent()
            if (loaded != null) {
                _project.postValue(loaded)
            }
        }
    }

    private fun scheduleAutoSave() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, saveDebounceMs)
    }

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

    /** Deletes the currently selected clip, if any, and clears the selection. */
    fun deleteSelectedClip() {
        val clipId = _selectedClipId.value ?: return
        removeClip(clipId)
        _selectedClipId.value = null
    }

    private var clipboardClip: Clip? = null

    private val _canPaste = MutableLiveData(false)
    val canPaste: LiveData<Boolean> = _canPaste

    /** Copies the currently selected clip's settings (filter, trim, speed, etc.) to an in-memory clipboard. */
    fun copySelectedClip() {
        val clipId = _selectedClipId.value ?: return
        val clip = _project.value?.clips?.firstOrNull { it.id == clipId } ?: return
        clipboardClip = clip
        _canPaste.value = true
    }

    /**
     * Pastes a duplicate of the copied clip (new id, same source/trim/filter/
     * speed/text overlays) right after the currently selected clip, or at the
     * end of the timeline if nothing is selected.
     */
    fun pasteClip() {
        val source = clipboardClip ?: return
        // Text overlays are now project-level (global timeline positions), so
        // pasting a clip doesn't duplicate any overlays -- they weren't
        // attached to the clip in the first place.
        val newClip = source.copy(id = UUID.randomUUID().toString())
        applyUpdate { current ->
            val mutable = current.clips.toMutableList()
            val insertAfterIndex = mutable.indexOfFirst { it.id == _selectedClipId.value }
            if (insertAfterIndex >= 0) {
                mutable.add(insertAfterIndex + 1, newClip)
            } else {
                mutable.add(newClip)
            }
            current.copy(clips = mutable)
        }
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
        scheduleAutoSave()
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

    fun addTextOverlay(overlay: TextOverlay) {
        applyUpdate { it.copy(textOverlays = it.textOverlays + overlay) }
    }

    fun removeTextOverlay(overlayId: String) {
        applyUpdate { it.copy(textOverlays = it.textOverlays.filterNot { o -> o.id == overlayId }) }
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
        scheduleAutoSave()
    }

    /** Re-applies a state that was undone. No-op if there's nothing to redo. */
    fun redo() {
        val current = _project.value ?: return
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(current)
        _project.value = next
        updateHistoryFlags()
        scheduleAutoSave()
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
        scheduleAutoSave()
    }

    private fun updateHistoryFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }
}
