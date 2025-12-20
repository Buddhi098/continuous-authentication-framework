package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.utils.Logger
import kotlin.math.abs

/**
 * High-Pass Filter Denoiser for sensor windows.
 *
 * Removes low-frequency / large gesture components to isolate micro-movements.
 * alpha: smoothing factor (0..1). Lower alpha keeps more high-frequency details.
 */
class HighPassFilterDenoiser(private val alpha: Float = 0.2f) : SensorDenoiser {

    init { require(alpha in 0f..1f) { "alpha must be between 0 and 1" } }

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        return try {
            if (window.isEmpty()) return emptyList()

            val filteredValues = mutableListOf<List<Float>>()

            window.forEachIndexed { index, (_, rawData) ->
                val filtered = if (index == 0) {
                    rawData
                } else {
                    rawData.indices.map { axis ->
                        // High-pass filter: y[i] = alpha * (y[i-1] + x[i] - x[i-1])
                        alpha * (filteredValues.last()[axis] + rawData[axis] - window[index - 1].second[axis])
                    }
                }
                filteredValues.add(filtered)
            }

            // Return window with original timestamps and filtered data
            window.mapIndexed { i, (ts, _) -> ts to filteredValues[i] }
        } catch (ex: Exception) {
            Logger.e("HighPassFilterDenoiser error", ex)
            window
        }
    }
}
