package com.gantoine.muxer.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

class TileMotion(private val accent: Color) {
    private val highlight = lerp(accent, Color.White, 0.55f)
    private val warm = accent.shiftHue(-35f, saturation = 1.25f, value = 1.05f)
    private val cool = accent.shiftHue(35f, saturation = 1.25f, value = 0.95f)
    private val deep = accent.shiftHue(15f, saturation = 1.2f, value = 0.65f)
    private val haloColor = lerp(accent, highlight, 0.5f)
    private val blobs = listOf(
        Blob(warm, speed = 0.55f, phase = 0f, alpha = 0.8f),
        Blob(cool, speed = 0.45f, phase = 2.1f, alpha = 0.8f),
        Blob(deep, speed = 0.7f, phase = 4.8f, alpha = 0.6f),
    )
    private val offset = Random.nextFloat() * 20f
    private val clip = Path()

    fun breathe(seconds: Float): Float = 0.5f + 0.5f * sin((seconds + offset) * TWO_PI / BREATH_PERIOD)

    fun heartbeat(seconds: Float): Float {
        val beat = ((seconds + offset) % PULSE_PERIOD) / PULSE_PERIOD
        val rise = (beat / 0.2f).coerceIn(0f, 1f)
        return 0.06f * sin(rise * PI.toFloat())
    }

    fun drawHalo(scope: DrawScope, seconds: Float, corner: Float, strength: Float) = with(scope) {
        val glow = (0.7f + 0.3f * breathe(seconds)) * strength
        val reach = HALO_REACH.toPx()
        repeat(HALO_LAYERS) { layer ->
            val fraction = layer.toFloat() / HALO_LAYERS
            val spread = 1.dp.toPx() + fraction * reach
            drawRoundRect(
                color = haloColor.copy(alpha = 0.07f * (1f - fraction).pow(1.5f) * glow),
                topLeft = Offset(-spread, -spread),
                size = Size(size.width + 2 * spread, size.height + 2 * spread),
                cornerRadius = CornerRadius(corner + spread),
            )
        }
    }

    fun drawAurora(scope: DrawScope, elapsed: Float, corner: Float, strength: Float) = with(scope) {
        val seconds = elapsed + offset
        clip.reset()
        clip.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner)))
        clipPath(clip) {
            blobs.forEach { blob ->
                val center = Offset(
                    size.width * (0.5f + 0.42f * sin(seconds * blob.speed + blob.phase)),
                    size.height * (0.5f + 0.42f * cos(seconds * blob.speed * 0.8f + blob.phase * 1.3f)),
                )
                val radius = size.width * 0.72f
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(blob.color.copy(alpha = blob.alpha * strength), blob.color.copy(alpha = 0f)),
                        center = center,
                        radius = radius,
                    ),
                    radius = radius,
                    center = center,
                )
            }
        }
    }

    fun drawRipples(scope: DrawScope, seconds: Float, corner: Float, strength: Float) = with(scope) {
        val base = 3.dp.toPx()
        drawRing(highlight.copy(alpha = strength), base, corner, 2.dp.toPx())
        RIPPLE_OFFSETS.forEach { delay ->
            val progress = (((seconds + offset) / PULSE_PERIOD - delay) % 1f + 1f) % 1f
            val eased = 1f - (1f - progress).pow(2.5f)
            val spread = base + eased * 13.dp.toPx()
            drawRing(highlight.copy(alpha = (1f - progress).pow(1.5f) * 0.9f * strength), spread, corner, 2.5.dp.toPx() * (1f - 0.6f * eased))
        }
    }

    fun drawSheen(scope: DrawScope, elapsed: Float, corner: Float, strength: Float) = with(scope) {
        val progress = ((elapsed + offset) % SHEEN_PERIOD) / SHEEN_SWEEP
        if (progress >= 1f) return@with
        val eased = progress * progress * (3f - 2f * progress)
        val band = size.width * 0.7f
        val travel = size.width * 1.5f + 2 * band
        val center = -band + eased * travel
        clip.reset()
        clip.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner)))
        clipPath(clip) {
            drawRect(
                Brush.linearGradient(
                    0f to Color.Transparent,
                    0.5f to Color.White.copy(alpha = 0.5f * strength),
                    1f to Color.Transparent,
                    start = Offset(center - band / 2, -band / 4),
                    end = Offset(center + band / 2, band / 4),
                ),
            )
        }
    }

    private fun DrawScope.drawRing(color: Color, spread: Float, corner: Float, width: Float) {
        drawRoundRect(
            color = color,
            topLeft = Offset(-spread, -spread),
            size = Size(size.width + 2 * spread, size.height + 2 * spread),
            cornerRadius = CornerRadius(corner + spread),
            style = Stroke(width),
        )
    }

    private class Blob(val color: Color, val speed: Float, val phase: Float, val alpha: Float)

    private companion object {
        const val TWO_PI = (2 * PI).toFloat()
        const val BREATH_PERIOD = 5f
        const val PULSE_PERIOD = 2.4f
        const val SHEEN_PERIOD = 3.6f
        const val SHEEN_SWEEP = 1.1f
        const val HALO_LAYERS = 14
        val HALO_REACH = 12.dp
        val RIPPLE_OFFSETS = listOf(0f, 0.14f)
    }
}

private fun Color.shiftHue(degrees: Float, saturation: Float, value: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    return Color.hsv(
        ((hsv[0] + degrees) % 360f + 360f) % 360f,
        (hsv[1] * saturation).coerceIn(0f, 1f),
        (hsv[2] * value).coerceIn(0f, 1f),
    )
}
