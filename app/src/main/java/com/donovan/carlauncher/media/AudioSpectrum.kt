package com.donovan.carlauncher.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Taps the device's audio output for a real frequency spectrum.
 *
 * This often yields nothing on modern Android: capturing the global output mix through
 * [Visualizer] needs RECORD_AUDIO, and even then the playing app decides whether it may
 * be captured at all - most streaming apps say no. So this reports whether it is
 * actually receiving signal, and the visualiser falls back to a playback-driven
 * animation when it is not.
 */
class AudioSpectrum(private val context: Context) {

    private val lock = Any()
    private val bands = FloatArray(BANDS)
    private var visualizer: Visualizer? = null

    /** Auto-gain reference so quiet tracks still fill the bars. */
    private var runningPeak = 0.25f

    /** True once real, non-silent FFT data has arrived at least once. */
    @Volatile
    var everHadSignal: Boolean = false
        private set

    @Volatile
    var attached: Boolean = false
        private set

    private val listener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, rate: Int) = Unit

        override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            if (fft == null || fft.size < 4) return
            consume(fft, samplingRate / 1000)
        }
    }

    fun start() {
        if (visualizer != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            // Session 0 is the global output mix.
            val v = Visualizer(0)
            val sizes = Visualizer.getCaptureSizeRange()
            v.captureSize = sizes[1].coerceAtMost(1024)
            v.setDataCaptureListener(
                listener,
                Visualizer.getMaxCaptureRate().coerceAtMost(20_000),
                false,
                true,
            )
            v.enabled = true
            visualizer = v
            attached = true
        }.onFailure {
            visualizer = null
            attached = false
        }
    }

    fun stop() {
        runCatching {
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        attached = false
        synchronized(lock) { bands.fill(0f) }
    }

    /** Copies the latest band magnitudes (0..1) into [out]. */
    fun snapshotInto(out: FloatArray) {
        synchronized(lock) {
            val n = minOf(out.size, bands.size)
            for (i in 0 until n) out[i] = bands[i]
        }
    }

    // ------------------------------------------------------------------ analysis

    private fun consume(fft: ByteArray, sampleRateHz: Int) {
        val binCount = fft.size / 2
        if (binCount < 4 || sampleRateHz <= 0) return

        val nyquist = sampleRateHz / 2f
        val hzPerBin = nyquist / binCount

        var frameMax = 0f
        val scratch = FloatArray(BANDS)

        for (band in 0 until BANDS) {
            // Logarithmic band edges: matches how hearing works, and keeps the bass from
            // hogging one bar while everything above 5 kHz shares another.
            val lo = bandEdgeHz(band)
            val hi = bandEdgeHz(band + 1)
            val loBin = (lo / hzPerBin).toInt().coerceIn(1, binCount - 1)
            val hiBin = (hi / hzPerBin).toInt().coerceIn(loBin + 1, binCount)

            var peak = 0f
            var i = loBin
            while (i < hiBin) {
                val re = fft[2 * i].toFloat()
                val im = fft[2 * i + 1].toFloat()
                val mag = hypot(re, im)
                if (mag > peak) peak = mag
                i++
            }
            // Perceptual squash: raw magnitudes are very spiky.
            val shaped = sqrt(peak / 128f).coerceIn(0f, 4f)
            scratch[band] = shaped
            if (shaped > frameMax) frameMax = shaped
        }

        if (frameMax > SIGNAL_FLOOR) everHadSignal = true

        // Slow auto-gain so a quiet mix still reaches the top of the strip.
        runningPeak = if (frameMax > runningPeak) {
            frameMax
        } else {
            max(MIN_PEAK, runningPeak * 0.96f)
        }
        val gain = 1f / runningPeak

        synchronized(lock) {
            for (band in 0 until BANDS) {
                bands[band] = (scratch[band] * gain).coerceIn(0f, 1f)
            }
        }
    }

    private fun bandEdgeHz(index: Int): Float {
        val t = index.toFloat() / BANDS
        // 40 Hz .. 14 kHz, spaced logarithmically.
        return LOW_HZ * (HIGH_HZ / LOW_HZ).pow(t)
    }

    companion object {
        const val BANDS = 64
        private const val LOW_HZ = 40f
        private const val HIGH_HZ = 14_000f
        private const val SIGNAL_FLOOR = 0.02f
        private const val MIN_PEAK = 0.15f
    }
}
