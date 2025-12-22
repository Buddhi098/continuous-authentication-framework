package com.ca.continuousauth.featuremodalities.featurepipeline

import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection.LowPassFilterDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featurePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.MeanFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.StatisticalFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.windowing.windowedFlow
import com.ca.continuousauth.featuremodalities.rawdatacollectors.TouchDataCollector
import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/**
 * Full pipeline to collect accelerometer features as a Flow.
 */
fun collectTouchDynamicFeature(
    touchEventFlow : Flow<TouchEventData>?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): Flow<List<Float>> {

    val sampleCollectionFrequency: Int = AuthConfigManager.config.sampleCollectionFrequencyHz
    val windowSize: Int = AuthConfigManager.config.windowSize
    val windowOverlap: Double = AuthConfigManager.config.windowOverlapRatio
    val denoisers: List<SensorDenoiser> = listOf(LowPassFilterDenoiser())
    val featureExtractors: List<FeatureExtractor> = listOf(StatisticalFeatureExtractor())

    // Safe to use rootView here
    val touchDataCollector = TouchDataCollector(touchEventFlow , sampleCollectionFrequency)

    return touchDataCollector.start()
        .windowedFlow(windowSize, windowOverlap)
        .denoisePipeline(denoisers)
        .featurePipeline(featureExtractors)
        .flowOn(dispatcher)
        .catch { ex ->
            Logger.e("Error in touch feature flow", ex)
        }

}
