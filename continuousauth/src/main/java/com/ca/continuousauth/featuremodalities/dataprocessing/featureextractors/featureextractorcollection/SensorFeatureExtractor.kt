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
        // 1. Magnitude (Pose Invariant)
        // -----------------------------
        val mag = FloatArray(n) { i ->
            sqrt(x[i]*x[i] + y[i]*y[i] + z[i]*z[i])
        }

        // -----------------------------
        // 2. Derivatives
        // -----------------------------
        val velocity = derivative(mag)
        val jerk = derivative(velocity)

        // -----------------------------
        // 3. Feature Vector (16)
        // -----------------------------
        val f = ArrayList<Float>(16)

        // ---- Magnitude (4)
        f.add(rms(mag))
        f.add(std(mag))
        f.add(mad(mag))
        f.add(peakToPeak(mag))

        // ---- Velocity (2)
        f.add(rms(velocity))
        f.add(std(velocity))

        // ---- Jerk (3)
        f.add(rms(jerk))
        f.add(energy(jerk))
        f.add(std(jerk))

        // ---- Rhythm (2)
        f.add(zeroCrossingRate(mag))
        f.add(zeroCrossingRate(jerk))

        // ---- Cross-axis correlation (3)
        f.add(correlation(x, y))
        f.add(correlation(y, z))
        f.add(correlation(z, x))

        // ---- Periodicity (1)
        f.add(autoCorrelationPeak(mag))

        // ---- Robust variability (1)
        f.add(mad(jerk))

        return f
    }

    // =====================================================
    // Derivative
    // =====================================================
    private fun derivative(data: FloatArray): FloatArray {
        val out = FloatArray(data.size)
        out[0] = 0f
        for (i in 1 until data.size) {
            out[i] = (data[i] - data[i - 1]) / dt
        }
        return out
    }

    // =====================================================
    // Stats
    // =====================================================
    private fun mean(data: FloatArray): Float =
        data.average().toFloat()

    private fun std(data: FloatArray): Float {
        val m = mean(data)
        val variance = data.sumOf { (it - m).toDouble().pow(2.0) } / data.size
        return sqrt(variance).toFloat()
    }

    private fun rms(data: FloatArray): Float =
        sqrt(data.sumOf { (it * it).toDouble() } / data.size).toFloat()

    private fun energy(data: FloatArray): Float =
        data.sumOf { (it * it).toDouble() }.toFloat()

    private fun median(data: FloatArray): Float {
        val sorted = data.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0)
            ((sorted[mid - 1] + sorted[mid]) / 2f)
        else sorted[mid]
    }

    private fun mad(data: FloatArray): Float {
        val med = median(data)
        return median(FloatArray(data.size) { i -> abs(data[i] - med) })
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

    // =====================================================
    // Correlation
    // =====================================================
    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val meanA = mean(a)
        val meanB = mean(b)

        var num = 0.0
        var denomA = 0.0
        var denomB = 0.0

        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            num += da * db
            denomA += da * da
            denomB += db * db
        }

        val denom = sqrt(denomA * denomB)
        return if (denom == 0.0) 0f else (num / denom).toFloat()
    }

    // =====================================================
    // Auto-correlation
    // =====================================================
    private fun autoCorrelationPeak(signal: FloatArray): Float {
        val n = signal.size
        var maxCorr = 0f

        for (lag in 1 until n / 2) {
            var sum = 0f
            for (i in 0 until n - lag) {
                sum += signal[i] * signal[i + lag]
            }
            if (sum > maxCorr) maxCorr = sum
        }

        return maxCorr / n
    }
}