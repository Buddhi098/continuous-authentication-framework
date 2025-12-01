package com.ca.continuousauth.featuremodalities.featurefusion

import com.ca.continuousauth.utils.Logger

/**
 * Fuses multi-modality feature samples into a 2D matrix.
 *
 * Input:
 *   {
 *      "accel": List<List<Float>>,
 *      "gyro" : List<List<Float>>,
 *      "touch": List<List<Float>>
 *   }
 *
 * Output:
 *   List<List<Float>>  // rows = fused samples, columns = fused features
 */
object FusedFeatureBuilder2D {

    /**
     * Produces fused sample rows by concatenating each modality's sample at the same index.
     */
    fun buildFusedMatrix(modalityFeatures: Map<String, List<List<Float>>>): List<List<Float>> {

        if (modalityFeatures.isEmpty()) {
            Logger.e("FusedFeatureBuilder2D: No modality features given.")
            return emptyList()
        }

//        Logger.d("FusedFeatureBuilder2D: Fusion starting for ${modalityFeatures.size} modalities.")

        return try {

            // 1. Ensure alignment — use smallest sample count among modalities
            val minSamples = modalityFeatures.values.minOfOrNull { it.size } ?: 0

            if (minSamples == 0) {
                Logger.e("FusedFeatureBuilder2D: One or more modalities returned zero samples.")
                return emptyList()
            }

//            Logger.d("FusedFeatureBuilder2D: Using aligned sample count = $minSamples")

            val fusedMatrix = mutableListOf<List<Float>>()

            // 2. Build each fused sample row
            for (i in 0 until minSamples) {

                val fusedRow = mutableListOf<Float>()

                modalityFeatures.forEach { (modalityName, featureList) ->
                    val vector = try {
                        featureList[i]
                    } catch (ex: Exception) {
                        Logger.e(
                            "FusedFeatureBuilder2D: Missing feature for modality=$modalityName at index=$i",
                            ex
                        )
                        emptyList()
                    }

                    fusedRow.addAll(vector)
                }

                fusedMatrix.add(fusedRow)
            }

//            Logger.d("FusedFeatureBuilder2D: Fusion complete. Matrix rows=$minSamples, cols=${fusedMatrix.first().size}")
            fusedMatrix

        } catch (ex: Exception) {
            Logger.e("FusedFeatureBuilder2D: Unexpected fusion error", ex)
            emptyList()
        }
    }
}
