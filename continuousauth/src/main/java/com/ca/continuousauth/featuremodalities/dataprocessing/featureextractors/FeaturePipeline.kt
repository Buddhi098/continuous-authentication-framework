package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors

import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Extension function to run a feature extraction pipeline on a Flow of sensor windows.
 *
 * Supports extractors that return either:
 * - 1D features -> List<Float>
 * - 2D features -> List<List<Float>>
 *
 * Returns:
 * - 1D -> List<Float>
 * - 2D -> List<List<Float>>
 */
fun Flow<List<Pair<Long, List<Float>>>>.featurePipeline(
    extractors: List<FeatureExtractor>
): Flow<Any> {
    return this.map { window ->
        try {
            val featureOutputs = extractors.map { it.extract(window) }

            // Check if any extractor returned 2D features
            val is2D = featureOutputs.any { it is List<*> && it.firstOrNull() is List<*> }

            if (is2D) {
                // Combine 2D features across extractors
                val combined: MutableList<MutableList<Float>> = mutableListOf()

                // Determine number of rows (windows) from the first 2D extractor
                val rows = featureOutputs.first { it is List<*> && it.firstOrNull() is List<*> } as List<List<Float>>
                for (i in rows.indices) {
                    combined.add(mutableListOf())
                }

                // Add all features row by row
                featureOutputs.forEach { output ->
                    when (output) {
                        is List<*> -> {
                            if (output.firstOrNull() is List<*>) {
                                val output2D = output.filterIsInstance<List<Float>>()
                                output2D.forEachIndexed { idx, row ->
                                    combined[idx].addAll(row)
                                }
                            } else {
                                // 1D output -> replicate for each window
                                val row = output.filterIsInstance<Float>()
                                combined.forEach { it.addAll(row) }
                            }
                        }
                    }
                }

                combined

            } else {
                // All 1D -> flatten into single list
                featureOutputs.flatMap { output ->
                    when (output) {
                        is List<*> -> output.filterIsInstance<Float>()
                        else -> emptyList()
                    }
                }
            }

        } catch (ex: Exception) {
            Logger.e("featurePipeline error", ex)
            emptyList<Float>()
        }
    }
}