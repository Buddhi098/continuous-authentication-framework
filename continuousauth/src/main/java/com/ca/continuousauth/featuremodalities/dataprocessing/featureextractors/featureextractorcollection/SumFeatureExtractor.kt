package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger

class SumFeatureExtractor : FeatureExtractor {

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

                // Compute sum
                val sum = values.sum()
                features.add(sum)
            }

            features

        } catch (ex: Exception) {
            Logger.e("SumFeatureExtractor error", ex)
            emptyList()
        }
    }
}