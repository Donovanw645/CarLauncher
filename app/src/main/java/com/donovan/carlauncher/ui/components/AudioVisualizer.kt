package com.donovan.carlauncher.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.donovan.carlauncher.media.AudioSpectrum
import kotlin.math.exp
import kotlin.math.sin

/**
 * Mirrored spectrum strip that sits under the library panel.
 *
 * Uses the real output spectrum when Android hands it over, and a playback-driven
 * animation when it does not - see [AudioSpectrum] for why that is the common case.
 */
@Composable
fun AudioVisualizer(
    playing: Boolean,
    modifier: Modifier = Modifier,
    accent: Color,
    secondary: Color,
    barWidth: Dp = 6.dp,
    barGap: Dp = 4.dp,
) {
    val context = LocalContext.current
    val spectrum = remember { AudioSpectrum(context) }

    DisposableEffect(spectrum) {
        spectrum.start()
        onDispose { spectrum.stop() }
    }

    // Levels the bars are actually drawn at, and a slower peak marker above each.
    val levels = remember { FloatArray(AudioSpectrum.BANDS) }
    val peaks = remember { FloatArray(AudioSpectrum.BANDS) }
    val raw = remember { FloatArray(AudioSpectrum.BANDS) }

    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = 0L
        var clock = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1_000_000_000f)
                last = now
                val step = dt.coerceIn(0.001f, 0.1f)
                clock += step

                val useReal = spectrum.everHadSignal
                if (useReal) spectrum.snapshotInto(raw)

                for (i in raw.indices) {
                    val target = when {
                        useReal -> raw[i]
                        playing -> synthetic(clock, i, raw.size)
                        else -> 0f
                    } * (if (playing || useReal) 1f else 0f)

                    // Snap up, ease down - the classic analyser feel, and it stops the
                    // strip looking like it is lagging the music.
                    val current = levels[i]
                    val rate = if (target > current) ATTACK else DECAY
                    levels[i] = current + (target - current) * (1f - exp(-rate * step))

                    peaks[i] = maxOf(levels[i], peaks[i] - PEAK_FALL * step)
                }
                frame = now
            }
        }
    }

    Canvas(modifier) {
        // Read so the draw invalidates every frame.
        @Suppress("UNUSED_EXPRESSION")
        frame

        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val bw = barWidth.toPx()
        val gap = barGap.toPx()
        val pitch = bw + gap
        val count = ((w + gap) / pitch).toInt().coerceIn(1, 256)
        val startX = (w - (count * pitch - gap)) / 2f

        // Centre line sits above the middle so the reflection reads as a reflection.
        val centreY = h * 0.60f
        val upMax = centreY - 2f
        val downMax = h - centreY - 2f

        val radius = CornerRadius(bw / 2f, bw / 2f)

        // Faint axis the bars grow out of.
        drawRect(
            color = accent.copy(alpha = 0.10f),
            topLeft = Offset(0f, centreY - 0.5f),
            size = Size(w, 1f),
        )

        for (i in 0 until count) {
            val t = if (count == 1) 0.5f else i.toFloat() / (count - 1)
            // Sample the band array across the available bars.
            val bandIndex = (t * (levels.size - 1)).toInt().coerceIn(0, levels.size - 1)
            val level = levels[bandIndex].coerceIn(0f, 1f)
            val peak = peaks[bandIndex].coerceIn(0f, 1f)

            // Green at the edges, blue through the middle - the app's own two accents
            // rather than the rainbow of a classic analyser.
            val mix = 1f - kotlin.math.abs(t - 0.5f) * 2f
            val base = lerp(accent, secondary, mix * 0.85f)

            val x = startX + i * pitch
            val idle = IDLE_FLOOR * upMax
            val up = (level * upMax).coerceAtLeast(idle)
            val down = (level * downMax * 0.62f).coerceAtLeast(idle * 0.62f)

            // Upper bar: brighter at the tip.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(base, base.copy(alpha = 0.55f)),
                    startY = centreY - up,
                    endY = centreY,
                ),
                topLeft = Offset(x, centreY - up),
                size = Size(bw, up),
                cornerRadius = radius,
            )

            // Reflection: shorter, dimmer, fading out.
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(base.copy(alpha = 0.34f), base.copy(alpha = 0f)),
                    startY = centreY,
                    endY = centreY + down,
                ),
                topLeft = Offset(x, centreY),
                size = Size(bw, down),
                cornerRadius = radius,
            )

            // Peak marker, only once the bar has something to say.
            if (peak > IDLE_FLOOR * 1.6f) {
                val py = centreY - (peak * upMax) - bw * 0.55f
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.55f),
                    topLeft = Offset(x, py.coerceAtLeast(0f)),
                    size = Size(bw, bw * 0.32f),
                    cornerRadius = CornerRadius(bw * 0.16f, bw * 0.16f),
                )
            }
        }
    }
}

/**
 * Stand-in spectrum for when the real one is unavailable: a few detuned oscillators
 * with a bass-weighted envelope, which reads as music rather than as a sine wave.
 */
private fun synthetic(t: Float, band: Int, count: Int): Float {
    val x = band.toFloat() / count
    // Gentle bass tilt only - a steep one leaves the top two thirds of the strip flat.
    val envelope = exp(-x * 0.9f) * 0.52f + 0.48f

    var v = 0.55f * sin(t * 2.1f + x * 9.0f)
    v += 0.30f * sin(t * 3.7f + x * 17.0f + 1.3f)
    v += 0.20f * sin(t * 5.3f + x * 29.0f + 2.7f)
    v += 0.13f * sin(t * 9.1f + x * 43.0f)

    val beat = 0.78f + 0.22f * sin(t * 2.6f)
    return ((v * 0.40f + 0.52f) * envelope * beat * 1.25f).coerceIn(0f, 1f)
}

private const val ATTACK = 22f
private const val DECAY = 7f
private const val PEAK_FALL = 0.9f
private const val IDLE_FLOOR = 0.035f
