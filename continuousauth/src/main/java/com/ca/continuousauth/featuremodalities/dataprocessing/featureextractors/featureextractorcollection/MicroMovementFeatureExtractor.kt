package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.*

/**
 * MicroMovementFeatureExtractor
 *
 * Extracts micro-movement features from high-frequency sensor windows.
 * Features per axis:
 * - Mean absolute delta (MAD)
 * - Standard deviation of delta
 * - Peak count
 * - Zero-crossing rate
 * - RMS (Root Mean Square)
 */
class MicroMovementFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        return try {
            if (window.isEmpty()) return emptyList()
            val axisCount = window.first().second.size
            val features = mutableListOf<Float>()

            for (axis in 0 until axisCount) {
                val values = window.map { it.second[axis] }

                features.add(meanAbsoluteDelta(values))
                features.add(stdDelta(values))
                features.add(peakCount(values).toFloat())
                features.add(zeroCrossingRate(values))
                features.add(rms(values))

                features.add(mean(values))
                features.add(variance(values))
                features.add(range(values))

                features.add(meanJerk(values))
                features.add(jerkRms(values))

                features.add(skewness(values))
                features.add(kurtosis(values))

                features.add(entropy(values))
                features.add(slopeSignChanges(values))
                features.add(peakDensity(values))
            }

            features
        } catch (ex: Exception) {
            Logger.e("MicroMovementFeatureExtractor error", ex)
            emptyList()
        }
    }

    // ------------------------
    // Core helpers
    // ------------------------

    private fun mean(values: List<Float>) =
        if (values.isEmpty()) 0f else values.average().toFloat()

    private fun variance(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val m = mean(values)
        return values.map { (it - m).pow(2) }.average().toFloat()
    }

    private fun range(values: List<Float>): Float =
        if (values.isEmpty()) 0f else (values.maxOrNull()!! - values.minOrNull()!!)

    private fun meanAbsoluteDelta(values: List<Float>): Float {
        if (values.size < 2) return 0f
        return values.zipWithNext { a, b -> abs(b - a) }.average().toFloat()
    }

    private fun stdDelta(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val deltas = values.zipWithNext { a, b -> b - a }
        val mean = deltas.average()
        return sqrt(deltas.map { (it - mean).pow(2) }.average()).toFloat()
    }

    private fun rms(values: List<Float>): Float =
        sqrt(values.map { it * it }.average()).toFloat()

    // ------------------------
    // Jerk features
    // ------------------------

    private fun meanJerk(values: List<Float>): Float {
        if (values.size < 3) return 0f
        val jerk = values.zipWithNext().zipWithNext { (a, b), (c, _) -> c - 2*b + a }
        return jerk.map { abs(it) }.average().toFloat()
    }

    private fun jerkRms(values: List<Float>): Float {
        if (values.size < 3) return 0f
        val jerk = values.zipWithNext().zipWithNext { (a, b), (c, _) -> c - 2*b + a }
        return sqrt(jerk.map { it * it }.average()).toFloat()
    }

    // ------------------------
    // Distribution shape
    // ------------------------

    private fun skewness(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val m = mean(values)
        val std = sqrt(variance(values))
        if (std == 0f) return 0f
        return values.map { ((it - m) / std).pow(3) }.average().toFloat()
    }

    private fun kurtosis(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val m = mean(values)
        val std = sqrt(variance(values))
        if (std == 0f) return 0f
        return values.map { ((it - m) / std).pow(4) }.average().toFloat()
    }

    // ------------------------
    // Complexity measures
    // ------------------------

    private fun entropy(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val absVals = values.map { abs(it) }
        val sum = absVals.sum()
        if (sum == 0f) return 0f
        return -absVals.map {
            val p = it / sum
            if (p == 0f) 0f else p * ln(p)
        }.sum()
    }

    private fun slopeSignChanges(values: List<Float>): Float {
        if (values.size < 3) return 0f
        var count = 0
        for (i in 1 until values.size - 1) {
            val diff1 = values[i] - values[i - 1]
            val diff2 = values[i + 1] - values[i]
            if (diff1 * diff2 < 0) count++
        }
        return count.toFloat()
    }

    private fun peakDensity(values: List<Float>): Float =
        if (values.isEmpty()) 0f else peakCount(values).toFloat() / values.size

    private fun peakCount(values: List<Float>): Int {
        if (values.size < 3) return 0
        var count = 0
        for (i in 1 until values.size - 1) {
            if ((values[i] > values[i - 1] && values[i] > values[i + 1]) ||
                (values[i] < values[i - 1] && values[i] < values[i + 1])
            ) count++
        }
        return count
    }

    private fun zeroCrossingRate(values: List<Float>): Float {
        if (values.size < 2) return 0f
        var count = 0
        for (i in 1 until values.size) {
            if (values[i - 1] * values[i] < 0) count++
        }
        return count.toFloat() / (values.size - 1)
    }
}
