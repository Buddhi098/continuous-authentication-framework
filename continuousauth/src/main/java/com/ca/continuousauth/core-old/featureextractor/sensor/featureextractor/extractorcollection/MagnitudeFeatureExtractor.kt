package com.ca.continuousauth.`core-old`.featureextractor.sensor.featureextractor.extractorcollection

import com.ca.continuousauth.`core-old`.featureextractor.sensor.featureextractor.SensorFeatureExtractorInterface
import kotlin.math.*

// ----------------------------
// Magnitude Features
// mean magnitude, variance
// ----------------------------
class MagnitudeFeatureExtractor : SensorFeatureExtractorInterface {
    override fun extract(samples: List<FloatArray>): FloatArray {
        if (samples.isEmpty()) return floatArrayOf()
        val magnitudes = samples.map { sample ->
            sqrt(sample.sumOf { it.toDouble().pow(2) }).toFloat()
        }
        val meanMag = magnitudes.average().toFloat()
        val varMag = magnitudes.map { (it - meanMag).pow(2) }.average().toFloat()
        val maxMag = magnitudes.maxOrNull() ?: 0f
        val minMag = magnitudes.minOrNull() ?: 0f
        return floatArrayOf(meanMag, varMag, minMag, maxMag)
    }
}