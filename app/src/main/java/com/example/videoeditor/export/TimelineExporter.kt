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
import kotlin.math.min

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

    companion object {
        /** Matches the actual length of res/raw/silence_10s.wav, bundled for padding delayed music starts. */
        private const val SILENCE_ASSET_DURATION_MS = 10_000L
    }

    /** Uri for the bundled silent-audio asset, used to pad a music track's timeline start. */
    private fun silenceAssetUri(): android.net.Uri =
        android.net.Uri.parse("android.resource://${context.packageName}/${com.example.videoeditor.R.raw.silence_10s}")

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
        settings: ExportSettings = ExportSettings(),
        onProgress: ProgressListener? = null,
        onComplete: CompletionListener
    ) {
        val videoSequence = buildVideoSequence(project, settings)
        val sequences = mutableListOf(videoSequence)

        // Each music track gets its own EditedMediaItemSequence, mixed together
        // by Composition alongside the video sequence -- so multiple independent
        // tracks (e.g. background music + a separate sound effect later in the
        // timeline) all play simultaneously at their own respective positions.
        for (audio in project.audioTracks) {
            val audioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
            if (audio.volume != 1f) {
                val mixer = androidx.media3.common.audio.ChannelMixingAudioProcessor()
                // Configure gain for both mono and stereo sources -- Media3 requires
                // registering a matrix per possible input channel count.
                mixer.putChannelMixingMatrix(
                    androidx.media3.common.audio.ChannelMixingMatrix.create(1, 1).scaleBy(audio.volume)
                )
                mixer.putChannelMixingMatrix(
                    androidx.media3.common.audio.ChannelMixingMatrix.create(2, 2).scaleBy(audio.volume)
                )
                audioProcessors += mixer
            }

            val audioMediaItem = MediaItem.Builder()
                .setUri(audio.sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(audio.sourceStartMs)
                        .setEndPositionMs(audio.sourceStartMs + audio.durationMs)
                        .build()
                )
                .build()

            val audioItem = EditedMediaItem.Builder(audioMediaItem)
                .setRemoveVideo(true)
                .setEffects(Effects(audioProcessors, emptyList()))
                .build()

            // Each track has its own independent start position on the GLOBAL
            // timeline (audio.timelineStartMs), not necessarily 0 -- pad this
            // track's sequence with silence chunks first so it starts playing
            // at the right moment instead of always from the beginning.
            val sequenceItems = mutableListOf<EditedMediaItem>()
            var remainingSilenceMs = audio.timelineStartMs
            while (remainingSilenceMs > 0) {
                val chunkMs = min(remainingSilenceMs, SILENCE_ASSET_DURATION_MS)
                val silenceItem = EditedMediaItem.Builder(
                    MediaItem.Builder()
                        .setUri(silenceAssetUri())
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(0)
                                .setEndPositionMs(chunkMs)
                                .build()
                        )
                        .build()
                ).setRemoveVideo(true).build()
                sequenceItems += silenceItem
                remainingSilenceMs -= chunkMs
            }
            sequenceItems += audioItem

            sequences += EditedMediaItemSequence(sequenceItems)
        }

        val composition = Composition.Builder(sequences).build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(settings.codec.mimeType)
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

    private fun buildVideoSequence(project: Project, settings: ExportSettings): EditedMediaItemSequence {
        // Running sum of preceding clips' timeline durations -- needed to know
        // where each clip sits on the GLOBAL timeline, since text overlays are
        // now project-level with global start/end positions (see model/Timeline.kt).
        var cumulativeGlobalStartMs = 0L

        val items = project.clips.mapIndexed { index, clip ->
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

            val rawClipDurationMsForEffect = clip.trimEndMs - clip.trimStartMs
            videoEffects += com.example.videoeditor.effects.VisualEffectFactory.forType(clip.effect, rawClipDurationMsForEffect)

            val clipLocalOverlays = TextOverlayEffectFactory.overlaysForWindow(
                project.textOverlays, cumulativeGlobalStartMs, clip.timelineDurationMs
            )
            TextOverlayEffectFactory.build(clipLocalOverlays)?.let { videoEffects += it }

            val clipLocalImageOverlays = com.example.videoeditor.effects.ImageOverlayEffectFactory.overlaysForWindow(
                project.imageOverlays, cumulativeGlobalStartMs, clip.timelineDurationMs
            )
            com.example.videoeditor.effects.ImageOverlayEffectFactory.build(context, clipLocalImageOverlays)?.let { videoEffects += it }

            cumulativeGlobalStartMs += clip.timelineDurationMs

            // Fade in if the PREVIOUS clip requested a transition into this one;
            // fade out if THIS clip requested a transition into the next one.
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
                ?.let { videoEffects += it }

            // Normalize all clips to the chosen output resolution so cuts/transitions line up cleanly.
            videoEffects += Presentation.createForWidthAndHeight(
                settings.resolution.width, settings.resolution.height, Presentation.LAYOUT_SCALE_TO_FIT
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
