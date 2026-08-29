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

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onVideoPicked(it) }
    }

    private val pickMusic = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
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

        binding.filterGrayscaleButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.GRAYSCALE) }
        binding.filterSepiaButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.SEPIA) }
        binding.filterNoneButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.NONE) }

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
    }

    override fun onStop() {
        super.onStop()
        player.pause()
        runCatching { unregisterReceiver(exportResultReceiver) }
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

    private fun rebuildPreviewPlaylist(project: com.example.videoeditor.model.Project) {
        val wasPlaying = player.isPlaying
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
        if (wasPlaying) player.play()
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
