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
import kotlinx.coroutines.flow.*

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

        val sampleRateHz = AuthConfigManager.config.sampleCollectionFrequencyHz
        val windowSize = AuthConfigManager.config.windowSize.toDouble()
        val overlap = AuthConfigManager.config.windowOverlapRatio

        val stepSize = windowSize * (1.0 - overlap)
        val authenticationFrequencyHz = sampleRateHz / stepSize
        Logger.d("Authentication frequency = $authenticationFrequencyHz Hz")

        /* ----------------------------------------------------------
           Raw Data Collectors
        ---------------------------------------------------------- */
        val gyroCollector = GyroscopeDataCollector(context, sampleRateHz, dispatcher)
        val accelCollector = AccelerometerDataCollector(context, sampleRateHz, dispatcher)
        val touchCollector = TouchDataCollector(touchEventFlow = touchEventFlow)

        /* ----------------------------------------------------------
           DYNAMIC PIPELINE (2D → will be flattened)
        ---------------------------------------------------------- */
        val dynamicConfigs = listOf(
            SensorPipelineConfig(
                sensorKey = "gyro",
                selector = { it.gyro },
                denoisers = listOf(KalmanDenoiser()),
                featureExtractors = listOf(RawSequenceFeatureExtractor())
            ),
            SensorPipelineConfig(
                sensorKey = "acc",
                selector = { it.accel },
                denoisers = listOf(KalmanDenoiser()),
                featureExtractors = listOf(RawSequenceFeatureExtractor())
            )
        )

        /* ----------------------------------------------------------
           STATIC PIPELINE (1D → ONLY for fusion)
        ---------------------------------------------------------- */
        val staticConfigs = listOf(
            SensorPipelineConfig(
                sensorKey = "gyro",
                selector = { it.gyro },
                denoisers = listOf(KalmanDenoiser()),
                featureExtractors = listOf(SensorFeatureExtractor())
            ),
            SensorPipelineConfig(
                sensorKey = "acc",
                selector = { it.accel },
                denoisers = listOf(KalmanDenoiser()),
                featureExtractors = listOf(SensorFeatureExtractor())
            )
        )

        /* ----------------------------------------------------------
           Synchronization (shared)
        ---------------------------------------------------------- */
        val synchronizer = SensorTimeSynchronizer(sampleRateHz)
        val synchronizedFlow = synchronizer.synchronize(
            gyroFlow = gyroCollector.start(),
            accelFlow = accelCollector.start()
        )

        /* ----------------------------------------------------------
           Two Sensor Feature Flows
        ---------------------------------------------------------- */
        val dynamicSensorFlow: Flow<Any> = collectSynchronizedSensorFeatures(
            synchronizedFlow = synchronizedFlow,
            sensorConfigs = dynamicConfigs
        )

        val staticSensorFlow: Flow<Any> = collectSynchronizedSensorFeatures(
            synchronizedFlow = synchronizedFlow,
            sensorConfigs = staticConfigs
        )

        /* ----------------------------------------------------------
           Touch Flow
        ---------------------------------------------------------- */
        val touchFlow: Flow<Pair<Long, Any>> =
            collectTouchDynamicFeature({ touchCollector.start() }, dispatcher)

        /* ----------------------------------------------------------
           Fusion Logic (STATIC + TOUCH ONLY)
        ---------------------------------------------------------- */
        var latchedTouchFeatures: List<Float>? = null
        var latchedTouchTime: Long = -1L
        var latchConsumed = true

        val dualFlow = combine(
            dynamicSensorFlow,
            staticSensorFlow,
            touchFlow
        ) { dynamicOutput, staticOutput, touchPair ->

            val (touchTime, touchFeatures) = touchPair

            // ✅ Dynamic → flatten BEFORE emitting
            val dynamicVector = flattenFeatureOutput(dynamicOutput) ?: emptyList()

            // ✅ Static → only used internally
            val staticVector = flattenFeatureOutput(staticOutput) ?: emptyList()

            val touchVector = flattenFeatureOutput(touchFeatures) ?: emptyList()

            val isTouchNonZero = touchVector.any { it != 0f }

            /* ----------- LATCH TOUCH ----------- */
            if (isTouchNonZero && touchTime != latchedTouchTime) {
                latchedTouchFeatures = touchVector
                latchedTouchTime = touchTime
                latchConsumed = false
            }

            var fusionVector: List<Float>? = null
            var emittedTouchTime = touchTime

            /* ----------- FUSION ----------- */
            if (!latchConsumed && latchedTouchFeatures != null) {

                val fusedMap = mapOf(
                    "sensor" to staticVector,  // ONLY static used
                    "touch" to latchedTouchFeatures!!
                )

                val fusionOutput = FusedFeatureBuilder.buildFusedFeatures(fusedMap)
                fusionVector = flattenFeatureOutput(fusionOutput)

                emittedTouchTime = latchedTouchTime
                latchConsumed = true
            }

            DualFeatureVector(
                sensorVector = dynamicVector, // flattened dynamic
                fusionVector = fusionVector,
                touchTime = emittedTouchTime
            )

        }.conflate()

        return dualFlow
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