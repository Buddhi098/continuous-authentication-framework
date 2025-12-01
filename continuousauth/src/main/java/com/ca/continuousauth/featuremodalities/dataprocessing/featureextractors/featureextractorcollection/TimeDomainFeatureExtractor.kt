package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.abs

class TimeDomainFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        return try {
            if (window.isEmpty()) return emptyList()
            val axisCount = window.first().second.size
            val features = mutableListOf<Float>()

            for (axis in 0 until axisCount) {
                val values = window.map { it.second[axis] }

                features.add(maxSlope(values))
                features.add(avgSlope(values))
                features.add(totalChange(values))
            }

            features
        } catch (ex: Exception) {
            Logger.e("TimeDomainFeatureExtractor error", ex)
            emptyList()
        }
    }

    private fun maxSlope(values: List<Float>): Float {
        if (values.size < 2) return 0f
        return values.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0f
    }

    private fun avgSlope(values: List<Float>): Float {
        if (values.size < 2) return 0f
        return values.zipWithNext { a, b -> abs(b - a) }.average().toFloat()
    }

    private fun totalChange(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        return abs(values.last() - values.first())
    }
}
