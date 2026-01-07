package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import kotlin.math.*

/**
 * Lightweight Sensor Feature Extractor (10 Features)
 * Optimized for Continuous Authentication & TFLite deployment.
 */
class SensorFeatureExtractor(
    private val samplingRate: Int = 50
) : FeatureExtractor {

    private val dt = 1.0f / samplingRate

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        val n = window.size
        if (n < 2) return emptyList()

        // -----------------------------
        // 1. Unpack Sensor Axes
        // -----------------------------
        val x = FloatArray(n)
        val y = FloatArray(n)
        val z = FloatArray(n)

        for (i in 0 until n) {
            val v = window[i].second
            x[i] = v.getOrElse(0) { 0f }
            y[i] = v.getOrElse(1) { 0f }
            z[i] = v.getOrElse(2) { 0f }
        }

        val axes = listOf(x, y, z)

        // -----------------------------
        // 2. Time-Domain Features
        // -----------------------------
        val std = axes.map { standardDeviation(it) }
        val rms = axes.map { rootMeanSquare(it) }

        // -----------------------------
        // 3. Frequency-Domain Features
        // -----------------------------
        val centeredAxes = axes.map { center(it) }
        val fftMagnitudes = centeredAxes.map { computeFFTMagnitude(it) }
        val fftFreqs = rfftFreq(n, dt)

        val dominantFreq = fftMagnitudes.map { mags ->
            val idx = mags.indices.maxByOrNull { mags[it] } ?: 0
            fftFreqs[idx]
        }.average().toFloat()

        val spectralEntropy = fftMagnitudes.map { mags ->
            val sum = mags.sum() + 1e-8f
            var entropy = 0f
            for (m in mags) {
                val p = m / sum
                if (p > 0f) entropy += p * ln(p)
            }
            -entropy
        }.average().toFloat()

        // -----------------------------
        // 4. Magnitude (SVM) Features
        // -----------------------------
        val svm = FloatArray(n) { i ->
            sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
        }

        val svmMean = svm.average().toFloat()

        // -----------------------------
        // 5. Cross-Axis Correlation
        // -----------------------------
        val corrXY = correlation(x, y)
        val corrXZ = correlation(x, z)
        val corrYZ = correlation(y, z)
        val meanCorr = (corrXY + corrXZ + corrYZ) / 3f

        // -----------------------------
        // 6. Final Feature Vector (10)
        // -----------------------------
        val features = ArrayList<Float>(10)

        // STD (X,Y,Z)
        features.addAll(std)

        // RMS (X,Y,Z)
        features.addAll(rms)

        // Frequency features
        features.add(dominantFreq)
        features.add(spectralEntropy)

        // Global features
        features.add(svmMean)
        features.add(meanCorr)

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

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        val meanA = a.average()
        val meanB = b.average()

        var num = 0.0
        var denA = 0.0
        var denB = 0.0

        for (i in a.indices) {
            val da = a[i] - meanA
            val db = b[i] - meanB
            num += da * db
            denA += da * da
            denB += db * db
        }

        val denom = sqrt(denA) * sqrt(denB)
        return if (denom == 0.0) 0f else (num / denom).toFloat()
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
