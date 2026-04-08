package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Gravity removal denoiser for accelerometer signals.
 * Replicates a 2nd-order Butterworth high-pass filter applied via zero-phase filtering (filtfilt).
 *
 * @param cutoff High-pass filter cutoff frequency in Hz (default 0.3 Hz)
 * @param fs Sampling frequency in Hz (default 100 Hz)
 */
class GravityRemovalDenoiser(
    val cutoff: Double = 0.3,
    val fs: Double = 100.0
) : SensorDenoiser {

    private val b: DoubleArray
    private val a: DoubleArray

    init {
        // Calculate 2nd-order Butterworth high-pass filter coefficients
        // using the standard digital biquad filter formulas (Audio EQ Cookbook / Bilinear Transform).
        // This exactly matches scipy.signal.butter(2, Wn, btype='high')
        val w0 = 2.0 * PI * cutoff / fs
        val alpha = sin(w0) / (2.0 * (1.0 / sqrt(2.0))) // Q = 1/sqrt(2) for Butterworth
        val cosW0 = cos(w0)

        val b0 = (1.0 + cosW0) / 2.0
        val b1 = -(1.0 + cosW0)
        val b2 = (1.0 + cosW0) / 2.0
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosW0
        val a2 = 1.0 - alpha

        // Normalize coefficients by a0
        b = doubleArrayOf(b0 / a0, b1 / a0, b2 / a0)
        a = doubleArrayOf(1.0, a1 / a0, a2 / a0)
    }

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        if (window.isEmpty()) return window

        val numAxes = window[0].second.size
        if (numAxes == 0) return window

        val windowLen = window.size

        // Extract each axis into separate DoubleArrays for processing
        val signals = Array(numAxes) { DoubleArray(windowLen) }
        for (i in 0 until windowLen) {
            for (axis in 0 until numAxes) {
                signals[axis][i] = window[i].second[axis].toDouble()
            }
        }

        // Apply zero-phase forward-backward filtering to each axis independently
        val denoisedSignals = Array(numAxes) { axis ->
            filtfilt(b, a, signals[axis])
        }

        // Reconstruct the denoised window back into the expected List<Pair> format
        return window.mapIndexed { i, pair ->
            val denoisedValues = List(numAxes) { axis ->
                denoisedSignals[axis][i].toFloat()
            }
            Pair(pair.first, denoisedValues)
        }
    }

    /**
     * Replicates scipy.signal.filtfilt (Zero-phase forward and backward digital IIR filtering).
     * Uses odd-symmetric edge padding to minimize start/end transients.
     */
    private fun filtfilt(b: DoubleArray, a: DoubleArray, x: DoubleArray): DoubleArray {
        // scipy default padlen for order 2 is 3 * max(len(a), len(b)) - 1 = 3 * (3 - 1) = 6
        val padLen = 6

        // If the signal is too short to pad properly, fallback to standard one-way filtering
        if (x.size <= padLen) {
            return lfilter(b, a, x)
        }

        // 1. Pad the signal with odd reflection (mimicking scipy's 'odd' padtype)
        val padded = DoubleArray(x.size + 2 * padLen)
        System.arraycopy(x, 0, padded, padLen, x.size)

        // Pad start
        for (i in 0 until padLen) {
            padded[padLen - 1 - i] = 2.0 * x[0] - x[i + 1]
        }

        // Pad end
        for (i in 0 until padLen) {
            padded[padLen + x.size + i] = 2.0 * x[x.size - 1] - x[x.size - 2 - i]
        }

        // 2. Forward filter
        val yForward = lfilter(b, a, padded)

        // 3. Reverse the forward-filtered signal
        yForward.reverse()

        // 4. Backward filter
        val yBackward = lfilter(b, a, yForward)

        // 5. Reverse back to original chronological order
        yBackward.reverse()

        // 6. Crop out the padding to return the center signal
        val result = DoubleArray(x.size)
        System.arraycopy(yBackward, padLen, result, 0, x.size)

        return result
    }

    /**
     * Standard Linear Filter (Direct Form II Transposed).
     * Replicates scipy.signal.lfilter.
     */
    private fun lfilter(b: DoubleArray, a: DoubleArray, x: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size)
        var z1 = 0.0
        var z2 = 0.0

        for (i in x.indices) {
            val xi = x[i]
            y[i] = b[0] * xi + z1
            z1 = b[1] * xi - a[1] * y[i] + z2
            z2 = b[2] * xi - a[2] * y[i]
        }

        return y
    }
}