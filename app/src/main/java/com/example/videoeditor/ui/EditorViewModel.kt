package com.example.videoeditor.ui

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.videoeditor.model.Clip
import com.example.videoeditor.model.FilterType
import com.example.videoeditor.model.Project
import com.example.videoeditor.model.TextOverlay
import com.example.videoeditor.model.TransitionType
import java.util.UUID

/**
 * Holds the current [Project] as the single source of truth for the editor screen.
 * TimelineView, the preview player, and the export flow all read from / mutate
 * this state via the exposed methods below rather than touching Project directly,
 * so undo/redo or persistence can be added later without touching the UI layer.
 */
class EditorViewModel : ViewModel() {

    private val _project = MutableLiveData(Project(id = UUID.randomUUID().toString(), name = "Untitled"))
    val project: LiveData<Project> = _project

    private val _selectedClipId = MutableLiveData<String?>(null)
    val selectedClipId: LiveData<String?> = _selectedClipId

    private val _exportProgress = MutableLiveData<Int>(-1)
    val exportProgress: LiveData<Int> = _exportProgress

    fun addClip(uri: Uri, durationMs: Long) {
        val current = _project.value ?: return
        val newClip = Clip(
            id = UUID.randomUUID().toString(),
            sourceUri = uri,
            trimStartMs = 0L,
            trimEndMs = durationMs
        )
        _project.value = current.copy(clips = current.clips + newClip)
    }

    fun removeClip(clipId: String) {
        val current = _project.value ?: return
        _project.value = current.copy(clips = current.clips.filterNot { it.id == clipId })
    }

    fun reorderClip(fromIndex: Int, toIndex: Int) {
        val current = _project.value ?: return
        val mutable = current.clips.toMutableList()
        if (fromIndex !in mutable.indices || toIndex !in mutable.indices) return
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        _project.value = current.copy(clips = mutable)
    }

    fun trimClip(clipId: String, newStartMs: Long, newEndMs: Long) {
        updateClip(clipId) { it.copy(trimStartMs = newStartMs, trimEndMs = newEndMs) }
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

    fun selectClip(clipId: String?) {
        _selectedClipId.value = clipId
    }

    fun setExportProgress(progress: Int) {
        _exportProgress.value = progress
    }

    fun setAudioTrack(uri: Uri) {
        val current = _project.value ?: return
        _project.value = current.copy(audioTrack = com.example.videoeditor.model.AudioTrack(sourceUri = uri))
    }

    fun setAudioTrackVolume(volume: Float) {
        val current = _project.value ?: return
        val track = current.audioTrack ?: return
        _project.value = current.copy(audioTrack = track.copy(volume = volume.coerceIn(0f, 1f)))
    }

    fun removeAudioTrack() {
        val current = _project.value ?: return
        _project.value = current.copy(audioTrack = null)
    }

    private inline fun updateClip(clipId: String, transform: (Clip) -> Clip) {
        val current = _project.value ?: return
        _project.value = current.copy(
            clips = current.clips.map { if (it.id == clipId) transform(it) else it }
        )
    }
}
