package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.utils.Logger
import kotlin.math.*

class AdvancedGyroscopeDenoiser(
    private val baseHighPassAlpha: Float = 0.05f,  // Base alpha for drift removal
    private val lowPassAlpha: Float = 0.25f,      // EMA smoothing factor
    private val madMultiplier: Float = 3.0f,      // Spike threshold multiplier
    private val baseGain: Float = 12f,            // Gain for subtle rotations
    private val windowSize: Int = 5               // Context for MAD
) : SensorDenoiser {

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        if (window.size < windowSize) return window

        val startTime = System.nanoTime()

        // 1. Adaptive high-pass filter to remove drift/bias
        val acOnly = adaptiveHighPass(window, baseHighPassAlpha)

        // 2. Intensity measurement
        val intensity = calculateWindowIntensity(acOnly)

        // 3. MAD-based spike removal with adaptive threshold
        val spikeRemoved = removeAdaptiveMADSpikes(acOnly, intensity)

        // 4. Dual-pass EMA smoothing
        val smoothed = dualPassSmooth(spikeRemoved, lowPassAlpha)

        // 5. Adaptive amplification for micro-rotations
        val amplified = adaptiveAmplify(smoothed, intensity, baseGain)

        val endTime = System.nanoTime()
        val durationMs = (endTime - startTime) / 1_000_000.0
        Logger.d("MicroGyroscopeDenoiser -> denoiseWindow execution time: %.3f ms".format(durationMs))

        return amplified
    }

    /** High-pass to remove drift/gravity */
    private fun adaptiveHighPass(
        window: List<Pair<Long, List<Float>>>,
        alpha: Float
    ): List<Pair<Long, List<Float>>> {
        val numAxes = window[0].second.size
        val bias = MutableList(numAxes) { window[0].second[it] }

        return window.map { (ts, values) ->
            val highPass = values.mapIndexed { i, v ->
                bias[i] = alpha * v + (1 - alpha) * bias[i]
                v - bias[i]
            }
            ts to highPass
        }
    }

    /** Estimate average intensity of the window */
    private fun calculateWindowIntensity(window: List<Pair<Long, List<Float>>>): Float {
        val flattened = window.flatMap { it.second }
        return sqrt(flattened.map { it * it }.average()).toFloat()
    }

    /** MAD-based spike removal with adaptive threshold based on intensity */
    private fun removeAdaptiveMADSpikes(
        window: List<Pair<Long, List<Float>>>,
        intensity: Float
    ): List<Pair<Long, List<Float>>> {
        val numAxes = window[0].second.size
        val result = window.map { it.first to it.second.toMutableList() }.toMutableList()

        // Adaptive multiplier: more aggressive for high intensity, gentle for low intensity
        val adaptiveMultiplier = madMultiplier * (1f + intensity)

        for (axis in 0 until numAxes) {
            for (i in window.indices) {
                val start = max(0, i - windowSize / 2)
                val end = min(window.size - 1, i + windowSize / 2)
                val neighborhood = (start..end).map { window[it].second[axis] }.sorted()
                val median = neighborhood[neighborhood.size / 2]
                val mad = neighborhood.map { abs(it - median) }.sorted()[neighborhood.size / 2]
                val threshold = (mad + 1e-6f) * adaptiveMultiplier

                val currentVal = window[i].second[axis]
                if (abs(currentVal - median) > threshold) {
                    // Replace with median or mean of neighbors to preserve micro-change
                    val neighbors = listOfNotNull(
                        result.getOrNull(i - 1)?.second?.get(axis),
                        result.getOrNull(i + 1)?.second?.get(axis)
                    )
                    result[i].second[axis] = if (neighbors.isNotEmpty()) neighbors.average().toFloat() else median
                }
            }
        }
        return result
    }

    /** Dual-pass EMA smoothing */
    private fun dualPassSmooth(window: List<Pair<Long, List<Float>>>, alpha: Float): List<Pair<Long, List<Float>>> {
        val numAxes = window[0].second.size
        val forward = MutableList(numAxes) { window[0].second[it] }

        val smoothed = window.map { (ts, values) ->
            val current = values.mapIndexed { i, v ->
                forward[i] = alpha * v + (1 - alpha) * forward[i]
                forward[i]
            }
            ts to current
        }

        // Backward pass to reduce phase shift
        val backward = MutableList(numAxes) { smoothed.last().second[it] }
        return smoothed.mapIndexed { idx, pair ->
            val i = smoothed.size - 1 - idx
            val current = smoothed[i].second.mapIndexed { j, v ->
                backward[j] = alpha * v + (1 - alpha) * backward[j]
                backward[j]
            }
            pair.first to current
        }
    }

    /** Amplify micro-rotations with intensity-adaptive gain */
    private fun adaptiveAmplify(
        window: List<Pair<Long, List<Float>>>,
        intensity: Float,
        baseGain: Float
    ): List<Pair<Long, List<Float>>> {
        // Low intensity → higher gain, High intensity → lower gain to avoid macro jumps
        val gain = baseGain / (1f + intensity)

        return window.map { (ts, values) ->
            ts to values.map { v -> (tanh(v * gain.toDouble())).toFloat() }
        }
    }
}
