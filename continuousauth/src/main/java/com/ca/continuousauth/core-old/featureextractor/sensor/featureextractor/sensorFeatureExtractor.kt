package com.ca.continuousauth.`core-old`.featureextractor.sensor.featureextractor

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import com.ca.continuousauth.utils.Logger // Assuming you have a Logger utility

fun sensorFeatureExtractor(
    batchFlow: Flow<List<FloatArray>>,
    pipeline: FeatureExtractorPipeline,
    enableLogging: Boolean = false
): Flow<FloatArray> {
    return batchFlow
        .filter { it.isNotEmpty() }
        .map { batch ->
            if (enableLogging) {
                Logger.d("SensorFeatureExtractor - Received batch of size: ${batch.size}")
            }

            val featureVector = pipeline.extractAll(batch)

            if (enableLogging) {
                Logger.d("SensorFeatureExtractor - Feature vector size: ${featureVector.size}")
                Logger.d("SensorFeatureExtractor - Feature vector: ${featureVector.joinToString(", ")}")
            }

            featureVector
        }
        .flowOn(Dispatchers.Default)
}
