package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import kotlin.math.*

/**
 * Lightweight Sensor Feature Extractor (14 Features - Pose Invariant) Optimized for Continuous
 * Authentication & TFLite deployment.
 */
class SensorFeatureExtractor(private val samplingRate: Int = 50) : FeatureExtractor {

    private val dt = 1.0f / samplingRate

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        val n = window.size
        if (n < 2) return emptyList()

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
        // 1. Pose-Invariant Metrics
        // -----------------------------
        // SVM (Signal Vector Magnitude)
        val svm = FloatArray(n) { i -> sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i]) }

        // Jerk Magnitude (Derivative of Acceleration)
        val jerk = FloatArray(n)
        jerk[0] = 0f // First element has no derivative
        for (i in 1 until n) {
            val dx = x[i] - x[i - 1]
            val dy = y[i] - y[i - 1]
            val dz = z[i] - z[i - 1]
            jerk[i] = sqrt(dx * dx + dy * dy + dz * dz)
        }

        val signals = listOf(svm, jerk)

        // -----------------------------
        // 2. Time-Domain Features
        // -----------------------------
        val std = signals.map { standardDeviation(it) }
        val rms = signals.map { rootMeanSquare(it) }
        val skewness = signals.map { skewness(it) }
        val kurtosis = signals.map { kurtosis(it) }
        val energy = signals.map { energy(it) }

        // -----------------------------
        // 3. Frequency-Domain Features
        // -----------------------------
        val centeredSignals = signals.map { center(it) }
        val fftMagnitudes = centeredSignals.map { computeFFTMagnitude(it) }
        val fftFreqs = rfftFreq(n, dt)

        val dominantFreq =
                fftMagnitudes.map { mags ->
                    val idx = mags.indices.maxByOrNull { mags[it] } ?: 0
                    fftFreqs[idx]
                }

        val spectralEntropy =
                fftMagnitudes.map { mags ->
                    val sum = mags.sum() + 1e-8f
                    var entropy = 0f
                    for (m in mags) {
                        val p = m / sum
                        if (p > 0f) entropy += p * ln(p)
                    }
                    -entropy
                }

        // -----------------------------
        // 4. Final Feature Vector (14)
        // -----------------------------
        val features = ArrayList<Float>(14)

        // 2 signals * 5 time-domain = 10 features
        features.addAll(std)
        features.addAll(rms)
        features.addAll(skewness)
        features.addAll(kurtosis)
        features.addAll(energy)

        // 2 signals * 2 frequency-domain = 4 features
        features.addAll(dominantFreq)
        features.addAll(spectralEntropy)

        return features
    }

    // =====================================================
    // Helper Functions
    // =====================================================

    private fun center(data: FloatArray): FloatArray {
        val mean = data.average().toFloat()
        return FloatArray(data.size) { i -> data[i] - mean }
    }

    private fun standardDeviation(data: FloatArray): Float {
        val mean = data.average()
        val sum = data.sumOf { (it - mean).pow(2) }
        return sqrt(sum / data.size).toFloat()
    }

    private fun rootMeanSquare(data: FloatArray): Float {
        val meanSq = data.sumOf { (it * it).toDouble() } / data.size
        return sqrt(meanSq).toFloat()
    }

    private fun energy(data: FloatArray): Float {
        return data.sumOf { (it * it).toDouble() }.toFloat()
    }

    private fun skewness(data: FloatArray): Float {
        val n = data.size
        if (n < 3) return 0f
        val mean = data.average()
        val m2 = data.sumOf { (it - mean).pow(2) } / n
        val m3 = data.sumOf { (it - mean).pow(3) } / n
        val s2 = sqrt(m2)
        if (s2 == 0.0) return 0f
        return (m3 / (s2.pow(3))).toFloat()
    }

    private fun kurtosis(data: FloatArray): Float {
        val n = data.size
        if (n < 4) return 0f
        val mean = data.average()
        val m2 = data.sumOf { (it - mean).pow(2) } / n
        val m4 = data.sumOf { (it - mean).pow(4) } / n
        if (m2 == 0.0) return 0f
        return (m4 / (m2.pow(2)) - 3.0).toFloat()
    }

    // =====================================================
    // FFT (Magnitude Only)
    // =====================================================

    private fun rfftFreq(n: Int, dt: Float): FloatArray {
        val size = (n / 2) + 1
        val freqs = FloatArray(size)
        val factor = 1f / (n * dt)
        for (i in 0 until size) {
            freqs[i] = i * factor
        }
        return freqs
    }

    private fun computeFFTMagnitude(input: FloatArray): FloatArray {
        val n = input.size
        val outputSize = (n / 2) + 1
        val magnitudes = FloatArray(outputSize)

        for (k in 0 until outputSize) {
            var real = 0.0
            var imag = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * PI * k * t / n
                real += input[t] * cos(angle)
                imag += input[t] * sin(angle)
            }
            magnitudes[k] = sqrt(real * real + imag * imag).toFloat()
        }
        return magnitudes
    }
}
