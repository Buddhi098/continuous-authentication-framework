package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.correlation
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.magnitude
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.mean
import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils.FeatureUtils.std
import kotlin.math.abs

class MagnetometerFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        if (window.isEmpty()) return emptyList()

        val x = window.map { it.second[0] }
        val y = window.map { it.second[1] }
        val z = window.map { it.second[2] }
        val mag = magnitude(x, y, z)

        val features = mutableListOf<Float>()

        // Orientation stability (NOT absolute values)
        features += std(x)
        features += std(y)
        features += std(z)

        // Relative magnitude change (environment-agnostic)
        val deltaMag = mag.zipWithNext { a, b -> abs(b - a) }
        features += mean(deltaMag)
        features += std(deltaMag)

        // Holding bias (normalized)
        features += correlation(x, y)
        features += correlation(y, z)
        features += correlation(x, z)

        return features
    }
}
