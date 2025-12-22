package com.ca.continuousauth.featuremodalities.featurepipeline

import AdvancedAccelerometerDenoiser
import android.content.Context
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection.HighPassFilterDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection.LowPassFilterDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection.MedianDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featurePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.AccelerometerFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.BiometricAxisMicroMovementExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.FrequencyDomainFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.MeanFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.MicroMovementFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.StatisticalFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.TimeDomainFeatureExtractor
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
    val denoisers: List<SensorDenoiser> = listOf(AdvancedAccelerometerDenoiser())
    val featureExtractors: List<FeatureExtractor> = listOf(AccelerometerFeatureExtractor() ,
        BiometricAxisMicroMovementExtractor())

    val accelerometerCollector = AccelerometerDataCollector(context,sampleCollectionFrequency, dispatcher)

    return accelerometerCollector.start()
        .windowedFlow(windowSize, windowOverlap)
        .denoisePipeline(denoisers)
        .featurePipeline(featureExtractors)
        .flowOn(dispatcher)
        .catch { ex ->
            Logger.e("Error in accelerometer feature flow", ex)
        }
}
