package com.example.videoeditor.export

import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/**
 * User-selectable output settings for export.
 *
 * NOTE on "format": Media3 Transformer always writes an MP4 container --
 * that's the broadly-compatible, standard choice, and Transformer doesn't
 * support muxing to other containers (AVI/MOV/WebM) out of the box. What IS
 * selectable is the video codec used *inside* that MP4: H.264 (maximum
 * device/player compatibility) or H.265/HEVC (smaller files at the same
 * quality, but slightly less universally supported, especially on older
 * devices or when re-sharing to other apps).
 */
@UnstableApi
enum class VideoCodec(val mimeType: String, val label: String) {
    H264(MimeTypes.VIDEO_H264, "H.264 (most compatible)"),
    H265(MimeTypes.VIDEO_H265, "H.265 / HEVC (smaller file size)")
}

enum class ResolutionPreset(val width: Int, val height: Int, val label: String) {
    P480(480, 854, "480p"),
    P720(720, 1280, "720p"),
    P1080(1080, 1920, "1080p"),
    UHD_4K(2160, 3840, "4K")
}

@UnstableApi
data class ExportSettings(
    val resolution: ResolutionPreset = ResolutionPreset.P1080,
    val codec: VideoCodec = VideoCodec.H264
)
