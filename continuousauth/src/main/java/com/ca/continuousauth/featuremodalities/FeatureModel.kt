package com.ca.continuousauth.featuremodalities

import android.content.Context
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.Scaler
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

data class DualFeatureVector(
        val sensorVector: List<Float>,
        val fusionVector: List<Float>?,
        val touchTime: Long = -1L
)

class FeatureModel {

        fun getDualFeatureFlowAtFrequency(
                context: Context,
                touchEventFlow: Flow<TouchEventData>?,
                dispatcher: CoroutineDispatcher = Dispatchers.Default
        ): Flow<DualFeatureVector> {

                val sampleRateHz: Double =
                        AuthConfigManager.config.sampleCollectionFrequencyHz.toDouble()

                val windowSize: Double = AuthConfigManager.config.windowSize.toDouble()

                val overlap: Double = AuthConfigManager.config.windowOverlapRatio

                val stepSize = windowSize * (1.0 - overlap)
                val authenticationFrequencyHz: Double = sampleRateHz / stepSize

                Logger.d("Authentication frequency = $authenticationFrequencyHz Hz")

                // Interval in milliseconds
                val intervalMs: Long = (1000.0 / authenticationFrequencyHz).toLong()

                /* ------------------------------------------------------------------
                 * Collectors (created ONCE here)
                 * ------------------------------------------------------------------ */

                val linearAccelCollector =
                        LinearAccelerometerDataCollector(
                                context = context,
                                frequencyHz = AuthConfigManager.config.sampleCollectionFrequencyHz,
                                dispatcher = dispatcher
                        )

                val gyroCollector =
                        GyroscopeDataCollector(
                                context = context,
                                frequencyHz = AuthConfigManager.config.sampleCollectionFrequencyHz,
                                dispatcher = dispatcher
                        )

                val totalAccelCollector =
                        TotalAccelerometerDataCollector(
                                context = context,
                                frequencyHz = AuthConfigManager.config.sampleCollectionFrequencyHz,
                                dispatcher = dispatcher
                        )

                val touchCollector =
                        TouchDataCollector(
                                touchEventFlow = touchEventFlow,
                        )

                /* ------------------------------------------------------------------
                 * Feature pipelines (collector injected)
                 * ------------------------------------------------------------------ */

                val linearAccelFlow =
                        collectProcessedLinearAccelFeatures(
                                collector = { linearAccelCollector.start() },
                                dispatcher = dispatcher
                        )

                val gyroFlow =
                        collectProcessedGyroFeatures(
                                collector = { gyroCollector.start() },
                                dispatcher = dispatcher
                        )

                val totalAccelFlow =
                        collectProcessedTotalAccelFeatures(
                                collector = { totalAccelCollector.start() },
                                dispatcher = dispatcher
                        )

                val touchFlow =
                        collectTouchDynamicFeature(
                                collector = { touchCollector.start() },
                                dispatcher = dispatcher
                        )

                /* ------------------------------------------------------------------
                 * Time-Synchronized Dual Feature Fusion (Latch-based)
                 * Fix: latch the most-recent non-zero touch vector and use it
                 * on the next combine trigger, then clear the latch so it is
                 * emitted only once.
                 * ------------------------------------------------------------------ */

                // Mutable latch — accessed only inside the combine lambda which
                // is invoked sequentially, so no concurrency risk.
                var latchedTouchFeatures: List<Float>? = null
                var latchedTouchTime: Long = -1L
                var latchConsumed = true // starts true ⇒ nothing to consume yet

                val dualFlow =
                        combine(linearAccelFlow, gyroFlow, totalAccelFlow, touchFlow) {
                                        linearAccel: List<Float>,
                                        gyro: List<Float>,
                                        totalAccel: List<Float>,
                                        touchPair: Pair<Long, List<Float>> ->
                                        val (touchTime, touchFeatures) = touchPair

                                        // --- Build sensor-only vector (always) ---
                                        val sensorMap =
                                                mapOf(
                                                        "linearAccel" to listOf(linearAccel),
                                                        "gyro" to listOf(gyro),
                                                        "totalAccel" to listOf(totalAccel)
                                                )
                                        val sensorVec =
                                                FusedFeatureBuilder2D.buildFusedMatrix(sensorMap)
                                                        .first()

                                        // --- Latch logic ---
                                        val isTouchNonZero = touchFeatures.any { it != 0f }

                                        if (isTouchNonZero && touchTime != latchedTouchTime) {
                                                // A fresh, unique non-zero touch just arrived —
                                                // latch it
                                                latchedTouchFeatures = touchFeatures
                                                latchedTouchTime = touchTime
                                                latchConsumed = false
                                                Logger.d(
                                                        "FusionPipeline: Latched non-zero touch vector (time=$touchTime, dim=${touchFeatures.size})"
                                                )
                                        }

                                        // Build fusion from the latch if it hasn't been consumed
                                        // yet
                                        val fusionVec: List<Float>?
                                        val emittedTouchTime: Long

                                        if (!latchConsumed && latchedTouchFeatures != null) {
                                                val fusedMap =
                                                        mapOf(
                                                                "linearAccel" to
                                                                        listOf(linearAccel),
                                                                "gyro" to listOf(gyro),
                                                                "totalAccel" to listOf(totalAccel),
                                                                "touch" to
                                                                        listOf(
                                                                                latchedTouchFeatures!!
                                                                        )
                                                        )
                                                fusionVec =
                                                        FusedFeatureBuilder2D.buildFusedMatrix(
                                                                        fusedMap
                                                                )
                                                                .first()
                                                emittedTouchTime = latchedTouchTime
                                                latchConsumed = true // consume the latch — one fusion per
                                                // touch
                                                Logger.d(
                                                        "FusionPipeline: Built fusion vector (dim=${fusionVec.size}, touchTime=$emittedTouchTime)"
                                                )
                                        } else {
                                                fusionVec = null
                                                emittedTouchTime = touchTime
                                        }

                                        DualFeatureVector(
                                                sensorVector = sensorVec,
                                                fusionVector = fusionVec,
                                                touchTime = emittedTouchTime
                                        )
                                }
                                .onEach { delay(intervalMs) }
                                .conflate()

                return dualFlow
        }

        /** Apply fitTransform using the provided scaler. */
        fun applyFitTransform(scaler: Scaler, data: List<List<Float>>): List<List<Float>> {
                return scaler.fitTransform(data)
        }

        /** Apply transform using an already fitted scaler. */
        fun applyTransform(scaler: Scaler, data: List<List<Float>>): List<List<Float>> {
                return scaler.transform(data)
        }
}
