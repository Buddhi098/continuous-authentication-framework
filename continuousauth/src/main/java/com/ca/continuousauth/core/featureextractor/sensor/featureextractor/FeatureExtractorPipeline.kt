package com.ca.continuousauth.core.featureextractor.sensor.featureextractor

// ----------------------------
// Feature Extractor Pipeline
// Chain multiple extractors
// ----------------------------
class FeatureExtractorPipeline(private val extractors: List<SensorFeatureExtractorInterface>) {
    fun extractAll(samples: List<FloatArray>): FloatArray {
        return extractors.flatMap { it.extract(samples).toList() }.toFloatArray()
    }
}