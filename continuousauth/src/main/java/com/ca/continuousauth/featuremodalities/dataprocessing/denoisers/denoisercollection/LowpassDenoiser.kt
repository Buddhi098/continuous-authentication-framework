package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import kotlin.math.*

class LowpassDenoiser(
    private val cutoff: Double = 20.0,
    private val fs: Double = 100.0,
    private val order: Int = 4
) : SensorDenoiser {

    private val b: DoubleArray
    private val a: DoubleArray

    init {
        // Calculate the normalized frequency (Nyquist is fs / 2)
        val nyquist = 0.5 * fs
        val normalizedCutoff = cutoff / nyquist

        // Compute Butterworth coefficients (b, a)
        val coeffs = designButterworthLowpass(order, normalizedCutoff)
        b = coeffs.first
        a = coeffs.second
    }

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        if (window.isEmpty()) return window

        val numSamples = window.size
        val numAxes = window[0].second.size

        // 1. Transpose data: Convert row-based (time) to column-based (axis)
        // We use Double for DSP precision, will convert back to Float later
        val axisData = Array(numAxes) { DoubleArray(numSamples) }

        for (i in 0 until numSamples) {
            val vals = window[i].second
            for (j in 0 until numAxes) {
                axisData[j][i] = vals[j].toDouble()
            }
        }

        // 2. Apply filtfilt (zero-phase filter) along each axis
        val denoisedAxisData = Array(numAxes) { DoubleArray(numSamples) }
        for (axis in 0 until numAxes) {
            denoisedAxisData[axis] = filtfilt(b, a, axisData[axis])
        }

        // 3. Reconstruct the window (Transpose back)
        return window.mapIndexed { i, pair ->
            val timestamp = pair.first
            val denoisedValues = ArrayList<Float>(numAxes)
            for (axis in 0 until numAxes) {
                denoisedValues.add(denoisedAxisData[axis][i].toFloat())
            }
            Pair(timestamp, denoisedValues)
        }
    }

    /**
     * Implements a zero-phase forward and reverse digital filter.
     * Corresponds to scipy.signal.filtfilt.
     */
    private fun filtfilt(b: DoubleArray, a: DoubleArray, x: DoubleArray): DoubleArray {
        val n = x.size

        // If the signal is too short for the filter, return original
        if (n <= a.size) return x

        // 1. Filter Forward
        // To reduce edge artifacts, we ideally pad. For this implementation,
        // we initialize the filter state based on the first sample to reduce transient.
        val yForward = lfilter(b, a, x, x[0])

        // 2. Reverse the result
        yForward.reverse()

        // 3. Filter Backward (which is technically forward on the reversed data)
        // Initialize state based on the "new" first sample (which was the last sample)
        val yBackward = lfilter(b, a, yForward, yForward[0])

        // 4. Reverse back to original orientation
        yBackward.reverse()

        return yBackward
    }

    /**
     * Standard Linear Filter (Difference Equation).
     * Corresponds to scipy.signal.lfilter.
     * @param zi The initial value to steady-state the filter (simple step response initialization).
     */
    private fun lfilter(b: DoubleArray, a: DoubleArray, x: DoubleArray, zi: Double): DoubleArray {
        val y = DoubleArray(x.size)
        // Normalized coefficients (usually a[0] is 1.0, but just in case)
        val a0 = a[0]

        // Filter delays
        val delays = DoubleArray(max(a.size, b.size))

        // Initialize delays for steady state (simple approach matching signal start)
        // Usually, zi is calculated via matrices, but scaling by the first sample is a robust approximation
        // for simple generic implementations without linear algebra libraries.
        val initialScale = zi
        for (i in delays.indices) {
            delays[i] = initialScale // Simplified initial condition
        }

        // Apply difference equation
        // y[n] = b[0]x[n] + ... + b[M]x[n-M] - a[1]y[n-1] - ... - a[N]y[n-N]
        for (i in x.indices) {
            var sum = 0.0

            // Apply numerator (b)
            for (k in b.indices) {
                val input = if (i - k >= 0) x[i - k] else delays[k]
                sum += b[k] * input
            }

            // Apply denominator (a) - note: a[0] corresponds to y[n]
            for (k in 1 until a.size){
                val output = if (i - k >= 0) y[i - k] else delays[k]
                sum -= a[k] * output
            }

            y[i] = sum / a0
        }
        return y
    }

    /**
     * Computes Butterworth coefficients (b, a) for a Lowpass filter.
     * @param order Filter order
     * @param wn Normalized cutoff frequency (0.0 to 1.0)
     */
    private fun designButterworthLowpass(order: Int, wn: Double): Pair<DoubleArray, DoubleArray> {
        // Pre-warp frequency
        val omega = tan(Math.PI * wn / 2.0)
        val omega2 = omega * omega

        // Calculate poles on the unit circle
        val numPoles = order
        val realPoles = ArrayList<Double>()
        val complexPoles = ArrayList<Pair<Double, Double>>() // real, imag

        for (k in 0 until numPoles) {
            val theta = (2.0 * k + 1.0) * Math.PI / (2.0 * numPoles)
            val real = -sin(theta)
            val imag = cos(theta)

            // Bilinear transform s -> z
            // z = (1 + s) / (1 - s) scaled by omega
            val sReal = real * omega
            val sImag = imag * omega

            val denom = (1.0 - sReal).pow(2) + sImag.pow(2)
            val zReal = ((1.0 + sReal) * (1.0 - sReal) - sImag * sImag) / denom
            val zImag = (sImag * (1.0 - sReal) - (1.0 + sReal) * (-sImag)) / denom

            if (abs(zImag) < 1e-10) {
                realPoles.add(zReal)
            } else if (zImag > 0) {
                // Keep only positive imaginary part for conjugate pairs
                complexPoles.add(zReal to zImag)
            }
        }

        // Convert Poles to Polynomial (a)
        // Start with polynomial: 1.0
        var aPoly = doubleArrayOf(1.0)

        // Convolve real poles: (z - p)
        for (p in realPoles) {
            // convolve aPoly with [1, -p]
            aPoly = convolve(aPoly, doubleArrayOf(1.0, -p))
        }
        // Convolve complex poles: (z - (r+ji))(z - (r-ji)) = z^2 - 2rz + (r^2+i^2)
        for ((r, i) in complexPoles) {
            val magSq = r*r + i*i
            aPoly = convolve(aPoly, doubleArrayOf(1.0, -2.0 * r, magSq))
        }

        // Calculate Gain (K) for numerator (b)
        // For Lowpass, gain ensures H(z=1) = 1.0.
        // At z=1 (DC), magnitude is Sum(b) / Sum(a)
        // Standard Butterworth numerator is proportional to (1+z^-1)^n
        // We calculate the recursive (1+1)^n logic via convolution of roots at -1

        var bPoly = doubleArrayOf(1.0)
        for (i in 0 until order) {
            bPoly = convolve(bPoly, doubleArrayOf(1.0, 1.0))
        }

        // Normalize gain
        val sumA = aPoly.sum()
        val sumB = bPoly.sum()
        val gain = sumA / sumB

        for (i in bPoly.indices) {
            bPoly[i] *= gain
        }

        return Pair(bPoly, aPoly)
    }

    // Helper to convolve two arrays (polynomial multiplication)
    private fun convolve(u: DoubleArray, v: DoubleArray): DoubleArray {
        val n = u.size
        val m = v.size
        val result = DoubleArray(n + m - 1)
        for (i in 0 until n) {
            for (j in 0 until m) {
                result[i + j] += u[i] * v[j]
            }
        }
        return result
    }
}