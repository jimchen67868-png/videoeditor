package com.example.videoeditor.data

import android.net.Uri
import com.example.videoeditor.model.AudioTrack
import com.example.videoeditor.model.Clip
import com.example.videoeditor.model.FilterType
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
    val textOverlays: List<TextOverlayDto>
)

data class ClipDto(
    val id: String,
    val sourceUri: String,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val speed: Float,
    val filter: String,
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
    val sizeSp: Float
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

fun Project.toDto(): ProjectDto = ProjectDto(
    id = id,
    name = name,
    clips = clips.map { it.toDto() },
    audioTracks = audioTracks.map { it.toDto() },
    textOverlays = textOverlays.map { it.toDto() }
)

fun ProjectDto.toModel(): Project = Project(
    id = id,
    name = name,
    clips = clips.map { it.toModel() },
    audioTracks = audioTracks.map { it.toModel() },
    textOverlays = textOverlays.map { it.toModel() }
)

private fun Clip.toDto(): ClipDto = ClipDto(
    id = id,
    sourceUri = sourceUri.toString(),
    trimStartMs = trimStartMs,
    trimEndMs = trimEndMs,
    speed = speed,
    filter = filter.name,
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
    volume = volume,
    transitionToNext = runCatching { TransitionType.valueOf(transitionToNext) }.getOrDefault(TransitionType.NONE),
    transitionDurationMs = transitionDurationMs
)

private fun TextOverlay.toDto(): TextOverlayDto = TextOverlayDto(
    id = id, text = text, startMs = startMs, endMs = endMs,
    x = x, y = y, colorArgb = colorArgb, sizeSp = sizeSp
)

private fun TextOverlayDto.toModel(): TextOverlay = TextOverlay(
    id = id, text = text, startMs = startMs, endMs = endMs,
    x = x, y = y, colorArgb = colorArgb, sizeSp = sizeSp
)

private fun AudioTrack.toDto(): AudioTrackDto = AudioTrackDto(
    id = id, sourceUri = sourceUri.toString(), sourceStartMs = sourceStartMs, timelineStartMs = timelineStartMs,
    durationMs = durationMs, volume = volume, duckingEnabled = duckingEnabled
)

private fun AudioTrackDto.toModel(): AudioTrack = AudioTrack(
    id = id, sourceUri = Uri.parse(sourceUri), sourceStartMs = sourceStartMs, timelineStartMs = timelineStartMs,
    durationMs = durationMs, volume = volume, duckingEnabled = duckingEnabled
)
