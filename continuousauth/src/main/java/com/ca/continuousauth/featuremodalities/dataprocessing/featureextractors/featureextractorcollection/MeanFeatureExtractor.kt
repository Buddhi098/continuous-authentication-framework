package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.pow
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
                    features.addAll(List(6) { 0f }) // mean, std, var, min, max, rms
                    continue
                }
                val mean = values.average().toFloat()
                features.addAll(listOf(mean))
            }
            features
        } catch (ex: Exception) {
            Logger.e("StatisticalFeatureExtractor error", ex)
            emptyList()
        }
    }
}