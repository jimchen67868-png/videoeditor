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
    }
}
