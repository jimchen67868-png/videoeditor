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

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

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
                viewModel.trimClip(clipId, newTrimStartMs, newTrimEndMs)
            }

            override fun onClipSelected(clipId: String) {
                viewModel.selectClip(clipId)
                Toast.makeText(this@EditorActivity, "Clip selected", Toast.LENGTH_SHORT).show()
            }

            override fun onPlayheadMoved(positionMs: Long) {
                player.seekTo(positionMs)
            }
        }

        binding.addClipButton.setOnClickListener {
            Toast.makeText(this, "Opening video picker...", Toast.LENGTH_SHORT).show()
            pickVideo.launch("video/*")
        }
        binding.exportButton.setOnClickListener { startExport() }

        binding.filterGrayscaleButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.GRAYSCALE) }
        binding.filterSepiaButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.SEPIA) }
        binding.filterNoneButton.setOnClickListener { applyFilterToSelected(com.example.videoeditor.model.FilterType.NONE) }

        viewModel.project.observe(this) { project ->
            binding.timelineView.setClips(project.clips)
            rebuildPreviewPlaylist(project)
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

        val outputFile = File(getExternalFilesDir(null), "export_${System.currentTimeMillis()}.mp4")
        ExportRequestHolder.pendingProject = project

        val intent = Intent(this, ExportWorkerService::class.java).apply {
            putExtra(ExportWorkerService.EXTRA_OUTPUT_PATH, outputFile.absolutePath)
        }
        ContextCompat.startForegroundService(this, intent)
        viewModel.setExportProgress(0)
        Toast.makeText(this, "Export started -- check notification for progress", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
