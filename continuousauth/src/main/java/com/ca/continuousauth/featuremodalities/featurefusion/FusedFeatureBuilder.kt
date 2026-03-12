package com.ca.continuousauth.featuremodalities.featurefusion

import com.ca.continuousauth.utils.Logger

/**
 * Dynamically fuses multi-modality features safely.
 */
object FusedFeatureBuilder {

    fun buildFusedFeatures(modalityFeatures: Map<String, Any>): Any {
        if (modalityFeatures.isEmpty()) {
            Logger.e("FusedFeatureBuilder: No modality features provided.")
            return emptyList<Float>()
        }

        // 1. Strict Empty Check: Neural networks cannot handle missing features
        val emptyModalities = modalityFeatures.filter {
            (it.value as? List<*>)?.isEmpty() == true
        }.keys

        if (emptyModalities.isNotEmpty()) {
            Logger.e("FusedFeatureBuilder: Aborting fusion. Modalities returned empty data: $emptyModalities")
            return emptyList<Float>() // Better to fail auth than crash the model
        }

        return try {
            val firstFeature = modalityFeatures.values.first()
            when {
                is2D(firstFeature) -> {
                    Logger.d("FusedFeatureBuilder: Detected 2D feature structure")
                    fuse2D(modalityFeatures)
                }
                is1D(firstFeature) -> {
                    Logger.d("FusedFeatureBuilder: Detected 1D feature structure")
                    fuse1D(modalityFeatures)
                }
                else -> {
                    Logger.e("FusedFeatureBuilder: Unsupported feature structure")
                    emptyList<Float>()
                }
            }
        } catch (ex: Exception) {
            Logger.e("FusedFeatureBuilder: Fusion failed", ex)
            emptyList<Float>()
        }
    }

    private fun is1D(obj: Any): Boolean {
        if (obj !is List<*>) return false
        val first = obj.firstOrNull() ?: return false
        return first is Float
    }

    private fun is2D(obj: Any): Boolean {
        if (obj !is List<*>) return false
        val first = obj.firstOrNull() ?: return false
        return first is List<*> && first.firstOrNull() is Float
    }

    private fun fuse1D(modalityFeatures: Map<String, Any>): List<Float> {
        val fusedVector = mutableListOf<Float>()

        modalityFeatures.forEach { (name, features) ->
            @Suppress("UNCHECKED_CAST")
            val vector = when {
                is1D(features) -> features as List<Float>
                is2D(features) -> (features as List<List<Float>>).flatten()
                else -> emptyList()
            }
            Logger.d("FusedFeatureBuilder: 1D modality=$name featureDim=${vector.size}")
            fusedVector.addAll(vector)
        }

        Logger.d("FusedFeatureBuilder: 1D fusion complete featureDim=${fusedVector.size}")
        return fusedVector
    }

    private fun fuse2D(modalityFeatures: Map<String, Any>): List<List<Float>> {
        var minWindows = Int.MAX_VALUE

        // Categorize modalities upfront to avoid doing it inside the tight loop
        val structureMap = mutableMapOf<String, Int>() // 1 for 1D, 2 for 2D

        modalityFeatures.forEach { (name, features) ->
            if (is2D(features)) {
                structureMap[name] = 2
                @Suppress("UNCHECKED_CAST")
                val list2D = features as List<List<Float>>
                if (list2D.size < minWindows) minWindows = list2D.size

                Logger.d("FusedFeatureBuilder: 2D modality=$name featureDim=${list2D.firstOrNull()?.size ?: 0}")
            } else if (is1D(features)) {
                structureMap[name] = 1
                @Suppress("UNCHECKED_CAST")
                Logger.d("FusedFeatureBuilder: 1D (Broadcast) modality=$name featureDim=${(features as List<Float>).size}")
            } else {
                structureMap[name] = 0 // Invalid
            }
        }

        if (minWindows == Int.MAX_VALUE) minWindows = 0

        val fusedMatrix = ArrayList<List<Float>>(minWindows) // Pre-allocate capacity for speed

        for (windowIndex in 0 until minWindows) {
            val fusedRow = mutableListOf<Float>()

            modalityFeatures.forEach { (modality, features) ->
                @Suppress("UNCHECKED_CAST")
                val vector = when (structureMap[modality]) {
                    2 -> (features as List<List<Float>>)[windowIndex]
                    1 -> features as List<Float> // Broadcasting the 1D feature across all windows
                    else -> emptyList() // Should be caught by the empty check at the start, but safe fallback
                }
                fusedRow.addAll(vector)
            }
            fusedMatrix.add(fusedRow)
        }

        Logger.d("FusedFeatureBuilder: 2D fusion complete windows=$minWindows fusedFeatureDim=${fusedMatrix.firstOrNull()?.size ?: 0}")
        return fusedMatrix
    }
}