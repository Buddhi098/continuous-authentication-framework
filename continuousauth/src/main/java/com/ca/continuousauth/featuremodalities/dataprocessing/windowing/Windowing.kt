package com.ca.continuousauth.featuremodalities.dataprocessing.windowing

import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Windows a flow of timestamped raw data.
 *
 * @param windowSize Number of elements in each window
 * @param overlapRatio Fraction of overlap between consecutive windows (0.0 to <1.0)
 * @return Flow emitting each window as a list of Pair(timestamp, rawData)
 */
fun Flow<Pair<Long, List<Float>>>.windowedFlow(
    windowSize: Int = AuthConfigManager.config.windowSize,
    overlapRatio: Double = AuthConfigManager.config.windowOverlapRatio
): Flow<List<Pair<Long, List<Float>>>> = flow {

    require(windowSize > 0) { "windowSize must be > 0" }
    require(overlapRatio >= 0.0 && overlapRatio < 1.0) { "overlapRatio must be in [0, 1)" }

    val step = (windowSize * (1 - overlapRatio)).toInt().coerceAtLeast(1)
    val buffer = mutableListOf<Pair<Long, List<Float>>>()

    collect { item ->
        buffer.add(item)

        while (buffer.size >= windowSize) {
            val window = buffer.take(windowSize)

            // emit safely
            emit(window)
//            Logger.d("Emitting window with ${window.size} elements, first ts=${window.first().first}")

            // remove items according to step
            repeat(step) { if (buffer.isNotEmpty()) buffer.removeAt(0) }
        }
    }

    // emit remaining elements (optional)
    if (buffer.isNotEmpty()) {
        emit(buffer.toList())
        Logger.d("Emitting final partial window with ${buffer.size} elements")
    }
}
