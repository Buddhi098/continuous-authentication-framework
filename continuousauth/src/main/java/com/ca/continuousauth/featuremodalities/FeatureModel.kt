package com.ca.continuousauth.featuremodalities

import android.content.Context
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.Scaler
import com.ca.continuousauth.featuremodalities.featurefusion.FusedFeatureBuilder
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
        val totalAccelCollector = TotalAccelerometerDataCollector(context, sampleRateHz, dispatcher)
        // Magnetometer collector disabled as per user request
        // val magnoCollector = MagnetometerDataCollector(context, sampleRateHz, dispatcher)
        val touchCollector = TouchDataCollector(touchEventFlow = touchEventFlow)

        /* ----------------------------------------------------------
           Timestamp-Based Sensor Synchronization
        ---------------------------------------------------------- */
        val synchronizer = SensorTimeSynchronizer(sampleRateHz)
        val synchronizedFlow = synchronizer.synchronize(
            gyroFlow  = gyroCollector.start(),
            accelFlow = totalAccelCollector.start()
        )

        /* ----------------------------------------------------------
           Synchronized Sensor Feature Pipeline
        ---------------------------------------------------------- */
        val sensorFeatureFlow: Flow<Any> = collectSynchronizedSensorFeatures(
            synchronizedFlow, dispatcher
        )

        /* ----------------------------------------------------------
           Touch Pipeline (unchanged)
        ---------------------------------------------------------- */
        val touchFlow: Flow<Pair<Long, Any>> = collectTouchDynamicFeature({ touchCollector.start() }, dispatcher)

        var latchedTouchFeatures: Any? = null
        var latchedTouchTime: Long = -1L
        var latchConsumed = true

        val dualFlow: Flow<DualFeatureVector> = combine(
            sensorFeatureFlow,
            touchFlow
        ) { sensorOutput, touchPair ->

            val (touchTime, touchFeatures) = touchPair
            val sensorVector = flattenFeatureOutput(sensorOutput)
            val touchVector = flattenFeatureOutput(touchFeatures)
            val isTouchNonZero = touchVector?.any { it != 0f } ?: false

            if (isTouchNonZero && touchTime != latchedTouchTime) {
                latchedTouchFeatures = touchVector
                latchedTouchTime = touchTime
                latchConsumed = false
                Logger.d("FusionPipeline: Latched touch vector (time=$touchTime dim=${touchVector?.size})")
            }

            val fusionVector: List<Float>?
            val emittedTouchTime: Long

            if (!latchConsumed && latchedTouchFeatures != null) {
                val fusedMap = mapOf(
                    "sensor" to (sensorVector ?: emptyList<Float>()),
                    "touch" to latchedTouchFeatures!!
                )
                val fusionOutput = FusedFeatureBuilder.buildFusedFeatures(fusedMap)
                fusionVector = flattenFeatureOutput(fusionOutput)
                emittedTouchTime = latchedTouchTime
                latchConsumed = true
                Logger.d("FusionPipeline: Fusion vector built (dim=${fusionVector?.size})")
            } else {
                fusionVector = null
                emittedTouchTime = touchTime
            }

            DualFeatureVector(
                sensorVector = sensorVector ?: emptyList<Float>(),
                fusionVector = fusionVector,
                touchTime = emittedTouchTime
            )

        }.conflate()

        return dualFlow
    }

    private fun flattenFeatureOutput(data: Any?): List<Float>? {
        return when (data) {
            is List<*> -> {
                if (data.isEmpty()) return emptyList()
                val first = data.first()
                when (first) {
                    is Float -> data.filterIsInstance<Float>()
                    is List<*> -> {
                        val matrix = data.filterIsInstance<List<Float>>()
                        val rows = matrix.size
                        val cols = matrix.firstOrNull()?.size ?: 0
                        if (rows == 0 || cols == 0) return emptyList()

                        val result = ArrayList<Float>(rows * cols)
                        for (col in 0 until cols) {
                            for (row in 0 until rows) {
                                result.add(matrix[row][col])
                            }
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