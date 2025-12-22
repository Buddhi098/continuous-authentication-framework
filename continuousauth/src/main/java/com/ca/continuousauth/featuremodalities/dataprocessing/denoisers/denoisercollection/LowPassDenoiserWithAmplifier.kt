package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers

import kotlin.math.*

/**
 * LowPassDenoiserWithAmplifier
 *
 * Applies exponential smoothing (low-pass filter) to remove high-frequency noise
 * and optionally amplifies micro-level sensor signals.
 */
class LowPassDenoiserWithAmplifier(
    private val alpha: Float = 0.6f,   // smoothing factor (0 < alpha < 1)
    private val gain: Float = 10f      // amplification factor for micro-movements
) : SensorDenoiser {

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        if (window.isEmpty()) return emptyList()

        val denoised = mutableListOf<Pair<Long, List<Float>>>()
        val numAxes = window[0].second.size
        val smoothed = MutableList(numAxes) { 0f }

        for ((timestamp, values) in window) {
            val filtered = values.mapIndexed { i, v ->
                // Low-pass smoothing (EMA)
                smoothed[i] = alpha * smoothed[i] + (1 - alpha) * v
                // Amplify micro-level motion
                smoothed[i] * gain
            }
            denoised.add(timestamp to filtered)
        }

        return denoised
    }
}
