package com.example.videoeditor.effects

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import com.example.videoeditor.model.FilterType

/**
 * Maps our app-level [FilterType] to a Media3 [RgbMatrix] effect.
 *
 * Media3's Transformer/Effect pipeline applies these as GL shader passes per-frame,
 * so they work identically in live preview (ExoPlayer + Effects) and final export
 * (Transformer) -- one implementation, two use sites.
 *
 * For more elaborate looks (LUT-based grading, vignettes) swap RgbMatrix for a
 * custom GlShaderProgram implementing SingleFrameGlShaderProgram.
 */
@UnstableApi
object FilterShaderEffect {

    fun forType(type: FilterType): RgbMatrix? = when (type) {
        FilterType.NONE -> null

        FilterType.GRAYSCALE -> RgbMatrix { _, _ ->
            floatArrayOf(
                0.33f, 0.33f, 0.33f, 0f,
                0.33f, 0.33f, 0.33f, 0f,
                0.33f, 0.33f, 0.33f, 0f,
                0f, 0f, 0f, 1f
            )
        }

        FilterType.SEPIA -> RgbMatrix { _, _ ->
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f,
                0.349f, 0.686f, 0.168f, 0f,
                0.272f, 0.534f, 0.131f, 0f,
                0f, 0f, 0f, 1f
            )
        }

        FilterType.VIVID -> RgbMatrix { _, _ ->
            floatArrayOf(
                1.2f, 0f, 0f, 0f,
                0f, 1.2f, 0f, 0f,
                0f, 0f, 1.2f, 0f,
                0f, 0f, 0f, 1f
            )
        }

        FilterType.COOL -> RgbMatrix { _, _ ->
            floatArrayOf(
                0.9f, 0f, 0f, 0f,
                0f, 1.0f, 0f, 0f,
                0f, 0f, 1.15f, 0f,
                0f, 0f, 0f, 1f
            )
        }

        FilterType.WARM -> RgbMatrix { _, _ ->
            floatArrayOf(
                1.15f, 0f, 0f, 0f,
                0f, 1.0f, 0f, 0f,
                0f, 0f, 0.9f, 0f,
                0f, 0f, 0f, 1f
            )
        }

        FilterType.INVERT -> RgbMatrix { _, _ ->
            // output = 1 - input for each channel (translate column holds the +1 offset)
            floatArrayOf(
                -1f, 0f, 0f, 1f,
                0f, -1f, 0f, 1f,
                0f, 0f, -1f, 1f,
                0f, 0f, 0f, 1f
            )
        }

        FilterType.NOIR -> RgbMatrix { _, _ ->
            // High-contrast black & white: luma weighting scaled up, offset pulled down to crush shadows.
            floatArrayOf(
                0.5f, 0.5f, 0.5f, -0.25f,
                0.5f, 0.5f, 0.5f, -0.25f,
                0.5f, 0.5f, 0.5f, -0.25f,
                0f, 0f, 0f, 1f
            )
        }

        FilterType.FADE -> RgbMatrix { _, _ ->
            // Vintage/matte look: desaturate slightly and lift blacks (reduced contrast, raised shadows).
            floatArrayOf(
                0.93f, 0.03f, 0.03f, 0.08f,
                0.03f, 0.93f, 0.03f, 0.08f,
                0.03f, 0.03f, 0.93f, 0.08f,
                0f, 0f, 0f, 1f
            )
        }

        FilterType.DRAMATIC -> RgbMatrix { _, _ ->
            // Punchier contrast and saturation for a "cinematic" look.
            floatArrayOf(
                1.4f, -0.15f, -0.15f, -0.1f,
                -0.15f, 1.4f, -0.15f, -0.1f,
                -0.15f, -0.15f, 1.4f, -0.1f,
                0f, 0f, 0f, 1f
            )
        }

        FilterType.PASTEL -> RgbMatrix { _, _ ->
            // Soft, desaturated, slightly brightened look.
            floatArrayOf(
                0.75f, 0.1f, 0.1f, 0.12f,
                0.1f, 0.75f, 0.1f, 0.12f,
                0.1f, 0.1f, 0.75f, 0.12f,
                0f, 0f, 0f, 1f
            )
        }

        FilterType.NIGHT -> RgbMatrix { _, _ ->
            // Cool, darkened tone -- boosts blue, dims red/green.
            floatArrayOf(
                0.6f, 0f, 0f, 0f,
                0f, 0.7f, 0f, 0f,
                0f, 0f, 1.0f, 0.05f,
                0f, 0f, 0f, 1f
            )
        }
    }
}
