package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.utils.Logger
import kotlin.math.*

class AdvancedGyroscopeDenoiser(
    private val baseHighPassAlpha: Float = 0.05f,
    private val lowPassAlpha: Float = 0.1f,
    private val madMultiplier: Float = 6.0f,
    private val baseGain: Float = 18f,
    private val windowSize: Int = 3
) : SensorDenoiser {

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        if (window.size < windowSize) return window

        // 1. Adaptive high-pass filter with axis-wise alpha
        val acOnly = adaptiveHighPass(window, baseHighPassAlpha)

        // 2. Window intensity
        val intensity = calculateWindowIntensity(acOnly)

        // 3. MAD spike removal with intensity-aware threshold
        val spikeRemoved = removeAdaptiveMADSpikes(acOnly, intensity)

        // 4. Dual-pass EMA with intensity-adaptive smoothing
        val smoothed = dualPassSmooth(spikeRemoved, lowPassAlpha, intensity)

        // 5. Amplify subtle rotations with adjusted gain
        val amplified = adaptiveAmplify(smoothed, intensity, baseGain)

        return amplified
    }

    private fun adaptiveHighPass(window: List<Pair<Long, List<Float>>>, alpha: Float): List<Pair<Long, List<Float>>> {
        val numAxes = window[0].second.size
        val bias = MutableList(numAxes) { window[0].second[it] }

        return window.map { (ts, values) ->
            val highPass = values.mapIndexed { i, v ->
                val adaptiveAlpha = alpha + 0.05f * abs(v) // higher motion → stronger high-pass
                bias[i] = adaptiveAlpha * v + (1 - adaptiveAlpha) * bias[i]
                v - bias[i]
            }
            ts to highPass
        }
    }

    private fun calculateWindowIntensity(window: List<Pair<Long, List<Float>>>): Float {
        val flattened = window.flatMap { it.second }
        val rms = sqrt(flattened.map { it * it }.average())
        return rms.toFloat()
    }

    private fun removeAdaptiveMADSpikes(window: List<Pair<Long, List<Float>>>, intensity: Float): List<Pair<Long, List<Float>>> {
        val numAxes = window[0].second.size
        val result = window.map { it.first to it.second.toMutableList() }.toMutableList()
        val adaptiveMultiplier = madMultiplier * (1f + 0.5f * intensity)

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

    private fun dualPassSmooth(window: List<Pair<Long, List<Float>>>, alpha: Float, intensity: Float): List<Pair<Long, List<Float>>> {
        val numAxes = window[0].second.size
        val forward = MutableList(numAxes) { window[0].second[it] }

        val adaptiveAlpha = alpha * (1f - min(0.5f, intensity)) // smaller alpha for micro-movements

        val smoothed = window.map { (ts, values) ->
            val current = values.mapIndexed { i, v ->
                forward[i] = adaptiveAlpha * v + (1 - adaptiveAlpha) * forward[i]
                forward[i]
            }
            ts to current
        }

        val backward = MutableList(numAxes) { smoothed.last().second[it] }
        return smoothed.mapIndexed { idx, pair ->
            val i = smoothed.size - 1 - idx
            val current = smoothed[i].second.mapIndexed { j, v ->
                backward[j] = adaptiveAlpha * v + (1 - adaptiveAlpha) * backward[j]
                backward[j]
            }
            pair.first to current
        }
    }

    private fun adaptiveAmplify(window: List<Pair<Long, List<Float>>>, intensity: Float, baseGain: Float): List<Pair<Long, List<Float>>> {
        val gain = baseGain / (1f + 2f * intensity) // reduce gain more aggressively on high intensity
        return window.map { (ts, values) ->
            ts to values.map { v -> (tanh(v * gain.toDouble())).toFloat() }
        }
    }
}
