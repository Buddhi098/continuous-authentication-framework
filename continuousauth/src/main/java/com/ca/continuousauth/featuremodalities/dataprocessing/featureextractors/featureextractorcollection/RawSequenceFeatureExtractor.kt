package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import kotlin.math.sqrt

/**
 * 2D Raw Sequence Feature Extractor
 *
 * Each record becomes [x, y, z, magnitude].
 * Output is a 2D list: List of rows (window) × features.
 */
class RawSequenceFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<List<Float>> {
        if (window.isEmpty()) return emptyList()

        val features2D = ArrayList<List<Float>>(window.size)

        for (record in window) {
            val axes = record.second
            val x = axes.getOrElse(0) { 0f }
            val y = axes.getOrElse(1) { 0f }
            val z = axes.getOrElse(2) { 0f }
            val magnitude = sqrt(x * x + y * y + z * z)

            features2D.add(listOf(x, y, z, magnitude))
        }

        return features2D
    }
}