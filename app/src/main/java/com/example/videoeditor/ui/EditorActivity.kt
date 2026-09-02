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
import com.example.videoeditor.R
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

    // Tracks (sourceUri, trimStart, trimEnd) per clip so the project observer
    // can tell "clip list actually changed" apart from "only a cosmetic field
    // like filter/text/transition changed" -- see the observer below.
    private var lastStructuralSignature: List<Triple<Uri, Long, Long>>? = null
    private var lastKnownPlayheadMs: Long = 0L

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
            showMusicPlacementDialog(it)
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
            viewModel.removeLastAudioTrack()
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
        binding.removeTextButton.setOnClickListener { removeLastTextOverlay() }
        binding.toggleTransitionButton.setOnClickListener { toggleTransitionOnSelected() }

        binding.copyClipButton.setOnClickListener {
            if (viewModel.selectedClipId.value == null) {
                Toast.makeText(this, "Select a clip first", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.copySelectedClip()
                Toast.makeText(this, "Clip copied", Toast.LENGTH_SHORT).show()
            }
        }
        binding.pasteClipButton.setOnClickListener {
            viewModel.pasteClip()
            Toast.makeText(this, "Clip pasted", Toast.LENGTH_SHORT).show()
        }
        binding.deleteClipButton.setOnClickListener {
            if (viewModel.selectedClipId.value == null) {
                Toast.makeText(this, "Select a clip first", Toast.LENGTH_SHORT).show()
            } else {
                viewModel.deleteSelectedClip()
                Toast.makeText(this, "Clip deleted", Toast.LENGTH_SHORT).show()
            }
        }
        viewModel.canPaste.observe(this) { binding.pasteClipButton.isEnabled = it }

        viewModel.project.observe(this) { project ->
            binding.timelineView.setClips(project.clips)
            binding.timelineView.setAudioTracks(project.audioTracks)
            binding.timelineView.setTextOverlays(project.textOverlays)

            // Only rebuild the ExoPlayer playlist (stop/clear/re-add/prepare)
            // when clips actually changed structurally (added, removed,
            // reordered, or trimmed). Filter/text/transition/speed changes
            // don't need any of that -- they only need the effects chain
            // refreshed. Doing a full teardown/rebuild on EVERY edit (including
            // things like a filter tap) was almost certainly what caused
            // playback to intermittently stop responding after those edits.
            val structuralSignature = project.clips.map { Triple(it.sourceUri, it.trimStartMs, it.trimEndMs) }
            if (structuralSignature != lastStructuralSignature) {
                lastStructuralSignature = structuralSignature
                rebuildPreviewPlaylist(project)
            } else {
                applyLiveEffectsForCurrentItem()
            }

            val tracks = project.audioTracks
            binding.musicTrackLabel.text = when {
                tracks.isEmpty() -> "No music"
                tracks.size == 1 -> queryDisplayName(tracks[0].sourceUri) ?: "1 music track"
                else -> "${tracks.size} music tracks"
            }
            binding.removeMusicButton.visibility = if (tracks.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
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
        lastKnownPlayheadMs = globalPositionMs
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * Applies the given clip index's filter/text/transition/speed live in the
     * preview, using the same Media3 Effect objects the exporter uses.
     *
     * Takes an explicit index (rather than always reading
     * player.currentMediaItemIndex) so it can be called BEFORE player.prepare()
     * during a playlist rebuild -- calling ExoPlayer.setVideoEffects() on an
     * already-prepared/playing player turned out to be unreliable (this is an
     * experimental Media3 API), which is what caused playback to stop
     * responding after changing a filter. Setting effects before prepare()
     * avoids that entirely. Wrapped in try/catch as a safety net: a compatibility
     * hiccup here should never be able to break playback outright.
     */
    private fun applyLiveEffectsForCurrentItem(index: Int = player.currentMediaItemIndex) {
        val project = viewModel.project.value ?: return
        val clip = project.clips.getOrNull(index) ?: return

        try {
            val effects = mutableListOf<androidx.media3.common.Effect>()
            com.example.videoeditor.effects.FilterShaderEffect.forType(clip.filter)?.let { effects += it }

            // Overlays are project-level with global timeline positions -- find
            // this clip's global start (sum of preceding clips' durations) so
            // we can pull just the overlays active during it, remapped local.
            val clipGlobalStartMs = project.clips.take(index).sumOf { it.timelineDurationMs }
            val clipLocalOverlays = com.example.videoeditor.effects.TextOverlayEffectFactory.overlaysForWindow(
                project.textOverlays, clipGlobalStartMs, clip.timelineDurationMs
            )
            com.example.videoeditor.effects.TextOverlayEffectFactory.build(clipLocalOverlays)?.let { effects += it }

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
        } catch (e: Exception) {
            // Live preview effects are a nice-to-have; if this experimental API
            // rejects the call for some reason, fail quietly rather than taking
            // playback down with it. Export (the source of truth) is unaffected.
            Toast.makeText(this, "Live preview effect couldn't be applied: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

    private fun showMusicPlacementDialog(uri: Uri) {
        val project = viewModel.project.value
        if (project == null || project.clips.isEmpty()) {
            Toast.makeText(this, "Add a clip first", Toast.LENGTH_SHORT).show()
            return
        }

        val defaultStartSeconds = lastKnownPlayheadMs / 1000f
        val defaultDurationSeconds = ((project.totalDurationMs - lastKnownPlayheadMs).coerceAtLeast(1000L)) / 1000f

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val startInput = android.widget.EditText(this).apply {
            hint = "Start time (seconds)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(defaultStartSeconds.toString())
        }
        val durationInput = android.widget.EditText(this).apply {
            hint = "Duration (seconds)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(defaultDurationSeconds.toString())
        }
        container.addView(startInput)
        container.addView(durationInput)

        android.app.AlertDialog.Builder(this)
            .setTitle("Place music on timeline")
            .setView(container)
            .setPositiveButton("Add") { _, _ ->
                val startSeconds = startInput.text.toString().toFloatOrNull() ?: defaultStartSeconds
                val durationSeconds = durationInput.text.toString().toFloatOrNull() ?: defaultDurationSeconds
                val timelineStartMs = (startSeconds * 1000).toLong().coerceAtLeast(0L)
                val durationMs = (durationSeconds * 1000).toLong().coerceAtLeast(500L)
                viewModel.addAudioTrack(uri, timelineStartMs, durationMs)
                Toast.makeText(this, "Music added", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddTextDialog() {
        val project = viewModel.project.value
        if (project == null || project.clips.isEmpty()) {
            Toast.makeText(this, "Add a clip first", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_text_overlay, null)
        val textInput = dialogView.findViewById<android.widget.EditText>(R.id.overlayTextInput)
        val durationInput = dialogView.findViewById<android.widget.EditText>(R.id.overlayDurationInput)
        val colorRow = dialogView.findViewById<android.widget.LinearLayout>(R.id.colorButtonRow)
        val sizeRow = dialogView.findViewById<android.widget.LinearLayout>(R.id.sizeButtonRow)
        val posTop = dialogView.findViewById<android.widget.LinearLayout>(R.id.positionRowTop)
        val posMid = dialogView.findViewById<android.widget.LinearLayout>(R.id.positionRowMiddle)
        val posBottom = dialogView.findViewById<android.widget.LinearLayout>(R.id.positionRowBottom)

        durationInput.setText("3")

        var selectedColor = android.graphics.Color.WHITE
        var selectedSize = 24f
        var selectedX = 0.5f
        var selectedY = 0.85f // default: bottom-center

        val density = resources.displayMetrics.density
        val swatchSizePx = (36 * density).toInt()
        val swatchMarginPx = (6 * density).toInt()

        // --- Color swatches ---
        val colorOptions = listOf(
            "White" to android.graphics.Color.WHITE,
            "Yellow" to android.graphics.Color.YELLOW,
            "Red" to android.graphics.Color.RED,
            "Cyan" to android.graphics.Color.CYAN,
            "Black" to android.graphics.Color.BLACK
        )
        val colorSwatches = mutableListOf<android.view.View>()
        colorOptions.forEach { (label, colorInt) ->
            val swatch = android.view.View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(swatchSizePx, swatchSizePx).apply {
                    marginEnd = swatchMarginPx
                }
                setBackgroundColor(colorInt)
                contentDescription = label
                alpha = if (colorInt == selectedColor) 1f else 0.4f
            }
            colorSwatches += swatch
            colorRow.addView(swatch)
            swatch.setOnClickListener {
                selectedColor = colorInt
                colorSwatches.forEach { it.alpha = 0.4f }
                swatch.alpha = 1f
            }
        }

        // --- Size options ---
        val sizeOptions = listOf("S" to 18f, "M" to 24f, "L" to 36f)
        val sizeButtons = mutableListOf<android.widget.Button>()
        sizeOptions.forEach { (label, sizeSp) ->
            val btn = android.widget.Button(this).apply {
                text = label
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                alpha = if (sizeSp == selectedSize) 1f else 0.5f
            }
            sizeButtons += btn
            sizeRow.addView(btn)
            btn.setOnClickListener {
                selectedSize = sizeSp
                sizeButtons.forEach { it.alpha = 0.5f }
                btn.alpha = 1f
            }
        }

        // --- Position grid (3x3: left/center/right x top/middle/bottom) ---
        data class PositionOption(val label: String, val x: Float, val y: Float)
        val rows = listOf(
            posTop to listOf(PositionOption("TL", 0.15f, 0.15f), PositionOption("TC", 0.5f, 0.15f), PositionOption("TR", 0.85f, 0.15f)),
            posMid to listOf(PositionOption("L", 0.15f, 0.5f), PositionOption("C", 0.5f, 0.5f), PositionOption("R", 0.85f, 0.5f)),
            posBottom to listOf(PositionOption("BL", 0.15f, 0.85f), PositionOption("BC", 0.5f, 0.85f), PositionOption("BR", 0.85f, 0.85f))
        )
        val positionButtons = mutableListOf<android.widget.Button>()
        rows.forEach { (rowLayout, options) ->
            options.forEach { option ->
                val btn = android.widget.Button(this).apply {
                    text = option.label
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    alpha = if (option.x == selectedX && option.y == selectedY) 1f else 0.5f
                }
                positionButtons += btn
                rowLayout.addView(btn)
                btn.setOnClickListener {
                    selectedX = option.x
                    selectedY = option.y
                    positionButtons.forEach { it.alpha = 0.5f }
                    btn.alpha = 1f
                }
            }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Add text overlay")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val text = textInput.text.toString().trim()
                val durationSeconds = durationInput.text.toString().toFloatOrNull() ?: 3f
                if (text.isNotEmpty()) {
                    // Starts at the current playhead position on the GLOBAL
                    // timeline, independent of any specific clip -- so it keeps
                    // its own position/length even if clips get trimmed,
                    // reordered, or deleted around it.
                    val startMs = lastKnownPlayheadMs
                    val endMs = (startMs + (durationSeconds * 1000).toLong())
                        .coerceAtMost(project.totalDurationMs)
                    val overlay = com.example.videoeditor.model.TextOverlay(
                        id = java.util.UUID.randomUUID().toString(),
                        text = text,
                        startMs = startMs,
                        endMs = endMs,
                        x = selectedX,
                        y = selectedY,
                        colorArgb = selectedColor,
                        sizeSp = selectedSize
                    )
                    viewModel.addTextOverlay(overlay)
                    Toast.makeText(this, "Text added", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeLastTextOverlay() {
        val lastOverlay = viewModel.project.value?.textOverlays?.lastOrNull()
        if (lastOverlay == null) {
            Toast.makeText(this, "No text overlay to remove", Toast.LENGTH_SHORT).show()
            return
        }
        viewModel.removeTextOverlay(lastOverlay.id)
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

        val targetIndex = if (resumeIndex < project.clips.size) resumeIndex else 0
        // Set effects/speed BEFORE prepare() -- calling setVideoEffects() on an
        // already-prepared/playing player is unreliable and previously caused
        // playback to stop responding after a filter change.
        if (project.clips.isNotEmpty()) {
            applyLiveEffectsForCurrentItem(targetIndex)
        }

        player.prepare()
        // Restore where playback was before this edit (e.g. a filter change or
        // trim adjustment) instead of jarringly restarting from the beginning.
        if (targetIndex < project.clips.size) {
            player.seekTo(targetIndex, resumePosition)
        }
        if (wasPlaying) player.play()
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
