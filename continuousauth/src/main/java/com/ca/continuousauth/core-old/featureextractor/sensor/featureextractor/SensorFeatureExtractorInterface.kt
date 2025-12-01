package com.ca.continuousauth.`core-old`.featureextractor.sensor.featureextractor

interface SensorFeatureExtractorInterface {
    fun extract(samples: List<FloatArray>): FloatArray
}