package com.example.videoeditor.model

import android.net.Uri

/**
 * Core timeline data model. This is the single source of truth for a project:
 * every screen (preview, timeline UI, export) reads from / writes to this model.
 */

/** Supported color/look filters. Each maps to a GLSL shader in effects/FilterShaderEffect.kt */
enum class FilterType {
    NONE,
    GRAYSCALE,
    SEPIA,
    VIVID,
    COOL,
    WARM,
    INVERT,
    NOIR,
    FADE,
    DRAMATIC,
    PASTEL,
    NIGHT
}

/**
 * Animated visual effects, distinct from [FilterType] (static color grading).
 * Each maps to a time-varying GL effect in effects/VisualEffectFactory.kt.
 */
enum class EffectType {
    NONE,
    FLASH,
    SHAKE,
    ZOOM_IN,
    ZOOM_OUT,
    PULSE
}

/** Type of transition rendered between two adjacent clips on the timeline. */
enum class TransitionType {
    NONE,
    CROSSFADE,
    CUT
}

/**
 * A text overlay burned into the video during export (and rendered live during preview).
 *
 * Lives at the PROJECT level, independent of any specific clip -- startMs/endMs
 * are positions on the whole project's global timeline, not relative to a clip.
 * This means trimming, reordering, or deleting clips doesn't silently break or
 * orphan an overlay's timing the way clip-scoped overlays would.
 *
 * @param startMs / endMs are the overlay's own active window on the GLOBAL project timeline (ms).
 * @param x / y are normalized 0f..1f positions (fraction of frame width/height).
 */
data class TextOverlay(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val x: Float = 0.5f,
    val y: Float = 0.85f,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val sizeSp: Float = 24f
)

/**
 * A single clip placed on the timeline.
 *
 * @param sourceUri original media file (video or image).
 * @param trimStartMs / trimEndMs define the in/out points *within the source file*.
 * @param speed playback speed multiplier (1.0 = normal).
 * @param filter color filter applied to this clip.
 * @param volume clip audio volume, 0f (muted) to 1f (full) — ignored for silent sources.
 * @param transitionToNext transition rendered between this clip and the following one.
 * @param transitionDurationMs duration of that transition, ignored if transitionToNext == NONE.
 */
data class Clip(
    val id: String,
    val sourceUri: Uri,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val speed: Float = 1.0f,
    val filter: FilterType = FilterType.NONE,
    val effect: EffectType = EffectType.NONE,
    val volume: Float = 1.0f,
    val transitionToNext: TransitionType = TransitionType.NONE,
    val transitionDurationMs: Long = 500L
) {
    /** Duration of this clip on the timeline, accounting for speed. */
    val timelineDurationMs: Long
        get() = ((trimEndMs - trimStartMs) / speed).toLong()
}

/** A background music track layered under the whole timeline. */
/**
 * A background music/audio track layered under the timeline.
 *
 * Like [TextOverlay], this is independent of any specific clip: [timelineStartMs]
 * is WHERE on the project's global timeline it starts playing, and [durationMs]
 * is its own length -- trimming, reordering, or deleting clips doesn't affect it.
 *
 * @param sourceUri the audio file.
 * @param sourceStartMs offset INTO the audio file itself (skip the first N ms of the file).
 * @param timelineStartMs position on the GLOBAL project timeline where playback starts.
 * @param durationMs how long this track plays, independent of the project's total length.
 */
data class AudioTrack(
    val id: String,
    val sourceUri: Uri,
    val sourceStartMs: Long = 0L,
    val timelineStartMs: Long = 0L,
    val durationMs: Long,
    val volume: Float = 1.0f,
    /** If true, ducks background music volume under clip dialogue automatically. */
    val duckingEnabled: Boolean = false
)

/**
 * A full editing project: an ordered list of clips plus an optional music track.
 * This whole object is what gets persisted (see data/ProjectDao) and what
 * TimelineExporter consumes to build the final Media3 Composition.
 */
data class Project(
    val id: String,
    val name: String,
    val clips: List<Clip> = emptyList(),
    val audioTracks: List<AudioTrack> = emptyList(),
    val textOverlays: List<TextOverlay> = emptyList()
) {
    /** Total duration of the whole project, ignoring transition overlap (approximation). */
    val totalDurationMs: Long
        get() = clips.sumOf { it.timelineDurationMs }

    /** Returns the clip whose timeline range contains [positionMs], plus its offset within that clip. */
    fun clipAt(positionMs: Long): Pair<Clip, Long>? {
        var cursor = 0L
        for (clip in clips) {
            val duration = clip.timelineDurationMs
            if (positionMs in cursor until (cursor + duration)) {
                return clip to (positionMs - cursor)
            }
            cursor += duration
        }
        return null
    }
}
