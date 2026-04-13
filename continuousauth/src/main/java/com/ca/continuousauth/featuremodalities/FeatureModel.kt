package com.ca.continuousauth.featuremodalities

import android.content.Context
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection.KalmanDenoiser
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.RawSequenceFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.SensorFeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.Scaler
import com.ca.continuousauth.featuremodalities.featurefusion.FusedFeatureBuilder
import com.ca.continuousauth.featuremodalities.featurepipeline.SensorPipelineConfig
import com.ca.continuousauth.featuremodalities.featurepipeline.collectSynchronizedSensorFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectTouchDynamicFeature
import com.ca.continuousauth.featuremodalities.rawdatacollectors.*
import com.ca.continuousauth.featuremodalities.synchronization.SensorTimeSynchronizer
import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

// Add other necessary imports...
data class DualFeatureVector(
    val sensorVector: Any,       // List<Float> or List<List<Float>>
    val fusionVector: Any? = null, // List<Float> or List<List<Float>> or null
    val touchTime: Long = -1L
)

class FeatureModel {

    fun getDualFeatureFlowAtFrequency(
        context: Context,
        touchEventFlow: Flow<TouchEventData>? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): Flow<DualFeatureVector> {

        /* ----------------------------------------------------------
           Config
        ---------------------------------------------------------- */
        val config       = AuthConfigManager.config
        val sampleRateHz = config.sampleCollectionFrequencyHz
        val windowSize   = config.windowSize.toDouble()
        val overlap      = config.windowOverlapRatio
        val authFreqHz   = sampleRateHz / (windowSize * (1.0 - overlap))

        Logger.d("Authentication frequency = $authFreqHz Hz")

        /* ----------------------------------------------------------
           Collectors
        ---------------------------------------------------------- */
        val gyroCollector  = GyroscopeDataCollector(context, sampleRateHz, dispatcher)
        val accelCollector = AccelerometerDataCollector(context, sampleRateHz, dispatcher)
        val touchCollector = TouchDataCollector(touchEventFlow = touchEventFlow)

        /* ----------------------------------------------------------
           Pipeline Configs
        ---------------------------------------------------------- */
        val dynamicConfigs = listOf(
            SensorPipelineConfig(
                sensorKey         = "gyro",
                selector          = { it.gyro },
                denoisers         = listOf(KalmanDenoiser()),
                featureExtractors = listOf(RawSequenceFeatureExtractor())
            ),
            SensorPipelineConfig(
                sensorKey         = "acc",
                selector          = { it.accel },
                denoisers         = listOf(KalmanDenoiser()),
                featureExtractors = listOf(RawSequenceFeatureExtractor())
            )
        )

        val staticConfigs = listOf(
            SensorPipelineConfig(
                sensorKey         = "gyro",
                selector          = { it.gyro },
                denoisers         = listOf(KalmanDenoiser()),
                featureExtractors = listOf(SensorFeatureExtractor())
            ),
            SensorPipelineConfig(
                sensorKey         = "acc",
                selector          = { it.accel },
                denoisers         = listOf(KalmanDenoiser()),
                featureExtractors = listOf(SensorFeatureExtractor())
            )
        )

        /* ----------------------------------------------------------
           channelFlow gives us a CoroutineScope for background launches
        ---------------------------------------------------------- */
        return channelFlow {

            // ✅ One shared upstream — both pipelines read the same sensor data
            val sharedSyncFlow = SensorTimeSynchronizer(sampleRateHz)
                .synchronize(
                    gyroFlow  = gyroCollector.start(),
                    accelFlow = accelCollector.start()
                )
                .shareIn(scope = this, started = SharingStarted.Eagerly)

            /* ----------------------------------------------------------
               Static cache — updated in background, read instantly.
               AtomicReference ensures safe cross-coroutine reads with NO suspension.
            ---------------------------------------------------------- */
            val latestStaticVector = AtomicReference<List<Float>?>(null)

            launch(dispatcher) {
                collectSynchronizedSensorFeatures(sharedSyncFlow, staticConfigs)
                    .collect { staticOutput ->
                        flattenFeatureOutput(staticOutput)?.let { vec ->
                            latestStaticVector.set(vec)
                        }
                    }
            }

            /* ----------------------------------------------------------
               Touch latch — written by touch coroutine, read by dynamic loop.
               CONFLATED so only the latest unprocessed touch is kept.
            ---------------------------------------------------------- */
            data class TouchLatch(val features: List<Float>, val time: Long)

            val touchLatchChannel = Channel<TouchLatch>(capacity = Channel.CONFLATED)

            launch(dispatcher) {
                var lastSeenTouchTime = -1L
                collectTouchDynamicFeature({ touchCollector.start() }, dispatcher)
                    .collect { (touchTime, touchFeatures) ->
                        val vec = flattenFeatureOutput(touchFeatures) ?: return@collect
                        if (vec.any { it != 0f } && touchTime != lastSeenTouchTime) {
                            lastSeenTouchTime = touchTime
                            touchLatchChannel.trySend(TouchLatch(vec, touchTime))
                        }
                    }
            }

            /* ----------------------------------------------------------
               Dynamic loop — NEVER suspends for static or touch.
               Both are read from caches; no blocking calls inside.
            ---------------------------------------------------------- */
            var lastDynamicVector: List<Float>? = null

            collectSynchronizedSensorFeatures(sharedSyncFlow, dynamicConfigs)
                .collect { dynamicOutput ->

                    // 🛑 Skip duplicate dynamic vectors
                    val dynamicVector = flattenFeatureOutput(dynamicOutput) ?: return@collect
                    if (dynamicVector == lastDynamicVector) return@collect
                    lastDynamicVector = dynamicVector

                    // ✅ Non-blocking touch check
                    val pendingTouch = touchLatchChannel.tryReceive().getOrNull()

                    // ✅ Non-blocking static read — always instant, never suspends
                    val fusionVector: List<Float>? = pendingTouch?.let { touch ->
                        val cachedStatic = latestStaticVector.get() ?: return@let null
                        flattenFeatureOutput(
                            FusedFeatureBuilder.buildFusedFeatures(
                                mapOf("sensor" to cachedStatic, "touch" to touch.features)
                            )
                        )
                    }

                    send(
                        DualFeatureVector(
                            sensorVector = dynamicVector,
                            fusionVector = fusionVector,
                            touchTime    = pendingTouch?.time ?: -1L
                        )
                    )
                }

        }.conflate()
    }
    /* ----------------------------------------------------------
       Flatten Utility
    ---------------------------------------------------------- */
    private fun flattenFeatureOutput(data: Any?): List<Float>? {
        return when (data) {
            is List<*> -> {
                if (data.isEmpty()) return emptyList()
                val first = data.first()
                when (first) {
                    is Float -> data.filterIsInstance<Float>()
                    is List<*> -> {
                        val matrix = data.filterIsInstance<List<Float>>()
                        val result = ArrayList<Float>()
                        for (row in matrix) {
                            result.addAll(row)
                        }
                        result
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    fun applyFitTransform(scaler: Scaler, data: List<List<Float>>): List<List<Float>> {
        return scaler.fitTransform(data)
    }

    fun applyTransform(scaler: Scaler, data: List<List<Float>>): List<List<Float>> {
        return scaler.transform(data)
    }
}