package com.ca.continuousauth.featuremodalities.dataprocessing.normalizers

import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun Flow<List<Pair<Long, List<Float>>>>.normalizePipeline(
    normalizers: List<SensorNormalizer>
): Flow<List<Pair<Long, List<Float>>>> {
    return this.map { window ->
        try {
            var normalizedWindow = window
            for (normalizer in normalizers) {
                normalizedWindow = normalizer.normalizeWindow(normalizedWindow)
            }
            normalizedWindow
        } catch (ex: Exception) {
            Logger.e("normalizePipeline error", ex)
            window
        }
    }
}
