package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.correlation
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.energy
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.magnitude
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.mean
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.rms
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.std

class AccelerometerFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        if (window.isEmpty()) return emptyList()

        val x = window.map { it.second[0] }
        val y = window.map { it.second[1] }
        val z = window.map { it.second[2] }
        val mag = magnitude(x, y, z)

        val features = mutableListOf<Float>()

        listOf(x, y, z, mag).forEach { axis ->
            features += mean(axis)
            features += std(axis)
            features += rms(axis)
            features += axis.minOrNull() ?: 0f
            features += axis.maxOrNull() ?: 0f
            features += energy(axis)
        }

        // Cross-axis coordination (holding style)
        features += correlation(x, y)
        features += correlation(y, z)
        features += correlation(x, z)

        // Jerk (smoothness of motion)
        val jerk = mag.zipWithNext { a, b -> b - a }
        features += mean(jerk)
        features += std(jerk)

        return features
    }
}
