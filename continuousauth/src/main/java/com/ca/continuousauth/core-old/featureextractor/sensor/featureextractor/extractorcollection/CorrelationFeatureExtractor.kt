package com.ca.continuousauth.`core-old`.featureextractor.sensor.featureextractor.extractorcollection

import com.ca.continuousauth.`core-old`.featureextractor.sensor.featureextractor.SensorFeatureExtractorInterface
import kotlin.math.sqrt

// ----------------------------
// Correlation Features
// Pearson correlation between each axis
// ----------------------------
class CorrelationFeatureExtractor : SensorFeatureExtractorInterface {
    override fun extract(samples: List<FloatArray>): FloatArray {
        if (samples.isEmpty()) return floatArrayOf()
        val dims = samples[0].size
        val n = samples.size

        // Compute mean per axis
        val mean = FloatArray(dims)
        for (sample in samples) {
            for (i in 0 until dims) mean[i] += sample[i]
        }
        for (i in 0 until dims) mean[i] /= n

        val features = mutableListOf<Float>()

        for (i in 0 until dims) {
            for (j in i + 1 until dims) {
                var num = 0f
                var denom1 = 0f
                var denom2 = 0f
                for (k in 0 until n) {
                    val xi = samples[k][i] - mean[i]
                    val xj = samples[k][j] - mean[j]
                    num += xi * xj
                    denom1 += xi * xi
                    denom2 += xj * xj
                }
                val corr = if (denom1 != 0f && denom2 != 0f) num / sqrt(denom1 * denom2) else 0f
                features.add(corr)
            }
        }
        return features.toFloatArray()
    }
}