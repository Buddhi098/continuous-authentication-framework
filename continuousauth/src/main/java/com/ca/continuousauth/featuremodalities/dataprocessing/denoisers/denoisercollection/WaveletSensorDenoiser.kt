package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import jwave.transforms.FastWaveletTransform
import jwave.transforms.wavelets.daubechies.Daubechies4
import kotlin.math.abs

/**
 * Wavelet denoiser for sensor data (accelerometer or gyroscope)
 * using Daubechies4 wavelet and soft thresholding.
 */
class WaveletSensorDenoiser(
    private val thresholdMultiplier: Float = 2f
) : SensorDenoiser {

    // Proper JWave transform
    private val transform = FastWaveletTransform(Daubechies4())

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        if (window.isEmpty()) return emptyList()

        val numAxes = window[0].second.size
        val denoisedWindow = MutableList(window.size) { MutableList(numAxes) { 0f } }

        for (axis in 0 until numAxes) {
            // Defensive: skip if any data is null
            if (window.any { it.second.size <= axis }) continue

            // Extract axis signal
            val signal = window.map { it.second[axis].toDouble() }.toDoubleArray()

            // Skip empty signal
            if (signal.isEmpty()) continue

            // Forward wavelet transform
            val coeffs = transform.forward(signal)

            // MAD-based noise estimate
            val absCoeffs = coeffs.map { abs(it) }.sorted()
            val median = absCoeffs[absCoeffs.size / 2]
            val sigma = median / 0.6745

            val threshold = thresholdMultiplier * sigma

            // Soft thresholding
            val thresholded = coeffs.map { c ->
                when {
                    c > threshold -> c - threshold
                    c < -threshold -> c + threshold
                    else -> 0.0
                }
            }.toDoubleArray()

            // Inverse transform
            val denoisedSignal = transform.reverse(thresholded)

            // Store denoised values
            for (i in denoisedSignal.indices) {
                denoisedWindow[i][axis] = denoisedSignal[i].toFloat()
            }
        }

        // Combine timestamps with denoised data
        return window.mapIndexed { idx, pair -> pair.first to denoisedWindow[idx] }
    }
}
