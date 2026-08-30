package com.example.videoeditor.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.videoeditor.databinding.ActivityEditorBinding
import com.example.videoeditor.export.ExportRequestHolder
import com.example.videoeditor.export.ExportWorkerService
import java.io.File

/**
 * Main (and for this scaffold, only) screen: video import, preview playback,
 * timeline editing, and triggering export.
 *
 * NOTE: this build includes Toast messages at each interaction point
 * (select / trim / export tap / export result) purely so behavior can be
 * verified on-device without adb/logcat access. Remove them once things
 * are confirmed working end to end.
 */
@OptIn(UnstableApi::class)
class EditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEditorBinding
    private val viewModel: EditorViewModel by viewModels()
    private lateinit var player: ExoPlayer

    // Polls playback position while the activity is visible so the timeline
    // playhead line and the time label stay in sync with actual playback,
    // not just with manual scrubbing.
    private val positionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val positionUpdater = object : Runnable {
        override fun run() {
            updatePlayheadAndTimeLabel()
            positionHandler.postDelayed(this, 200L)
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onVideoPicked(it) }
    }

    private val pickMusic = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            runCatching {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.setAudioTrack(it)
            Toast.makeText(this, "Music added", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    private val pickExportDestination =
        registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
            if (uri != null) {
                runExport(uri)
            } else {
                Toast.makeText(this, "Export cancelled -- no destination chosen", Toast.LENGTH_SHORT).show()
            }
        }

    private val exportResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(ExportWorkerService.EXTRA_RESULT_SUCCESS, false)
            val message = intent.getStringExtra(ExportWorkerService.EXTRA_RESULT_MESSAGE) ?: ""
            Toast.makeText(
                this@EditorActivity,
                if (success) "Export finished: $message" else "Export failed: $message",
                Toast.LENGTH_LONG
            ).show()
            viewModel.setExportProgress(-1)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        player = ExoPlayer.Builder(this).build()
        binding.previewPlayerView.player = player
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                applyLiveEffectsForCurrentItem()
            }
        })

        binding.timelineView.listener = object : TimelineView.Listener {
            override fun onClipTrimmed(clipId: String, newTrimStartMs: Long, newTrimEndMs: Long) {
                // Called continuously while dragging -- use the "live" update
                // that doesn't spam undo history; beginBatchEdit/endBatchEdit
                // (below) collapse the whole drag into one undo step.
                viewModel.trimClipLive(clipId, newTrimStartMs, newTrimEndMs)
            }

            override fun onClipSelected(clipId: String) {
                viewModel.selectClip(clipId)
                Toast.makeText(this@EditorActivity, "Clip selected", Toast.LENGTH_SHORT).show()
            }

            override fun onPlayheadMoved(positionMs: Long) {
                player.seekTo(positionMs)
            }

            override fun onTrimGestureStart() {
                viewModel.beginBatchEdit()
            }

            override fun onTrimGestureEnd() {
                viewModel.endBatchEdit()
            }
        }

        binding.addClipButton.setOnClickListener {
            Toast.makeText(this, "Opening video picker...", Toast.LENGTH_SHORT).show()
            pickVideo.launch("video/*")
        }
        binding.exportButton.setOnClickListener { startExport() }

        binding.addMusicButton.setOnClickListener { pickMusic.launch("audio/*") }
        binding.removeMusicButton.setOnClickListener {
            viewModel.removeAudioTrack()
            Toast.makeText(this, "Music removed", Toast.LENGTH_SHORT).show()
        }

        binding.undoButton.setOnClickListener { viewModel.undo() }
        binding.redoButton.setOnClickListener { viewModel.redo() }

        viewModel.canUndo.observe(this) { binding.undoButton.isEnabled = it }
        viewModel.canRedo.observe(this) { binding.redoButton.isEnabled = it }
        viewModel.selectedClipId.observe(this) { updateTransitionButtonLabel() }

        binding.filterGrayscaleButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.GRAYSCALE) }
        binding.filterSepiaButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.SEPIA) }
        binding.filterNoneButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.NONE) }
        binding.filterVividButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.VIVID) }
        binding.filterCoolButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.COOL) }
        binding.filterWarmButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.WARM) }
        binding.filterInvertButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.INVERT) }
        binding.filterNoirButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.NOIR) }
        binding.filterFadeButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.FADE) }
        binding.filterDramaticButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.DRAMATIC) }
        binding.filterPastelButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.PASTEL) }
        binding.filterNightButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.NIGHT) }

        binding.addTextButton.setOnClickListener { showAddTextDialog() }
        binding.removeTextButton.setOnClickListener { removeLastTextOverlayFromSelected() }
        binding.toggleTransitionButton.setOnClickListener { toggleTransitionOnSelected() }

        viewModel.project.observe(this) { project ->
            binding.timelineView.setClips(project.clips)
            rebuildPreviewPlaylist(project)

            val track = project.audioTrack
            binding.musicTrackLabel.text = if (track != null) {
                queryDisplayName(track.sourceUri) ?: "Music track added"
            } else {
                "No music"
            }
            binding.removeMusicButton.visibility = if (track != null) android.view.View.VISIBLE else android.view.View.GONE
            updateTransitionButtonLabel()
        }

        viewModel.exportProgress.observe(this) { progress ->
            binding.exportProgressBar.progress = progress.coerceIn(0, 100)
            binding.exportProgressBar.visibility =
                if (progress in 0..99) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ExportWorkerService.ACTION_EXPORT_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exportResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(exportResultReceiver, filter)
        }
        positionHandler.post(positionUpdater)
    }

    override fun onStop() {
        super.onStop()
        player.pause()
        positionHandler.removeCallbacks(positionUpdater)
        runCatching { unregisterReceiver(exportResultReceiver) }
    }

    /**
     * Converts ExoPlayer's per-MediaItem position into a "global" timeline
     * position matching TimelineView's coordinate space (sum of all clips'
     * timeline durations before the current one, plus position within it),
     * then updates the playhead line and the mm:ss / mm:ss label.
     *
     * NOTE: this assumes each clip's rendered timeline width equals its raw
     * playback duration -- true unless a clip's speed != 1.0, since preview
     * playback doesn't currently apply speed changes (only export does).
     * Minor desync possible for sped-up/slowed-down clips; not worth the
     * complexity to fix until live-preview effects are wired up properly.
     */
    private fun updatePlayheadAndTimeLabel() {
        val project = viewModel.project.value ?: return
        if (project.clips.isEmpty()) {
            binding.timeLabel.text = "0:00 / 0:00"
            return
        }

        val currentItemIndex = player.currentMediaItemIndex
        var cumulativeMs = 0L
        for (i in 0 until currentItemIndex) {
            if (i < project.clips.size) cumulativeMs += project.clips[i].timelineDurationMs
        }
        val positionWithinItem = player.currentPosition.coerceAtLeast(0L)
        val globalPositionMs = (cumulativeMs + positionWithinItem).coerceAtMost(project.totalDurationMs)

        binding.timelineView.setPlayheadMs(globalPositionMs)
        binding.timeLabel.text = "${formatTime(globalPositionMs)} / ${formatTime(project.totalDurationMs)}"
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Applies the currently-playing clip's filter and speed live in the preview,
     * using the same Media3 Effect objects the exporter uses -- so what you see
     * while editing now matches (aside from transitions/text overlay timing
     * edge cases) what export produces, instead of preview always showing a
     * flat, unfiltered image regardless of the clip's settings.
     *
     * Called whenever playback moves to a different clip (onMediaItemTransition)
     * and whenever the current clip's filter changes, since the effect needs to
     * be reapplied any time either the current item or its settings change.
     */
    private fun applyLiveEffectsForCurrentItem() {
        val project = viewModel.project.value ?: return
        val index = player.currentMediaItemIndex
        val clip = project.clips.getOrNull(index) ?: return

        val effects = mutableListOf<androidx.media3.common.Effect>()
        com.example.videoeditor.effects.FilterShaderEffect.forType(clip.filter)?.let { effects += it }
        com.example.videoeditor.effects.TextOverlayEffectFactory.build(clip.textOverlays)?.let { effects += it }

        // Same fade logic as TimelineExporter, so preview matches export.
        val previousClip = project.clips.getOrNull(index - 1)
        val fadeInMs = if (previousClip?.transitionToNext == com.example.videoeditor.model.TransitionType.CROSSFADE) {
            previousClip.transitionDurationMs
        } else 0L
        val fadeOutMs = if (clip.transitionToNext == com.example.videoeditor.model.TransitionType.CROSSFADE) {
            clip.transitionDurationMs
        } else 0L
        val rawClipDurationMs = clip.trimEndMs - clip.trimStartMs
        com.example.videoeditor.effects.TransitionEffectFactory
            .fadeEffect(fadeInMs, fadeOutMs, rawClipDurationMs)
            ?.let { effects += it }

        player.setVideoEffects(effects)

        // Approximate per-clip speed in preview via the player's global playback
        // speed -- not perfectly accurate (ExoPlayer doesn't support truly
        // per-MediaItem speed), but changes speed as playback crosses into a
        // clip with a different speed setting, which is close enough for editing.
        player.setPlaybackParameters(androidx.media3.common.PlaybackParameters(clip.speed))
    }

    private fun updateTransitionButtonLabel() {
        val project = viewModel.project.value ?: return
        val selectedClip = project.clips.firstOrNull { it.id == viewModel.selectedClipId.value }
        binding.toggleTransitionButton.text = if (selectedClip?.transitionToNext == com.example.videoeditor.model.TransitionType.CROSSFADE) {
            "Remove Crossfade"
        } else {
            "Add Crossfade to Next Clip"
        }
    }

    private fun toggleTransitionOnSelected() {
        val project = viewModel.project.value
        val clipId = viewModel.selectedClipId.value
        val index = project?.clips?.indexOfFirst { it.id == clipId } ?: -1
        if (project == null || clipId == null || index == -1) {
            Toast.makeText(this, "Select a clip first", Toast.LENGTH_SHORT).show()
            return
        }
        if (index == project.clips.size - 1) {
            Toast.makeText(this, "Can't add a transition after the last clip", Toast.LENGTH_SHORT).show()
            return
        }

        val clip = project.clips[index]
        val isCurrentlyCrossfade = clip.transitionToNext == com.example.videoeditor.model.TransitionType.CROSSFADE
        val newTransition = if (isCurrentlyCrossfade) {
            com.example.videoeditor.model.TransitionType.NONE
        } else {
            com.example.videoeditor.model.TransitionType.CROSSFADE
        }
        viewModel.setClipTransition(clipId, newTransition, durationMs = 500L)
        Toast.makeText(
            this,
            if (newTransition == com.example.videoeditor.model.TransitionType.CROSSFADE) "Crossfade added" else "Transition removed",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun onVideoPicked(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val durationMs = readVideoDurationMs(uri)
        if (durationMs <= 0L) {
            Toast.makeText(this, "Couldn't read video duration -- picked file may be unsupported", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.addClip(uri, durationMs)
        Toast.makeText(this, "Clip added (${durationMs}ms)", Toast.LENGTH_SHORT).show()
    }

    private fun readVideoDurationMs(uri: Uri): Long {
        val retriever = android.media.MediaMetadataRetriever()
        return try {
            retriever.setDataSource(this, uri)
            retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Toast.makeText(this, "Error reading video: ${e.message}", Toast.LENGTH_LONG).show()
            0L
        } finally {
            retriever.release()
        }
    }

    private fun applyFilterToSelected(filter: com.example.videoeditor.model.FilterType) {
        val clipId = viewModel.selectedClipId.value
        if (clipId == null) {
            Toast.makeText(this, "Select a clip first", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.setClipFilter(clipId, filter)
    }

    private fun showAddTextDialog() {
        val clipId = viewModel.selectedClipId.value
        val project = viewModel.project.value
        val clip = project?.clips?.firstOrNull { it.id == clipId }
        if (clipId == null || clip == null) {
            Toast.makeText(this, "Select a clip first", Toast.LENGTH_SHORT).show()
            return
        }

        val input = android.widget.EditText(this).apply {
            hint = "Enter overlay text"
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Add text overlay")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    val overlay = com.example.videoeditor.model.TextOverlay(
                        id = java.util.UUID.randomUUID().toString(),
                        text = text,
                        startMs = 0L,
                        endMs = clip.timelineDurationMs
                    )
                    viewModel.addTextOverlay(clipId, overlay)
                    Toast.makeText(this, "Text added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeLastTextOverlayFromSelected() {
        val clipId = viewModel.selectedClipId.value
        val project = viewModel.project.value
        val clip = project?.clips?.firstOrNull { it.id == clipId }
        val lastOverlay = clip?.textOverlays?.lastOrNull()
        if (clipId == null || lastOverlay == null) {
            Toast.makeText(this, "No text overlay on selected clip", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.removeTextOverlay(clipId, lastOverlay.id)
        Toast.makeText(this, "Text removed", Toast.LENGTH_SHORT).show()
    }

    private fun rebuildPreviewPlaylist(project: com.example.videoeditor.model.Project) {
        val wasPlaying = player.isPlaying
        val resumeIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val resumePosition = player.currentPosition.coerceAtLeast(0L)

        player.stop()
        player.clearMediaItems()

        project.clips.forEach { clip ->
            val mediaItem = MediaItem.Builder()
                .setUri(clip.sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs)
                        .setEndPositionMs(clip.trimEndMs)
                        .build()
                )
                .build()
            player.addMediaItem(mediaItem)
        }
        player.prepare()
        // Restore where playback was before this edit (e.g. a filter change or
        // trim adjustment) instead of jarringly restarting from the beginning.
        if (resumeIndex < project.clips.size) {
            player.seekTo(resumeIndex, resumePosition)
        }
        if (wasPlaying) player.play()
        applyLiveEffectsForCurrentItem()
        updatePlayheadAndTimeLabel()
    }

    private fun startExport() {
        val project = viewModel.project.value
        if (project == null || project.clips.isEmpty()) {
            Toast.makeText(this, "Add a clip before exporting", Toast.LENGTH_SHORT).show()
            return
        }
        showExportSettingsDialog()
    }

    private fun showExportSettingsDialog() {
        val resolutions = com.example.videoeditor.export.ResolutionPreset.entries.toTypedArray()
        val resolutionLabels = resolutions.map { it.label }.toTypedArray()
        var selectedResolution = com.example.videoeditor.export.ResolutionPreset.P1080

        val codecs = com.example.videoeditor.export.VideoCodec.entries.toTypedArray()
        val codecLabels = codecs.map { it.label }.toTypedArray()
        var selectedCodec = com.example.videoeditor.export.VideoCodec.H264

        android.app.AlertDialog.Builder(this)
            .setTitle("Resolution")
            .setSingleChoiceItems(resolutionLabels, resolutions.indexOf(selectedResolution)) { _, which ->
                selectedResolution = resolutions[which]
            }
            .setPositiveButton("Next") { _, _ ->
                android.app.AlertDialog.Builder(this)
                    .setTitle("Video codec")
                    .setSingleChoiceItems(codecLabels, codecs.indexOf(selectedCodec)) { _, which ->
                        selectedCodec = codecs[which]
                    }
                    .setPositiveButton("Choose save location") { _, _ ->
                        pendingExportSettings = com.example.videoeditor.export.ExportSettings(selectedResolution, selectedCodec)
                        val suggestedName = "edited_video_${System.currentTimeMillis()}.mp4"
                        pickExportDestination.launch(suggestedName)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var pendingExportSettings: com.example.videoeditor.export.ExportSettings? = null

    private fun runExport(destinationUri: Uri) {
        val project = viewModel.project.value
        if (project == null || project.clips.isEmpty()) return
        val settings = pendingExportSettings ?: com.example.videoeditor.export.ExportSettings()

        val tempOutputFile = File(cacheDir, "export_temp_${System.currentTimeMillis()}.mp4")
        ExportRequestHolder.pendingProject = project

        val intent = Intent(this, ExportWorkerService::class.java).apply {
            putExtra(ExportWorkerService.EXTRA_OUTPUT_PATH, tempOutputFile.absolutePath)
            putExtra(ExportWorkerService.EXTRA_DESTINATION_URI, destinationUri.toString())
            putExtra(ExportWorkerService.EXTRA_RESOLUTION_NAME, settings.resolution.name)
            putExtra(ExportWorkerService.EXTRA_CODEC_NAME, settings.codec.name)
        }
        ContextCompat.startForegroundService(this, intent)
        viewModel.setExportProgress(0)
        Toast.makeText(this, "Export started (${settings.resolution.label}, ${settings.codec.label}) -- check notification for progress", Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
