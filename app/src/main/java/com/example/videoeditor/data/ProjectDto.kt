package com.example.videoeditor.data

import android.net.Uri
import com.example.videoeditor.model.AudioTrack
import com.example.videoeditor.model.Clip
import com.example.videoeditor.model.FilterType
import com.example.videoeditor.model.ImageOverlay
import com.example.videoeditor.model.Project
import com.example.videoeditor.model.TextOverlay
import com.example.videoeditor.model.TransitionType

/**
 * Persistence-friendly mirror of [Project] using String for URIs instead of
 * [Uri] -- letting Gson reflect directly into Uri's internal fields is
 * unreliable across versions, so we convert explicitly at the boundary
 * instead. This is the ONLY place Uri<->String conversion for persistence
 * should happen; everywhere else in the app keeps using the real Uri type.
 */
data class ProjectDto(
    val id: String,
    val name: String,
    val clips: List<ClipDto>,
    val audioTracks: List<AudioTrackDto>,
    val textOverlays: List<TextOverlayDto>,
    val imageOverlays: List<ImageOverlayDto>
)

data class ClipDto(
    val id: String,
    val sourceUri: String,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val speed: Float,
    val filter: String,
    val effect: String,
    val volume: Float,
    val transitionToNext: String,
    val transitionDurationMs: Long
)

data class TextOverlayDto(
    val id: String,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val x: Float,
    val y: Float,
    val colorArgb: Int,
    val sizeSp: Float,
    val hasBackground: Boolean
)

data class AudioTrackDto(
    val id: String,
    val sourceUri: String,
    val sourceStartMs: Long,
    val timelineStartMs: Long,
    val durationMs: Long,
    val volume: Float,
    val duckingEnabled: Boolean
)

data class ImageOverlayDto(
    val id: String,
    val sourceUri: String,
    val startMs: Long,
    val endMs: Long,
    val x: Float,
    val y: Float,
    val scale: Float,
    val opacity: Float
)

fun Project.toDto(): ProjectDto = ProjectDto(
    id = id,
    name = name,
    clips = clips.map { it.toDto() },
    audioTracks = audioTracks.map { it.toDto() },
    textOverlays = textOverlays.map { it.toDto() },
    imageOverlays = imageOverlays.map { it.toDto() }
)

fun ProjectDto.toModel(): Project = Project(
    id = id,
    name = name,
    clips = clips.map { it.toModel() },
    audioTracks = audioTracks.map { it.toModel() },
    textOverlays = textOverlays.map { it.toModel() },
    imageOverlays = imageOverlays.map { it.toModel() }
)

private fun Clip.toDto(): ClipDto = ClipDto(
    id = id,
    sourceUri = sourceUri.toString(),
    trimStartMs = trimStartMs,
    trimEndMs = trimEndMs,
    speed = speed,
    filter = filter.name,
    effect = effect.name,
    volume = volume,
    transitionToNext = transitionToNext.name,
    transitionDurationMs = transitionDurationMs
)

private fun ClipDto.toModel(): Clip = Clip(
    id = id,
    sourceUri = Uri.parse(sourceUri),
    trimStartMs = trimStartMs,
    trimEndMs = trimEndMs,
    speed = speed,
    filter = runCatching { FilterType.valueOf(filter) }.getOrDefault(FilterType.NONE),
    effect = runCatching { com.example.videoeditor.model.EffectType.valueOf(effect) }.getOrDefault(com.example.videoeditor.model.EffectType.NONE),
    volume = volume,
    transitionToNext = runCatching { TransitionType.valueOf(transitionToNext) }.getOrDefault(TransitionType.NONE),
    transitionDurationMs = transitionDurationMs
)

private fun TextOverlay.toDto(): TextOverlayDto = TextOverlayDto(
    id = id, text = text, startMs = startMs, endMs = endMs,
    x = x, y = y, colorArgb = colorArgb, sizeSp = sizeSp, hasBackground = hasBackground
)

private fun TextOverlayDto.toModel(): TextOverlay = TextOverlay(
    id = id, text = text, startMs = startMs, endMs = endMs,
    x = x, y = y, colorArgb = colorArgb, sizeSp = sizeSp, hasBackground = hasBackground
)

private fun AudioTrack.toDto(): AudioTrackDto = AudioTrackDto(
    id = id, sourceUri = sourceUri.toString(), sourceStartMs = sourceStartMs, timelineStartMs = timelineStartMs,
    durationMs = durationMs, volume = volume, duckingEnabled = duckingEnabled
)

private fun AudioTrackDto.toModel(): AudioTrack = AudioTrack(
    id = id, sourceUri = Uri.parse(sourceUri), sourceStartMs = sourceStartMs, timelineStartMs = timelineStartMs,
    durationMs = durationMs, volume = volume, duckingEnabled = duckingEnabled
)

private fun ImageOverlay.toDto(): ImageOverlayDto = ImageOverlayDto(
    id = id, sourceUri = sourceUri.toString(), startMs = startMs, endMs = endMs,
    x = x, y = y, scale = scale, opacity = opacity
)

private fun ImageOverlayDto.toModel(): ImageOverlay = ImageOverlay(
    id = id, sourceUri = Uri.parse(sourceUri), startMs = startMs, endMs = endMs,
    x = x, y = y, scale = scale, opacity = opacity
)
