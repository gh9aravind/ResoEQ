package com.example.parametriceq

/**
 * A cascade of per-band peaking biquad filters - one filter chain per channel.
 *
 * This replaces Stage 1's DynamicsProcessing. DynamicsProcessing's "EQ" bands
 * are defined by cutoff/edge frequencies - closer to a graphic EQ. Here, each
 * band is a true parametric peaking filter with its own center frequency, Q
 * (bandwidth), and gain, computed with the standard Audio EQ Cookbook formulas
 * (Robert Bristow-Johnson). We run this ourselves on the raw PCM samples
 * instead of handing audio to a platform effect.
 */
class ParametricEqEngine(
    private val sampleRate: Int,
    private val bandFreqs: FloatArray,
    private val channelCount: Int
) {
    private val bandGains = FloatArray(bandFreqs.size)
    private val bandQ = FloatArray(bandFreqs.size) { 1.0f }
    private val filters = Array(channelCount) { Array(bandFreqs.size) { Biquad() } }

    init {
        for (ch in 0 until channelCount) {
            for (b in bandFreqs.indices) {
                filters[ch][b].setPeakingCoefficients(sampleRate, bandFreqs[b], bandQ[b], 0f)
            }
        }
    }

    /** Safe to call from the UI thread while the audio thread is running. */
    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in bandFreqs.indices) return
        bandGains[bandIndex] = gainDb
        applyBand(bandIndex)
    }

    /** Q controls how narrow/wide the band is. ~0.7 = wide/gentle, ~3+ = narrow/surgical. */
    fun setBandQ(bandIndex: Int, q: Float) {
        if (bandIndex !in bandFreqs.indices) return
        bandQ[bandIndex] = q.coerceIn(0.1f, 10f)
        applyBand(bandIndex)
    }

    private fun applyBand(bandIndex: Int) {
        for (ch in 0 until channelCount) {
            filters[ch][bandIndex].setPeakingCoefficients(
                sampleRate, bandFreqs[bandIndex], bandQ[bandIndex], bandGains[bandIndex]
            )
        }
    }

    /**
     * Filters a 16-bit PCM interleaved buffer IN PLACE.
     * Call only from the single audio-processing thread.
     */
    fun processInPlace(buffer: ShortArray, samples: Int) {
        var i = 0
        while (i < samples) {
            for (ch in 0 until channelCount) {
                var sample = buffer[i + ch] / 32768.0
                for (band in filters[ch]) {
                    sample = band.process(sample)
                }
                val clamped = (sample * 32768.0).toInt().coerceIn(-32768, 32767)
                buffer[i + ch] = clamped.toShort()
            }
            i += channelCount
        }
    }
}

/**
 * One second-order IIR peaking filter (Direct Form I).
 * Coefficient updates (from slider moves) and sample processing (from the
 * audio thread) can happen concurrently: `coeffs` is swapped as a single
 * @Volatile object reference, so the audio thread never sees a half-updated
 * set of coefficients, even without locking on every sample.
 */
class Biquad {
    private data class Coeffs(
        val b0: Double, val b1: Double, val b2: Double,
        val a1: Double, val a2: Double
    )

    @Volatile private var coeffs = Coeffs(1.0, 0.0, 0.0, 0.0, 0.0)
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun setPeakingCoefficients(sampleRate: Int, freq: Float, q: Float, gainDb: Float) {
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * Math.PI * freq / sampleRate
        val alpha = Math.sin(w0) / (2.0 * q)
        val cosw0 = Math.cos(w0)
        val a0 = 1.0 + alpha / a
        coeffs = Coeffs(
            b0 = (1.0 + alpha * a) / a0,
            b1 = (-2.0 * cosw0) / a0,
            b2 = (1.0 - alpha * a) / a0,
            a1 = (-2.0 * cosw0) / a0,
            a2 = (1.0 - alpha / a) / a0
        )
    }

    fun process(inSample: Double): Double {
        val c = coeffs
        val out = c.b0 * inSample + c.b1 * x1 + c.b2 * x2 - c.a1 * y1 - c.a2 * y2
        x2 = x1
        x1 = inSample
        y2 = y1
        y1 = out
        return out
    }
}
