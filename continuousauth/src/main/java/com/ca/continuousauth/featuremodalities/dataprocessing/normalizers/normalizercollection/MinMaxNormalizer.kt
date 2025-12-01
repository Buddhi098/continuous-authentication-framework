package com.ca.continuousauth.featuremodalities.dataprocessing.normalizers.normalizercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.normalizers.SensorNormalizer
import com.ca.continuousauth.utils.Logger
import kotlin.math.max
import kotlin.math.min

class MinMaxNormalizer(
    private val minRange: Float = 0f,
    private val maxRange: Float = 1f
) : SensorNormalizer {

    init {
        require(maxRange > minRange) { "maxRange must be greater than minRange" }
    }

    override fun normalizeWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        return try {
            if (window.isEmpty()) return emptyList()

            val axisCount = window.first().second.size
            val mins = MutableList(axisCount) { Float.MAX_VALUE }
            val maxs = MutableList(axisCount) { Float.MIN_VALUE }

            // Compute min and max per axis
            for ((_, values) in window) {
                for (i in values.indices) {
                    mins[i] = min(mins[i], values[i])
                    maxs[i] = max(maxs[i], values[i])
                }
            }

            // Normalize each value
            window.map { (ts, values) ->
                val normalizedValues = values.indices.map { i ->
                    val denominator = maxs[i] - mins[i]
                    if (denominator != 0f) {
                        ((values[i] - mins[i]) / denominator) * (maxRange - minRange) + minRange
                    } else {
                        minRange // fallback if all values same
                    }
                }
                ts to normalizedValues
            }
        } catch (ex: Exception) {
            Logger.e("MinMaxNormalizer error", ex)
            window // fallback: return original window
        }
    }
}
