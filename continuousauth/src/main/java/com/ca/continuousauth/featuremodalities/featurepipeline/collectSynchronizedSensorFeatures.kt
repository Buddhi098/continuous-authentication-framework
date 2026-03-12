package com.ca.continuousauth.featuremodalities.featurepipeline

import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection.KalmanDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featurePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.RawSequenceFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.windowing.windowedFlow
import com.ca.continuousauth.featuremodalities.featurefusion.FusedFeatureBuilder
import com.ca.continuousauth.featuremodalities.synchronization.SynchronizedSample
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/**
 * Processes a time-synchronized sensor flow through windowing, denoising,
 * and feature extraction, then fuses gyro and accel channels.
 *
 * Magnetometer is currently disabled as per user request.
 *
 * @param synchronizedFlow A flow of time-aligned [SynchronizedSample]s.
 * @param dispatcher       Coroutine dispatcher for background processing.
 * @return A [Flow] of fused feature vectors.
 */
fun collectSynchronizedSensorFeatures(
    synchronizedFlow: Flow<SynchronizedSample>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): Flow<Any> {

    val windowSize = AuthConfigManager.config.windowSize
    val windowOverlap = AuthConfigManager.config.windowOverlapRatio

    val denoisers: List<SensorDenoiser> = listOf(KalmanDenoiser())
    val featureExtractors: List<FeatureExtractor> = listOf(RawSequenceFeatureExtractor())

    val sharedFlow = synchronizedFlow.shareIn(
        scope = kotlinx.coroutines.CoroutineScope(dispatcher),
        started = SharingStarted.Eagerly,
        replay = 0
    )

    fun sensorPipeline(
        selector: (SynchronizedSample) -> List<Float>,
        sensorName: String
    ): Flow<Any> {
        return sharedFlow
            .map { sample -> sample.timestamp to selector(sample) }
            .windowedFlow(windowSize, windowOverlap)
            .denoisePipeline(denoisers)
            .featurePipeline(featureExtractors)
            .flowOn(dispatcher)
            .catch { ex -> Logger.e("Error in $sensorName synchronized feature flow", ex) }
    }

    val gyroFeatures  = sensorPipeline({ it.gyro },  "gyroscope")
    val accelFeatures = sensorPipeline({ it.accel }, "accelerometer")

    return gyroFeatures.zip(accelFeatures) { g, a ->
        val sensorMap = mapOf(
            "gyro" to g,
            "totalAccel" to a
        )
        FusedFeatureBuilder.buildFusedFeatures(sensorMap)
    }
    .flowOn(dispatcher)
    .catch { ex -> Logger.e("Error in synchronized sensor fusion flow", ex) }
}
