package com.ca.continuousauth.featuremodalities.featurepipeline

import android.app.Activity
import android.content.Context
import android.view.View
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
import com.ca.continuousauth.featuremodalities.rawdatacollectors.GyroscopeDataCollector
import com.ca.continuousauth.featuremodalities.rawdatacollectors.MagnetometerDataCollector
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
    val normalizers: List<SensorNormalizer> = listOf(MinMaxNormalizer())
    val featureExtractors: List<FeatureExtractor> = listOf(StatisticalFeatureExtractor())

    // Safe to use rootView here
    val touchDataCollector = TouchDataCollector(touchEventFlow , sampleCollectionFrequency)

    return touchDataCollector.start()
        .windowedFlow(windowSize, windowOverlap)
        .denoisePipeline(denoisers)
        .normalizePipeline(normalizers)
        .featurePipeline(featureExtractors)
        .flowOn(dispatcher)
        .catch { ex ->
            Logger.e("Error in touch feature flow", ex)
        }

}
