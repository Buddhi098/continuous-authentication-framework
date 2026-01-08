package com.ca.continuousauth.featuremodalities

import android.content.Context
import android.view.View
import com.ca.continuousauth.config.AuthConfig
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.MinMaxScaler
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.Scaler
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.StandardScaler
import com.ca.continuousauth.featuremodalities.featurefusion.FusedFeatureBuilder2D
import com.ca.continuousauth.featuremodalities.featurepipeline.collectProcessedGyroFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectProcessedLinearAccelFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectProcessedTotalAccelFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectTouchDynamicFeature
import com.ca.continuousauth.featuremodalities.rawdatacollectors.GyroscopeDataCollector
import com.ca.continuousauth.featuremodalities.rawdatacollectors.LinearAccelerometerDataCollector
import com.ca.continuousauth.featuremodalities.rawdatacollectors.TotalAccelerometerDataCollector
import com.ca.continuousauth.featuremodalities.rawdatacollectors.TouchDataCollector
import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

class FeatureModel {

    fun getFeatureFlowAtFrequency(
        context: Context,
        touchEventFlow: Flow<TouchEventData>?,
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): Flow<List<Float>> {

        val sampleRateHz: Double =
            AuthConfigManager.config.sampleCollectionFrequencyHz.toDouble()

        val windowSize: Double =
            AuthConfigManager.config.windowSize.toDouble()

        val overlap: Double =
            AuthConfigManager.config.windowOverlapRatio

        val stepSize = windowSize * (1.0 - overlap)
        val authenticationFrequencyHz: Double = sampleRateHz / stepSize

        Logger.d("Authentication frequency = $authenticationFrequencyHz Hz")

        // Interval in milliseconds
        val intervalMs: Long = (1000.0 / authenticationFrequencyHz).toLong()

        /* ------------------------------------------------------------------
         * Collectors (created ONCE here)
         * ------------------------------------------------------------------ */

        val linearAccelCollector = LinearAccelerometerDataCollector(
            context = context,
            frequencyHz = AuthConfigManager.config.sampleCollectionFrequencyHz,
            dispatcher = dispatcher
        )

        val totalAccelCollector = TotalAccelerometerDataCollector(
            context = context,
            frequencyHz = AuthConfigManager.config.sampleCollectionFrequencyHz,
            dispatcher = dispatcher
        )

        val gyroCollector = GyroscopeDataCollector(
            context = context,
            frequencyHz = AuthConfigManager.config.sampleCollectionFrequencyHz,
            dispatcher = dispatcher
        )

        val touchCollector = TouchDataCollector(
            touchEventFlow = touchEventFlow,
            frequencyHz = AuthConfigManager.config.sampleCollectionFrequencyHz
        )

        /* ------------------------------------------------------------------
         * Feature pipelines (collector injected)
         * ------------------------------------------------------------------ */

        val linearAccelFlow = collectProcessedLinearAccelFeatures(
            collector = { linearAccelCollector.start() },
            dispatcher = dispatcher
        )

        val gyroFlow = collectProcessedGyroFeatures(
            collector = { gyroCollector.start() },
            dispatcher = dispatcher
        )

        val touchFlow = collectTouchDynamicFeature(
            collector = { touchCollector.start() },
            dispatcher = dispatcher
        )

    val totalAccelFlow = collectProcessedTotalAccelFeatures(
        collector = { totalAccelCollector.start() },
        dispatcher = dispatcher
    )

        /* ------------------------------------------------------------------
         * Feature fusion
         * ------------------------------------------------------------------ */

        val combinedFlow = combine(
            linearAccelFlow,
            totalAccelFlow,
            gyroFlow,
            touchFlow
        ) { linearAccel, totalAccel, gyro, touch ->

            val fusedMap = mapOf(
//                "linearAccel" to listOf(linearAccel),
                "totalAccel" to listOf(totalAccel),
                "gyro" to listOf(gyro),
                "touch" to listOf(touch)
            )

            FusedFeatureBuilder2D
                .buildFusedMatrix(fusedMap)
                .first()
        }

        /* ------------------------------------------------------------------
         * Throttle to authentication frequency
         * ------------------------------------------------------------------ */

        return combinedFlow
            .onEach {
                delay(intervalMs)
            }
            .conflate()
    }

    /**
     * Apply fitTransform using the provided scaler.
     */
    fun applyFitTransform(scaler: Scaler, data: List<List<Float>>): List<List<Float>> {
        return scaler.fitTransform(data)
    }

    /**
     * Apply transform using an already fitted scaler.
     */
    fun applyTransform(scaler: Scaler, data: List<List<Float>>): List<List<Float>> {
        return scaler.transform(data)
    }


}
