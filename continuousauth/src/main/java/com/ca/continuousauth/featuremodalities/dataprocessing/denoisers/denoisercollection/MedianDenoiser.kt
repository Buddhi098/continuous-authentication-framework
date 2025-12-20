package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.utils.Logger

class MedianDenoiser(private val windowSize: Int = 5) : SensorDenoiser {

    init { require(windowSize > 0) { "windowSize must be > 0" } }

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        return try {
            window.mapIndexed { index, (_, rawData) ->
                val medianValues = rawData.indices.map { axis ->
                    val start = (index - windowSize + 1).coerceAtLeast(0)
                    val valuesToConsider = window.subList(start, index + 1).map { it.second[axis] }.sorted()
                    valuesToConsider[valuesToConsider.size / 2]
                }
                window[index].first to medianValues
            }
        } catch (ex: Exception) {
            Logger.e("MedianDenoiser error", ex)
            window
        }
    }
}
