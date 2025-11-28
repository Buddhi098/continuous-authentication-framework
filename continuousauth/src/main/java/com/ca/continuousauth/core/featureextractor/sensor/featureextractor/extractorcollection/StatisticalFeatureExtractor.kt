package com.ca.continuousauth.core.featureextractor.sensor.featureextractor.extractorcollection
import com.ca.continuousauth.core.featureextractor.sensor.featureextractor.SensorFeatureExtractorInterface
import kotlin.math.*

// ----------------------------
// Statistical Features
// mean, std, min, max per axis
// ----------------------------
class StatisticalFeatureExtractor : SensorFeatureExtractorInterface {
    override fun extract(samples: List<FloatArray>): FloatArray {
        if (samples.isEmpty()) return floatArrayOf()

        val dims = samples[0].size
        val n = samples.size

        val mean = FloatArray(dims)
        val minVals = FloatArray(dims) { Float.MAX_VALUE }
        val maxVals = FloatArray(dims) { Float.MIN_VALUE }
        val std = FloatArray(dims)

        for (sample in samples) {
            for (i in 0 until dims) {
                val v = sample[i]
                mean[i] += v
                minVals[i] = min(minVals[i], v)
                maxVals[i] = max(maxVals[i], v)
            }
        }
        for (i in 0 until dims) mean[i] /= n

        for (sample in samples) {
            for (i in 0 until dims) {
                val diff = sample[i] - mean[i]
                std[i] += diff * diff
            }
        }
        for (i in 0 until dims) std[i] = sqrt(std[i] / n)

        return mean + std + minVals + maxVals
    }
}