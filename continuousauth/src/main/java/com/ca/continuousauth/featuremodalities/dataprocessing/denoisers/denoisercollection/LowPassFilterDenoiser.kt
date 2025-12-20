package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.utils.Logger

class LowPassFilterDenoiser(private val alpha: Float = 0.4f) : SensorDenoiser {

    init { require(alpha in 0f..1f) { "alpha must be between 0 and 1" } }

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        return try {
            val filteredValues = mutableListOf<List<Float>>()
            window.forEachIndexed { index, (_, rawData) ->
                val filtered = if (index == 0) {
                    rawData
                } else {
                    rawData.indices.map { axis ->
                        alpha * rawData[axis] + (1 - alpha) * filteredValues.last()[axis]
                    }
                }
                filteredValues.add(filtered)
            }
            window.mapIndexed { i, (ts, _) -> ts to filteredValues[i] }
        } catch (ex: Exception) {
            Logger.e("LowPassFilterDenoiser error", ex)
            window
        }
    }
}
