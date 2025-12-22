package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.*

class GyroscopeFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        if (window.size < 5) return emptyList()

        val startTime = System.nanoTime() // Start timing

        return try {
            // 1. Magnitude calculation (Angular Speed)
            val mag = window.map { (_, v) -> sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) }

            // 2. Angular Jerk (Rate of change of angular velocity)
            val jerk = calculateJerk(window)

            val features = mutableListOf<Float>()

            // --- Statistical Features ---
            features.add(rms(mag))
            features.add(calculateIQR(mag))      // Robustness to outliers
            features.add(calculateKurtosis(mag)) // Measures spikiness

            // --- Complexity Features (Hjorth) ---
            val (mobility, complexity) = calculateHjorth(mag)
            features.add(mobility)
            features.add(complexity)

            // --- Information Theory ---
            features.add(calculateLogEntropy(mag))

            // --- Cross-Axis Coupling ---
            features.add(calculateCorrelation(window.map { it.second[0] }, window.map { it.second[1] })) // X-Y
            features.add(calculateCorrelation(window.map { it.second[1] }, window.map { it.second[2] })) // Y-Z

            // --- Motion Jerk Metrics ---
            features.add(rms(jerk))
            features.add(zeroCrossingRate(jerk)) // Frequency of micro-reversals

            val endTime = System.nanoTime()
            val durationMs = (endTime - startTime) / 1_000_000.0
            Logger.d("GyroscopeFeatureExtractor -> extract execution time: ${"%.3f".format(durationMs)} ms")

            features
        } catch (ex: Exception) {
            Logger.e("GyroscopeFeatureExtractor error", ex)
            emptyList()
        }
    }

    // ---------------- Hjorth Parameters ----------------
    private fun calculateHjorth(values: List<Float>): Pair<Float, Float> {
        val d1 = values.zipWithNext { a, b -> b - a }
        val d2 = d1.zipWithNext { a, b -> b - a }

        val sigma0 = std(values)
        val sigma1 = std(d1)
        val sigma2 = std(d2)

        if (sigma0 < 1e-6f || sigma1 < 1e-6f) return 0f to 0f

        val mobility = sigma1 / sigma0
        val complexity = (sigma2 / sigma1) / mobility
        return mobility to complexity
    }

    private fun calculateLogEntropy(values: List<Float>): Float {
        return values.sumOf {
            val p = it.toDouble().pow(2)
            if (p < 1e-6) 0.0 else ln(p)
        }.toFloat()
    }

    private fun calculateCorrelation(axis1: List<Float>, axis2: List<Float>): Float {
        val m1 = axis1.average().toFloat()
        val m2 = axis2.average().toFloat()
        var num = 0f
        var den1 = 0f
        var den2 = 0f
        for (i in axis1.indices) {
            val d1 = axis1[i] - m1
            val d2 = axis2[i] - m2
            num += d1 * d2
            den1 += d1 * d1
            den2 += d2 * d2
        }
        val d = sqrt(den1 * den2)
        return if (d < 1e-6f) 0f else num / d
    }

    private fun calculateIQR(values: List<Float>): Float {
        val sorted = values.sorted()
        val q1 = sorted[(sorted.size * 0.25).toInt()]
        val q3 = sorted[(sorted.size * 0.75).toInt()]
        return q3 - q1
    }

    private fun calculateKurtosis(values: List<Float>): Float {
        val mean = values.average().toFloat()
        val std = std(values)
        if (std < 1e-6f) return 0f
        return values.map { ((it - mean) / std).pow(4) }.average().toFloat() - 3f
    }

    private fun calculateJerk(window: List<Pair<Long, List<Float>>>): List<Float> {
        return window.zipWithNext { a, b ->
            val dt = (b.first - a.first).toFloat() / 1000f
            if (dt <= 0) 0f else {
                val diffs = b.second.zip(a.second) { vB, vA -> (vB - vA) / dt }
                sqrt(diffs.sumOf { (it * it).toDouble() }).toFloat()
            }
        }
    }

    // --- Helper Math ---
    private fun rms(v: List<Float>) = if (v.isEmpty()) 0f else sqrt(v.map { it * it }.average().toFloat())
    private fun std(v: List<Float>): Float {
        if (v.size < 2) return 0f
        val m = v.average().toFloat()
        return sqrt(v.map { (it - m).pow(2) }.average().toFloat())
    }
    private fun zeroCrossingRate(v: List<Float>): Float {
        val m = v.average().toFloat()
        val centered = v.map { it - m }
        var count = 0
        for (i in 1 until centered.size) if (centered[i - 1] * centered[i] < 0) count++
        return count.toFloat() / (v.size - 1)
    }
}
