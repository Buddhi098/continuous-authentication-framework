package com.ca.continuousauth.featuremodalities.featurepipeline

import android.content.Context
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection.LowPassFilterDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featurePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.StatisticalFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.normalizers.SensorNormalizer
import com.ca.continuousauth.featuremodalities.dataprocessing.normalizers.normalizePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.normalizers.normalizercollection.MinMaxNormalizer
import com.ca.continuousauth.featuremodalities.dataprocessing.windowing.windowedFlow
import com.ca.continuousauth.featuremodalities.rawdatacollectors.AccelerometerDataCollector
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/**
 * Full pipeline to collect accelerometer features as a Flow.
 */
fun collectProcessedAccelFeatures(
    context: Context,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): Flow<List<Float>> {

    val sampleCollectionFrequency: Int = AuthConfigManager.config.sampleCollectionFrequencyHz
    val windowSize: Int = AuthConfigManager.config.windowSize
    val windowOverlap: Double = AuthConfigManager.config.windowOverlapRatio
    val denoisers: List<SensorDenoiser> = listOf(LowPassFilterDenoiser())
    val normalizers: List<SensorNormalizer> = listOf(MinMaxNormalizer())
    val featureExtractors: List<FeatureExtractor> = listOf(StatisticalFeatureExtractor())

    val accelerometerCollector = AccelerometerDataCollector(context,sampleCollectionFrequency, dispatcher)

    return accelerometerCollector.start()
        .windowedFlow(windowSize, windowOverlap)
        .denoisePipeline(denoisers)
        .normalizePipeline(normalizers)
        .featurePipeline(featureExtractors)
        .flowOn(dispatcher)
        .catch { ex ->
            Logger.e("Error in accelerometer feature flow", ex)
        }
}
