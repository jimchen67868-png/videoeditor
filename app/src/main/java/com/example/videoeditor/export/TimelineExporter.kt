package com.example.videoeditor.export

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import androidx.media3.common.Effect
import com.example.videoeditor.effects.FilterShaderEffect
import com.example.videoeditor.effects.TextOverlayEffectFactory
import com.example.videoeditor.model.Project
import java.io.File

/**
 * Turns a [Project] (our timeline model) into a rendered MP4 using Media3 Transformer.
 *
 * One EditedMediaItem is built per clip (trim points + speed + per-clip effects),
 * all clips are placed in one EditedMediaItemSequence back-to-back, and if a
 * background music track exists it becomes a second, audio-only sequence mixed
 * underneath. Transformer handles decode -> GL effect pass -> re-encode -> mux.
 */
@UnstableApi
class TimelineExporter(private val context: Context) {

    fun interface ProgressListener {
        /** progress in 0..100, or -1 if unknown */
        fun onProgress(progress: Int)
    }

    fun interface CompletionListener {
        fun onResult(success: Boolean, outputFile: File?, error: Throwable?)
    }

    fun export(
        project: Project,
        outputFile: File,
        onProgress: ProgressListener? = null,
        onComplete: CompletionListener
    ) {
        val videoSequence = buildVideoSequence(project)
        val sequences = mutableListOf(videoSequence)

        project.audioTrack?.let { audio ->
            val audioItem = EditedMediaItem.Builder(MediaItem.fromUri(audio.sourceUri))
                .setRemoveVideo(true)
                .build()
            sequences += EditedMediaItemSequence(audioItem)
        }

        val composition = Composition.Builder(sequences).build()

        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: Composition,
                    exportResult: androidx.media3.transformer.ExportResult
                ) {
                    onComplete.onResult(true, outputFile, null)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exception: androidx.media3.transformer.ExportException
                ) {
                    onComplete.onResult(false, null, exception)
                }
            })
            .build()

        transformer.start(composition, outputFile.absolutePath)

        // Poll progress on the main thread per Transformer's contract.
        if (onProgress != null) {
            val holder = androidx.media3.transformer.ProgressHolder()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val pollRunnable = object : Runnable {
                override fun run() {
                    val state = transformer.getProgress(holder)
                    if (state != androidx.media3.transformer.Transformer.PROGRESS_STATE_NOT_STARTED) {
                        onProgress.onProgress(holder.progress)
                    }
                    if (state != androidx.media3.transformer.Transformer.PROGRESS_STATE_UNAVAILABLE) {
                        handler.postDelayed(this, 250)
                    }
                }
            }
            handler.post(pollRunnable)
        }
    }

    private fun buildVideoSequence(project: Project): EditedMediaItemSequence {
        val items = project.clips.map { clip ->
            val mediaItem = MediaItem.Builder()
                .setUri(clip.sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs)
                        .setEndPositionMs(clip.trimEndMs)
                        .build()
                )
                .build()

            val videoEffects = mutableListOf<Effect>()
            FilterShaderEffect.forType(clip.filter)?.let { videoEffects += it }
            TextOverlayEffectFactory.build(clip.textOverlays)?.let { videoEffects += it }
            // Normalize all clips to a consistent resolution so cuts/transitions line up cleanly.
            videoEffects += Presentation.createForWidthAndHeight(
                1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT
            )

            val audioProcessors = if (clip.speed != 1.0f) {
                listOf(SonicAudioProcessor().apply { setSpeed(clip.speed) })
            } else {
                emptyList()
            }

            EditedMediaItem.Builder(mediaItem)
                .setEffects(Effects(audioProcessors, videoEffects))
                .setRemoveAudio(clip.volume <= 0f)
                .build()
        }

        return EditedMediaItemSequence(items)
    }
}
