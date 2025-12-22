package com.ca.continuousauth.featuremodalities

import android.content.Context
import android.view.View
import com.ca.continuousauth.config.AuthConfig
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.MinMaxScaler
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.Scaler
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.StandardScaler
import com.ca.continuousauth.featuremodalities.featurefusion.FusedFeatureBuilder2D
import com.ca.continuousauth.featuremodalities.featurepipeline.collectProcessedAccelFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectProcessedGyroFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectTouchDynamicFeature
import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList

class FeatureModel {

    /**
     * Collect multiple modality features in parallel and fuse them in real-time.
     *
     * @param context Android context
     * @param featureCount Number of fused samples to collect
     * @return List of fused feature vectors
     */
    suspend fun getFeatureVectors(context: Context, featureCount: Int , touchEventFlow: Flow<TouchEventData>?): List<List<Float>> {

        // Define flows for each modality
        val accelFlow: Flow<List<Float>> = collectProcessedAccelFeatures(context)
        val gyroFlow: Flow<List<Float>> = collectProcessedGyroFeatures(context)
        val touchFlow: Flow<List<Float>> = collectTouchDynamicFeature(touchEventFlow)

        // Combine all flows dynamically
        val fusedFlow: Flow<List<Float>> = combine(accelFlow, gyroFlow ,touchFlow) { arrays ->
            val fusedMap = mapOf(
                "accel" to listOf(arrays[0]),
                "gyro" to listOf(arrays[1]),
                "touch" to listOf(arrays[3])
//                "magnetometer" to listOf(arrays[2])
            )
            FusedFeatureBuilder2D.buildFusedMatrix(fusedMap).first() // Take single fused vector
        }

        // Collect only the requested number of fused samples
        val fusedVectors: List<List<Float>> = fusedFlow.take(featureCount).toList()

        Logger.d("Output fused vector dimention length = ${fusedVectors[0].size}")
        Logger.d("Output fused vector number of samples = ${fusedVectors.size}")
        Logger.d("Output fused vector [0]= ${fusedVectors[0]}")
        return fusedVectors
    }
    /**
     * Returns a continuous Flow emitting fused feature vectors one by one.
     * This is ideal for real-time inference.
     */
    fun getFeatureFlow(context: Context , touchEventFlow: Flow<TouchEventData>?): Flow<List<Float>> {

        // Individual modality feature flows
        val accelFlow: Flow<List<Float>> = collectProcessedAccelFeatures(context)
        val gyroFlow: Flow<List<Float>> = collectProcessedGyroFeatures(context)
        val touchFlow: Flow<List<Float>> = collectTouchDynamicFeature(touchEventFlow)

        // Combine them into a fused feature vector
        return combine(accelFlow, gyroFlow,  touchFlow) { accel, gyro,touch ->

            val fusedMap = mapOf(
                "accel" to listOf(accel),
                "gyro" to listOf(gyro),
                "touch" to listOf(touch)
            )

            // Build a single fused feature vector
            FusedFeatureBuilder2D.buildFusedMatrix(fusedMap).first()
        }
    }

    fun getFeatureFlowAtFrequency(
        context: Context,
        touchEventFlow: Flow<TouchEventData>?,
    ): Flow<List<Float>> {

        val sampleRateHz: Double = AuthConfigManager.config.sampleCollectionFrequencyHz.toDouble()
        val windowSize: Double = AuthConfigManager.config.windowSize.toDouble()
        val overlap: Double = AuthConfigManager.config.windowOverlapRatio

        val stepSize = windowSize * (1.0 - overlap)
        val AuthenticationFrequencyHz: Double = sampleRateHz / stepSize
        Logger.d("Authentication frequency = $AuthenticationFrequencyHz Hz")

        // Compute interval in milliseconds
        val intervalMs: Long = (1000.0 / AuthenticationFrequencyHz).toLong()

        // Individual modality feature flows
        val accelFlow = collectProcessedAccelFeatures(context)
        val gyroFlow = collectProcessedGyroFeatures(context)
        val touchFlow = collectTouchDynamicFeature(touchEventFlow)

        // Combine flows
        val combinedFlow = combine(accelFlow, gyroFlow,touchFlow) { accel, gyro,  touch ->
            val fusedMap = mapOf(
                "accel" to listOf(accel),
                "gyro" to listOf(gyro),
                "touch" to listOf(touch),
            )
            FusedFeatureBuilder2D.buildFusedMatrix(fusedMap).first()
        }

        // Throttle to exact target frequency
        return combinedFlow
            .onEach {
                delay(intervalMs) // suspend function works here
            }
            .conflate() // prevent backlog if sensors are faster
    }

//    fun getFeatureFlowAtFrequency(
//        context: Context,
//        touchEventFlow: Flow<TouchEventData>?,
//    ): Flow<List<Float>> {
//
//        val sampleRateHz: Double = AuthConfigManager.config.sampleCollectionFrequencyHz.toDouble()
//        val windowSize: Double = AuthConfigManager.config.windowSize.toDouble()
//        val overlap: Double = AuthConfigManager.config.windowOverlapRatio
//
//        val stepSize = windowSize * (1.0 - overlap)
//        val AuthenticationFrequencyHz: Double = sampleRateHz / stepSize
//        Logger.d("Authentication frequency = $AuthenticationFrequencyHz Hz")
//
//        // Compute interval in milliseconds
//        val intervalMs: Long = (1000.0 / AuthenticationFrequencyHz).toLong()
//
//        // Individual modality feature flows
//        val accelFlow = collectProcessedAccelFeatures(context)
//        val gyroFlow = collectProcessedGyroFeatures(context)
//
//        // Combine flows
//        val combinedFlow = combine(accelFlow, gyroFlow) { accel, gyro->
//            val fusedMap = mapOf(
//                "accel" to listOf(accel),
//                "gyro" to listOf(gyro),
//            )
//            FusedFeatureBuilder2D.buildFusedMatrix(fusedMap).first()
//        }
//
//        // Throttle to exact target frequency
//        return combinedFlow
//            .onEach {
//                delay(intervalMs) // suspend function works here
//            }
//            .conflate() // prevent backlog if sensors are faster
//    }

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
