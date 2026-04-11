package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import kotlin.math.*

class SensorFeatureExtractor(private val samplingRate: Int = 50) : FeatureExtractor {

    private val dt = 1.0f / samplingRate

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        val n = window.size
        if (n < 5) return emptyList()

        val x = FloatArray(n)
        val y = FloatArray(n)
        val z = FloatArray(n)

        for (i in 0 until n) {
            val v = window[i].second
            x[i] = v.getOrElse(0) { 0f }
            y[i] = v.getOrElse(1) { 0f }
            z[i] = v.getOrElse(2) { 0f }
        }

        // -----------------------------
        // Magnitude
        // -----------------------------
        val mag = FloatArray(n) { i ->
            sqrt(x[i]*x[i] + y[i]*y[i] + z[i]*z[i])
        }

        // -----------------------------
        // Derivatives
        // -----------------------------
        val velocity = derivative(mag)
        val jerk = derivative(velocity)

        val f = ArrayList<Float>(10)

        // ---- Magnitude (3)
        f.add(rms(mag))
        f.add(std(mag))
        f.add(peakToPeak(mag))

        // ---- Velocity (2)
        f.add(rms(velocity))
        f.add(std(velocity))

        // ---- Jerk (2)
        f.add(rms(jerk))
        f.add(std(jerk))

        // ---- Rhythm (2)
        f.add(zeroCrossingRate(mag))
        f.add(zeroCrossingRate(jerk))

        return f
    }

    // =====================================================
    // Derivative
    // =====================================================
    private fun derivative(data: FloatArray): FloatArray {
        val out = FloatArray(data.size)
        for (i in 1 until data.size) {
            out[i] = (data[i] - data[i - 1]) / dt
        }
        return out
    }

    // =====================================================
    // Stats
    // =====================================================
    private fun mean(data: FloatArray): Float =
        data.sum() / data.size

    private fun std(data: FloatArray): Float {
        val m = mean(data)
        var sum = 0f
        for (v in data) {
            val d = v - m
            sum += d * d
        }
        return sqrt(sum / data.size)
    }

    private fun rms(data: FloatArray): Float {
        var sum = 0f
        for (v in data) sum += v * v
        return sqrt(sum / data.size)
    }

    private fun peakToPeak(data: FloatArray): Float =
        (data.maxOrNull() ?: 0f) - (data.minOrNull() ?: 0f)

    private fun zeroCrossingRate(data: FloatArray): Float {
        var count = 0
        for (i in 1 until data.size) {
            if ((data[i] >= 0 && data[i - 1] < 0) ||
                (data[i] < 0 && data[i - 1] >= 0)) {
                count++
            }
        }
        return count.toFloat() / data.size
    }
}