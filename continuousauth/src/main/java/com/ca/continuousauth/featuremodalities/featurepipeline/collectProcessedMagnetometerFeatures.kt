package com.ca.continuousauth.featuremodalities.featurepipeline

import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection.KalmanDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featurePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.RawSequenceFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.SensorFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.windowing.windowedFlow
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/**
 * Full pipeline to process magnetometer features as a Flow.
 *
 * @param collector A function that starts raw magnetometer data streaming
 */
fun collectProcessedMagnetometerFeatures(
    collector: () -> Flow<Pair<Long, List<Float>>>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): Flow<List<Float>> {

    val windowSize = AuthConfigManager.config.windowSize
    val windowOverlap = AuthConfigManager.config.windowOverlapRatio

    val denoisers: List<SensorDenoiser> = listOf(KalmanDenoiser())

    val featureExtractors: List<FeatureExtractor> = listOf(RawSequenceFeatureExtractor())

    return collector()
        .windowedFlow(windowSize, windowOverlap)
        .denoisePipeline(denoisers)
        .featurePipeline(featureExtractors)
        .flowOn(dispatcher)
        .catch { ex -> Logger.e("Error in magnetometer feature flow", ex) } as Flow<List<Float>>
}