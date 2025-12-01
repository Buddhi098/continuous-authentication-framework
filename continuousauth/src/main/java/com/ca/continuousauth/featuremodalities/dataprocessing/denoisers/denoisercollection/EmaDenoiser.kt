package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.utils.Logger

class EmaDenoiser(private val alpha: Float = 0.3f) : SensorDenoiser {

    init { require(alpha in 0f..1f) { "alpha must be between 0 and 1" } }

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        return try {
            val emaValues = mutableListOf<List<Float>>()
            window.forEachIndexed { index, (_, rawData) ->
                val smoothed = if (index == 0) {
                    rawData
                } else {
                    rawData.indices.map { axis ->
                        alpha * rawData[axis] + (1 - alpha) * emaValues.last()[axis]
                    }
                }
                emaValues.add(smoothed)
            }
            window.mapIndexed { i, (ts, _) -> ts to emaValues[i] }
        } catch (ex: Exception) {
            Logger.e("EmaDenoiser error", ex)
            window
        }
    }
}
