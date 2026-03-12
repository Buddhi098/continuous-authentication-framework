package com.ca.continuousauth.featuremodalities.dataprocessing.windowing

import com.ca.continuousauth.config.AuthConfigManager
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
    val buffer = ArrayDeque<Pair<Long, List<Float>>>(windowSize + step)

    collect { item ->
        buffer.addLast(item)

        while (buffer.size >= windowSize) {
            // Extract exactly windowSize elements without copying the entire buffer
            val window = ArrayList<Pair<Long, List<Float>>>(windowSize)
            val iter = buffer.iterator()
            repeat(windowSize) { window.add(iter.next()) }

            emit(window)

            // remove items according to step — O(1) per removeFirst()
            repeat(step) { if (buffer.isNotEmpty()) buffer.removeFirst() }
        }
    }

    // Drop any remaining partial window — emitting fewer than windowSize rows
    // would produce a feature matrix with wrong dimensions, causing a shape
    // mismatch with the model that expects exactly windowSize timesteps.
}
