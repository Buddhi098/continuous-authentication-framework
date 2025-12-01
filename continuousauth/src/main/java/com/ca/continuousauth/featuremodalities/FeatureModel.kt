package com.ca.continuousauth.featuremodalities

import android.content.Context
import com.ca.continuousauth.featuremodalities.featurefusion.FusedFeatureBuilder2D
import com.ca.continuousauth.featuremodalities.featurepipeline.collectProcessedAccelFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectProcessedGyroFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectProcessedMagnoFeatures
import com.ca.continuousauth.featuremodalities.featurepipeline.collectTouchDynamicFeature
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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
    suspend fun getFeatureVectors(context: Context, featureCount: Int): List<List<Float>> {

        // Define flows for each modality
        val accelFlow: Flow<List<Float>> = collectProcessedAccelFeatures(context)
        val gyroFlow: Flow<List<Float>> = collectProcessedGyroFeatures(context)
        val magnoFlow: Flow<List<Float>> = collectProcessedMagnoFeatures(context)
        val touchFlow: Flow<List<Float>> = collectTouchDynamicFeature(context)

        // Combine all flows dynamically
        val fusedFlow: Flow<List<Float>> = combine(accelFlow, gyroFlow , magnoFlow , touchFlow) { arrays ->
            val fusedMap = mapOf(
                "accel" to listOf(arrays[0]),
                "gyro" to listOf(arrays[1]),
                "magno" to listOf(arrays[2]),
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
    fun getFeatureFlow(context: Context): Flow<List<Float>> {

        // Individual modality feature flows
        val accelFlow: Flow<List<Float>> = collectProcessedAccelFeatures(context)
        val gyroFlow: Flow<List<Float>> = collectProcessedGyroFeatures(context)
        val magnoFlow: Flow<List<Float>> = collectProcessedMagnoFeatures(context)
        val touchFlow: Flow<List<Float>> = collectTouchDynamicFeature(context)

        // Combine them into a fused feature vector
        return combine(accelFlow, gyroFlow, magnoFlow, touchFlow) { accel, gyro, magno, touch ->

            val fusedMap = mapOf(
                "accel" to listOf(accel),
                "gyro" to listOf(gyro),
                "magno" to listOf(magno),
                "touch" to listOf(touch)
            )

            // Build a single fused feature vector
            FusedFeatureBuilder2D.buildFusedMatrix(fusedMap).first()
        }
    }

}
