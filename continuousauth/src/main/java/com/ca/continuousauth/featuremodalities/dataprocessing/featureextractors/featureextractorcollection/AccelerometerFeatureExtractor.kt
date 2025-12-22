package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.*

class AccelerometerFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        if (window.size < 5) return emptyList()

        val startTime = System.nanoTime() // start timing

        return try {
            // 1. Magnitudes
            val magAccel = window.map { (_, axes) ->
                sqrt(axes.fold(0f) { acc, v -> acc + v * v }.toDouble()).toFloat()
            }
            val magJerk = calculateMagJerk(window)

            val features = mutableListOf<Float>()

            // 2. Statistical Distribution
            features.add(rms(magAccel))
            features.add(std(magAccel))
            features.add(calculateSkewness(magAccel))
            features.add(calculateKurtosis(magAccel))

            // 3. Mobility & Complexity
            features.add(calculateMobility(magAccel))

            // 4. Jerk-based Features
            features.add(meanAbs(magJerk))
            features.add(rms(magJerk))

            // 5. Temporal Dynamics
            features.add(zeroCrossingRate(magAccel))
            features.add(calculatePulseMetric(magAccel))

            val endTime = System.nanoTime()
            val durationMs = (endTime - startTime) / 1_000_000.0
            Logger.d("AccelerometerFeatureExtractor -> extract execution time: ${"%.3f".format(durationMs)} ms")

            features
        } catch (ex: Exception) {
            Logger.e("AccelerometerFeatureExtractor error", ex)
            emptyList()
        }
    }

    // ---------------- Micro-Movement Metrics ----------------

    private fun calculateSkewness(values: List<Float>): Float {
        val mean = values.average().toFloat()
        val std = std(values)
        if (std < 0.0001f) return 0f
        return values.map { ((it - mean) / std).pow(3) }.average().toFloat()
    }

    private fun calculateKurtosis(values: List<Float>): Float {
        val mean = values.average().toFloat()
        val std = std(values)
        if (std < 0.0001f) return 0f
        return values.map { ((it - mean) / std).pow(4) }.average().toFloat() - 3f
    }

    private fun calculateMobility(values: List<Float>): Float {
        val varSignal = std(values).pow(2)
        val diff = values.zipWithNext { a, b -> b - a }
        val varDiff = std(diff).pow(2)
        if (varSignal < 0.0001f) return 0f
        return sqrt(varDiff / varSignal)
    }

    private fun calculateMagJerk(window: List<Pair<Long, List<Float>>>): List<Float> {
        val numAxes = window[0].second.size
        val jerks = mutableListOf<Float>()
        for (i in 1 until window.size) {
            var sumSq = 0f
            for (axis in 0 until numAxes) {
                val d = window[i].second[axis] - window[i - 1].second[axis]
                sumSq += d * d
            }
            jerks.add(sqrt(sumSq))
        }
        return jerks
    }

    private fun calculatePulseMetric(values: List<Float>): Float {
        val r = rms(values)
        if (r < 0.0001f) return 0f
        return (values.maxOrNull() ?: 0f) / r
    }

    // ---------------- Base Math Utilities ----------------

    private fun meanAbs(values: List<Float>) = if (values.isEmpty()) 0f else values.map { abs(it) }.average().toFloat()

    private fun rms(values: List<Float>) = if (values.isEmpty()) 0f else sqrt(values.map { it * it }.average()).toFloat()

    private fun std(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average()
        return sqrt(values.map { (it - mean).pow(2) }.average()).toFloat()
    }

    private fun zeroCrossingRate(values: List<Float>): Float {
        val mean = values.average().toFloat()
        val centered = values.map { it - mean }
        var count = 0
        for (i in 1 until centered.size) {
            if (centered[i - 1] * centered[i] < 0) count++
        }
        return count.toFloat() / (values.size - 1)
    }
}
