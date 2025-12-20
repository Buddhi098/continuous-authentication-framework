package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.correlation
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.energy
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.magnitude
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.mean
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.rms
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.std
import kotlin.math.abs

class GyroscopeFeatureExtractor : FeatureExtractor {

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
            features += energy(axis)
        }

        // Rotational coordination (hand posture)
        features += correlation(x, y)
        features += correlation(y, z)
        features += correlation(x, z)

        // Rotation smoothness
        val delta = mag.zipWithNext { a, b -> abs(b - a) }
        features += mean(delta)
        features += std(delta)

        return features
    }
}
