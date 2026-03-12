package com.ca.continuousauth.featuremodalities

import android.content.Context
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.Scaler
import com.ca.continuousauth.featuremodalities.featurefusion.FusedFeatureBuilder
import com.ca.continuousauth.featuremodalities.featurepipeline.collectProcessedGyroFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectProcessedMagnetometerFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectProcessedTotalAccelFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectTouchDynamicFeature
import com.ca.continuousauth.featuremodalities.rawdatacollectors.*
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

        val sampleRateHz = AuthConfigManager.config.sampleCollectionFrequencyHz.toDouble()
        val windowSize = AuthConfigManager.config.windowSize.toDouble()
        val overlap = AuthConfigManager.config.windowOverlapRatio
        val stepSize = windowSize * (1.0 - overlap)
        val authenticationFrequencyHz = sampleRateHz / stepSize
        Logger.d("Authentication frequency = $authenticationFrequencyHz Hz")

        /* ----------------------------------------------------------
           Collectors
        ---------------------------------------------------------- */
        val gyroCollector = GyroscopeDataCollector(context, AuthConfigManager.config.sampleCollectionFrequencyHz, dispatcher)
        val totalAccelCollector = TotalAccelerometerDataCollector(context, AuthConfigManager.config.sampleCollectionFrequencyHz, dispatcher)
        val magnoCollector = MagnetometerDataCollector(context, AuthConfigManager.config.sampleCollectionFrequencyHz, dispatcher)
        val touchCollector = TouchDataCollector(touchEventFlow = touchEventFlow)

        /* ----------------------------------------------------------
           Feature Pipelines
        ---------------------------------------------------------- */
        val gyroFlow: Flow<Any> = collectProcessedGyroFeatures({ gyroCollector.start() }, dispatcher)
        val totalAccelFlow: Flow<Any> = collectProcessedTotalAccelFeatures({ totalAccelCollector.start() }, dispatcher)
        val magnoFlow: Flow<Any> = collectProcessedMagnetometerFeatures({ magnoCollector.start() }, dispatcher)
        val touchFlow: Flow<Pair<Long, Any>> = collectTouchDynamicFeature({ touchCollector.start() }, dispatcher)

        /* ----------------------------------------------------------
           Touch Latch Variables
        ---------------------------------------------------------- */
        var latchedTouchFeatures: Any? = null
        var latchedTouchTime: Long = -1L
        var latchConsumed = true

        /* ----------------------------------------------------------
           Fusion Pipeline
        ---------------------------------------------------------- */
        val dualFlow: Flow<DualFeatureVector> = combine(
            gyroFlow,
            totalAccelFlow,
            magnoFlow,
            touchFlow
        ) { gyro, totalAccel, magno, touchPair ->

            val (touchTime, touchFeatures) = touchPair

            /* ---------------- Sensor-only fusion ---------------- */
            val sensorMap = mapOf(
                "gyro" to gyro,
                "totalAccel" to totalAccel,
                "magno" to magno
            )
            val sensorOutput = FusedFeatureBuilder.buildFusedFeatures(sensorMap)
            val sensorVector = flattenFeatureOutput(sensorOutput)

            /* ---------------- Touch normalization ---------------- */
            val touchVector = flattenFeatureOutput(touchFeatures)
            val isTouchNonZero = touchVector?.any { it != 0f } ?: false

            if (isTouchNonZero && touchTime != latchedTouchTime) {
                latchedTouchFeatures = touchVector
                latchedTouchTime = touchTime
                latchConsumed = false
                Logger.d("FusionPipeline: Latched touch vector (time=$touchTime dim=${touchVector?.size})")
            }

            /* ---------------- Fusion with touch ---------------- */
            val fusionVector: List<Float>?
            val emittedTouchTime: Long

            if (!latchConsumed && latchedTouchFeatures != null) {
                val fusedMap = mapOf(
                    "gyro" to gyro,
                    "totalAccel" to totalAccel,
                    "magno" to magno,
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

    /* ----------------------------------------------------------
       Utility: Flatten 1D or 2D feature output into a 1D List<Float>

       For 2D data (seq_len, feature_dim), transposes to
       (feature_dim, seq_len) before flattening so the layout
       matches the model's expected input: (batch, feature_dim, seq_len, channel).
    ---------------------------------------------------------- */
    private fun flattenFeatureOutput(data: Any?): List<Float>? {
        return when (data) {
            is List<*> -> {
                if (data.isEmpty()) return emptyList()
                val first = data.first()
                when (first) {
                    is Float -> data.filterIsInstance<Float>()
                    is List<*> -> {
                        // Input shape: (seq_len, feature_dim) e.g. (200, 12)
                        // Output order: (feature_dim, seq_len) e.g. (12, 200) flattened
                        // This matches model input (batch, feature_dim, seq_len, channel)
                        val matrix = data.filterIsInstance<List<Float>>()
                        val rows = matrix.size                    // seq_len (e.g. 200)
                        val cols = matrix.firstOrNull()?.size ?: 0 // feature_dim (e.g. 12)
                        if (rows == 0 || cols == 0) return emptyList()

                        val result = ArrayList<Float>(rows * cols)
                        for (col in 0 until cols) {       // iterate features first
                            for (row in 0 until rows) {   // then timesteps
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

    /* ----------------------------------------------------------
       Scaling Utilities
    ---------------------------------------------------------- */
    fun applyFitTransform(scaler: Scaler, data: List<List<Float>>): List<List<Float>> {
        return scaler.fitTransform(data)
    }

    fun applyTransform(scaler: Scaler, data: List<List<Float>>): List<List<Float>> {
        return scaler.transform(data)
    }
}