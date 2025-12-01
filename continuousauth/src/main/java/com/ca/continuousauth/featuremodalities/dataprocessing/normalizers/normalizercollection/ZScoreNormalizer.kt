package com.ca.continuousauth.featuremodalities.dataprocessing.normalizers.normalizercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.normalizers.SensorNormalizer
import com.ca.continuousauth.utils.Logger
import kotlin.math.sqrt

class ZScoreNormalizer : SensorNormalizer {

    override fun normalizeWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        return try {
            if (window.isEmpty()) return emptyList()

            val axisCount = window.first().second.size
            val means = MutableList(axisCount) { 0f }

            // Compute mean per axis
            for ((_, values) in window) {
                for (i in values.indices) {
                    means[i] += values[i]
                }
            }
            for (i in means.indices) means[i] /= window.size

            // Compute standard deviation per axis
            val stds = MutableList(axisCount) { 0f }
            for ((_, values) in window) {
                for (i in values.indices) {
                    stds[i] += (values[i] - means[i]) * (values[i] - means[i])
                }
            }
            for (i in stds.indices) stds[i] = sqrt(stds[i] / window.size)

            // Normalize values
            window.map { (ts, values) ->
                val normalizedValues = values.indices.map { i ->
                    if (stds[i] != 0f) (values[i] - means[i]) / stds[i] else 0f
                }
                ts to normalizedValues
            }
        } catch (ex: Exception) {
            Logger.e("ZScoreNormalizer error", ex)
            window
        }
    }
}
