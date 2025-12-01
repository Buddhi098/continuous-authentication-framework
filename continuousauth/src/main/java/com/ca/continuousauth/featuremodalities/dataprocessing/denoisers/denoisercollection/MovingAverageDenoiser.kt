package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.utils.Logger

class MovingAverageDenoiser(private val avgWindowSize: Int = 3) : SensorDenoiser {

    init { require(avgWindowSize > 0) { "avgWindowSize must be > 0" } }

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        return try {
            window.mapIndexed { index, (_, rawData) ->
                val smoothedValues = rawData.indices.map { axis ->
                    val start = (index - avgWindowSize + 1).coerceAtLeast(0)
                    val end = index + 1
                    val valuesToAverage = window.subList(start, end).map { it.second[axis] }
                    valuesToAverage.average().toFloat()
                }
                window[index].first to smoothedValues
            }
        } catch (ex: Exception) {
            Logger.e("MovingAverageDenoiser error", ex)
            window // fallback: return original
        }
    }
}
