package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.*

class TimeDomainFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        return try {
            if (window.isEmpty()) return emptyList()
            val axisCount = window.first().second.size
            val features = mutableListOf<Float>()

            for (axis in 0 until axisCount) {
                val values = window.map { it.second[axis] }

                // Original features
                features.add(maxSlope(values))
                features.add(avgSlope(values))
                features.add(totalChange(values))

                // New robust features
                features.add(mean(values))
                features.add(variance(values))
                features.add(rms(values))
                features.add(zeroCrossingRate(values))
                features.add(skewness(values))
                features.add(kurtosis(values))
            }

            features
        } catch (ex: Exception) {
            Logger.e("TimeDomainFeatureExtractor error", ex)
            emptyList()
        }
    }

    // -----------------------
    // Original Features
    // -----------------------
    private fun maxSlope(values: List<Float>): Float {
        if (values.size < 2) return 0f
        return values.zipWithNext { a, b -> abs(b - a) }.maxOrNull() ?: 0f
    }

    private fun avgSlope(values: List<Float>): Float {
        if (values.size < 2) return 0f
        return values.zipWithNext { a, b -> abs(b - a) }.average().toFloat()
    }

    private fun totalChange(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        return abs(values.last() - values.first())
    }

    // -----------------------
    // New Features
    // -----------------------
    private fun mean(values: List<Float>) = if (values.isEmpty()) 0f else values.average().toFloat()

    private fun variance(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val avg = mean(values)
        return values.map { (it - avg).pow(2) }.average().toFloat()
    }

    private fun rms(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        return sqrt(values.map { it * it }.average()).toFloat()
    }

    private fun zeroCrossingRate(values: List<Float>): Float {
        if (values.size < 2) return 0f
        var count = 0
        for (i in 1 until values.size) {
            if ((values[i - 1] * values[i]) < 0) count++
        }
        return count.toFloat() / (values.size - 1)
    }

    private fun skewness(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val avg = mean(values)
        val std = sqrt(variance(values))
        if (std == 0f) return 0f
        return values.map { ((it - avg) / std).pow(3) }.average().toFloat()
    }

    private fun kurtosis(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val avg = mean(values)
        val std = sqrt(variance(values))
        if (std == 0f) return 0f
        return values.map { ((it - avg) / std).pow(4) }.average().toFloat() - 3f
    }
}
