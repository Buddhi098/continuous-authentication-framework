package com.ca.continuousauth.featuremodalities.featurepipeline

import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.TremorBandPassDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection.LowpassDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featurePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.AccFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.AccelerometerFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.BiometricAxisMicroMovementExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.SensorFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.windowing.windowedFlow
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/**
 * Full pipeline to process linear accelerometer features as a Flow.
 */
fun collectProcessedLinearAccelFeatures(
    collector: () -> Flow<Pair<Long, List<Float>>>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): Flow<List<Float>> {

    val windowSize: Int = AuthConfigManager.config.windowSize
    val windowOverlap: Double = AuthConfigManager.config.windowOverlapRatio

    val denoisers: List<SensorDenoiser> = listOf(LowpassDenoiser())
    // Example if needed later:
    // listOf(LowpassDenoiser(), BandpassHandMovementDenoiser())

    val featureExtractors: List<FeatureExtractor> = listOf(
        SensorFeatureExtractor()
    )

    return collector()
        .windowedFlow(windowSize, windowOverlap)
        .denoisePipeline(denoisers)
        .featurePipeline(featureExtractors)
        .flowOn(dispatcher)
        .catch { ex ->
            Logger.e("Error in linear accelerometer feature flow", ex)
        }
}
