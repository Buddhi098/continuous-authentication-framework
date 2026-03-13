package com.ca.continuousauth.featuremodalities.featurepipeline

import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featurePipeline
import com.ca.continuousauth.featuremodalities.dataprocessing.windowing.windowedFlow
import com.ca.continuousauth.featuremodalities.featurefusion.FusedFeatureBuilder
import com.ca.continuousauth.featuremodalities.synchronization.SynchronizedSample
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Configuration for a sensor processing pipeline.
 */
data class SensorPipelineConfig(
    val sensorKey: String,
    val selector: (SynchronizedSample) -> List<Float>,
    val denoisers: List<SensorDenoiser>,
    val featureExtractors: List<FeatureExtractor>
)

/**
 * Builds a processing pipeline for a given sensor.
 */
private fun buildSensorPipeline(
    sharedFlow: SharedFlow<SynchronizedSample>,
    config: SensorPipelineConfig,
    windowSize: Int,
    windowOverlap: Double,
    dispatcher: CoroutineDispatcher
): Flow<Any> {

    return sharedFlow
        .map { sample -> sample.timestamp to config.selector(sample) }
        .windowedFlow(windowSize, windowOverlap)
        .denoisePipeline(config.denoisers)
        .featurePipeline(config.featureExtractors)
        .flowOn(dispatcher)
        .catch { ex ->
            Logger.e("Error in ${config.sensorKey} pipeline", ex)
        }
}

/**
 * Extensible synchronized sensor feature collector.
 * Supports adding new sensors easily.
 */
fun collectSynchronizedSensorFeatures(
    synchronizedFlow: Flow<SynchronizedSample>,
    sensorConfigs: List<SensorPipelineConfig>,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): Flow<Any> {

    val windowSize = AuthConfigManager.config.windowSize
    val windowOverlap = AuthConfigManager.config.windowOverlapRatio

    val sharedFlow = synchronizedFlow.shareIn(
        scope = CoroutineScope(dispatcher),
        started = SharingStarted.Eagerly,
        replay = 0
    )

    /**
     * Build pipelines for all sensors
     */
    val sensorFlows: List<Pair<String, Flow<Any>>> = sensorConfigs.map { config ->
        config.sensorKey to buildSensorPipeline(
            sharedFlow,
            config,
            windowSize,
            windowOverlap,
            dispatcher
        )
    }

    /**
     * Combine all sensor feature flows
     */
    val combinedFlow = combine(sensorFlows.map { it.second }) { featureArray ->

        val sensorFeatureMap = mutableMapOf<String, Any>()

        sensorFlows.forEachIndexed { index, pair ->
            sensorFeatureMap[pair.first] = featureArray[index]
        }

        FusedFeatureBuilder.buildFusedFeatures(sensorFeatureMap)
    }

    return combinedFlow
        .flowOn(dispatcher)
        .catch { ex ->
            Logger.e("Error in synchronized sensor fusion flow", ex)
        }
}