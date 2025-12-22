package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.pow
import kotlin.math.sqrt

class StatisticalFeatureExtractor : FeatureExtractor {

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
                val variance = values.map { (it - mean).pow(2) }.average().toFloat()
//                val std = sqrt(variance)
//                val min = values.minOrNull() ?: 0f
//                val max = values.maxOrNull() ?: 0f
                val rms = sqrt(values.map { it * it }.average().toFloat())

//                features.addAll(listOf(mean, std, variance, min, max, rms))
                features.addAll(listOf(mean, variance, rms))
            }

            features
        } catch (ex: Exception) {
            Logger.e("StatisticalFeatureExtractor error", ex)
            emptyList()
        }
    }
}
