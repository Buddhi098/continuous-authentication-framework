package com.ca.continuousauth.core.featureextractor.sensor.featureextractor

interface SensorFeatureExtractorInterface {
    fun extract(samples: List<FloatArray>): FloatArray
}