package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.sqrt

class MeanFeatureExtractor : FeatureExtractor {
    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        return try {
            if (window.isEmpty()) return emptyList()

            val axisCount = window.first().second.size
            val features = mutableListOf<Float>()

            for (axis in 0 until axisCount) {
                val values = window.map { it.second[axis] }
                if (values.isEmpty()) {
                    features.add(0f)
                    continue
                }
                // Compute mean
                val mean = values.average().toFloat()
                features.add(mean)
            }

            // -------------------------------
            // Min-Max Scaling to [0,1]
            // -------------------------------
            val minVal = features.minOrNull() ?: 0f
            val maxVal = features.maxOrNull() ?: 1f
            val range = if (maxVal - minVal == 0f) 1f else maxVal - minVal

            features.map { (it - minVal) / range }

        } catch (ex: Exception) {
            Logger.e("MeanFeatureExtractor error", ex)
            emptyList()
        }
    }
}
