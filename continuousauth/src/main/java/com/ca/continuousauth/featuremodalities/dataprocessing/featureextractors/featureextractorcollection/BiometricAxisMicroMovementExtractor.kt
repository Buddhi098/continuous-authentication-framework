package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.*

class BiometricAxisMicroMovementExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        if (window.size < 10) return emptyList()

        val startTime = System.nanoTime() // start timing

        return try {
            val features = mutableListOf<Float>()
            val numAxes = 3

            // Process X, Y, Z axes separately to capture directional micro-movements
            for (axis in 0 until numAxes) {
                val signal = window.map { it.second[axis] }

                // 1. Hjorth Mobility
                val mobility = calculateMobility(signal)

                // 2. Hjorth Complexity
                val complexity = calculateComplexity(signal, mobility)

                // 3. Kurtosis
                val kurtosis = calculateKurtosis(signal)

                // 4. Zero Crossing Rate
                val zcr = calculateZCR(signal)

                // 5. Mean Absolute Jerk
                val jerk = calculateMeanAbsJerk(signal)

                features.addAll(listOf(mobility, complexity, kurtosis, zcr, jerk))
            }

            val endTime = System.nanoTime()
            val durationMs = (endTime - startTime) / 1_000_000.0
            Logger.d("BiometricAxisMicroMovementExtractor -> extract execution time: ${"%.3f".format(durationMs)} ms")

            features
        } catch (ex: Exception) {
            Logger.e("BiometricAxisMicroMovementExtractor Error", ex)
            emptyList()
        }
    }

    // ---------------- Core Micro-Movement Metrics ----------------

    private fun calculateMobility(signal: List<Float>): Float {
        val stdSignal = calculateStd(signal)
        val diff = signal.zipWithNext { a, b -> b - a }
        val stdDiff = calculateStd(diff)
        return if (stdSignal < 1e-6f) 0f else stdDiff / stdSignal
    }

    private fun calculateComplexity(signal: List<Float>, mobility: Float): Float {
        if (mobility < 1e-6f) return 0f
        val diff1 = signal.zipWithNext { a, b -> b - a }
        val diff2 = diff1.zipWithNext { a, b -> b - a }

        val stdDiff1 = calculateStd(diff1)
        val stdDiff2 = calculateStd(diff2)

        val mobilityOfDiff = if (stdDiff1 < 1e-6f) 0f else stdDiff2 / stdDiff1
        return mobilityOfDiff / mobility
    }

    private fun calculateKurtosis(signal: List<Float>): Float {
        val mean = signal.average().toFloat()
        val std = calculateStd(signal)
        if (std < 1e-6f) return 0f
        return signal.map { ((it - mean) / std).pow(4) }.average().toFloat() - 3f
    }

    private fun calculateZCR(signal: List<Float>): Float {
        val mean = signal.average().toFloat()
        val centered = signal.map { it - mean }
        var crossings = 0
        for (i in 1 until centered.size) {
            if (centered[i - 1] * centered[i] < 0) crossings++
        }
        return crossings.toFloat() / (signal.size - 1)
    }

    private fun calculateMeanAbsJerk(signal: List<Float>): Float {
        if (signal.size < 2) return 0f
        return signal.zipWithNext { a, b -> abs(b - a) }.average().toFloat()
    }

    private fun calculateStd(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        return sqrt(values.map { (it - mean).pow(2) }.average().toFloat())
    }
}
