package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import org.apache.commons.math3.stat.descriptive.moment.Kurtosis
import org.apache.commons.math3.stat.descriptive.moment.Skewness
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import kotlin.math.*

/**
 * Optimized Gyroscope Feature Extractor for Human Identification.
 * Focuses on Angular Velocity Magnitude, Rotational Smoothness (Jerk), and Micro-Tremors.
 * Input speed: 100Hz.
 */
class GyroFeatureExtractor(private val samplingRate: Double = 100.0) : FeatureExtractor {

    private val fft = FastFourierTransformer(DftNormalization.STANDARD)
    private val skewness = Skewness()
    private val kurtosis = Kurtosis()

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        if (window.isEmpty()) return emptyList()

        val size = window.size
        val features = mutableListOf<Float>()

        // Arrays for axes and magnitude
        val x = DoubleArray(size)
        val y = DoubleArray(size)
        val z = DoubleArray(size)
        val mag = DoubleArray(size)

        // 1. Pre-process: Calculate Angular Velocity Magnitude
        // Magnitude = sqrt(x^2 + y^2 + z^2)
        // This is robust against how the user holds the phone (portrait vs landscape).
        for (i in 0 until size) {
            val (vX, vY, vZ) = window[i].second
            x[i] = vX.toDouble()
            y[i] = vY.toDouble()
            z[i] = vZ.toDouble()
            mag[i] = sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
        }

        // -----------------------------
        // A. Rotational Intensity & Stability
        // -----------------------------
        features.add(mag.average().toFloat())      // Avg Rotation Speed
        features.add(mag.std().toFloat())          // Rotation Variability
        features.add(skewness.evaluate(mag).toFloat()) // Asymmetry of rotation
        // Kurtosis on Gyro indicates "suddenness" of turns.
        // High kurtosis = mostly steady with sharp turns. Low = constant turning.
        features.add(kurtosis.evaluate(mag).toFloat())

        // -----------------------------
        // B. Angular Jerk (Micro-movement Smoothness)
        // -----------------------------
        // Angular Jerk is the derivative of Angular Velocity.
        // It captures the fine-motor control of the wrist.
        val jerkMag = DoubleArray(size - 1) { i ->
            val djx = x[i+1] - x[i]
            val djy = y[i+1] - y[i]
            val djz = z[i+1] - z[i]
            sqrt(djx*djx + djy*djy + djz*djz) * samplingRate
        }
        features.add(jerkMag.average().toFloat()) // Mean "Shakiness"
        features.add(jerkMag.std().toFloat())     // Variability of Shakiness

        // -----------------------------
        // C. Frequency Analysis (Tremor Detection)
        // -----------------------------
        // Pad to power of 2 for FFT
        val paddedSize = nextPowerOfTwo(size)
        val fftData = DoubleArray(paddedSize) { i -> if (i < size) mag[i] else 0.0 }

        // Remove DC offset (static rotation bias)
        val meanMag = mag.average()
        for(i in 0 until size) fftData[i] -= meanMag

        val fftResult = fft.transform(fftData, TransformType.FORWARD)
        val fftMag = DoubleArray(fftResult.size / 2) { i -> fftResult[i].abs() }
        val freqRes = samplingRate / paddedSize

        // 1. Spectral Entropy (Complexity of rotation patterns)
        features.add(calculateSpectralEntropy(fftMag).toFloat())

        // 2. Dominant Frequency
        val maxIndex = fftMag.indices.maxByOrNull { fftMag[it] } ?: 0
        features.add((maxIndex * freqRes).toFloat())

        // 3. Band Power Ratios (Biometric Signature)
        // Band 1: 0.1 - 4 Hz (Voluntary Wrist Rotation)
        // Band 2: 4 - 12 Hz (Physiological Tremor / Micro-shakes)
        val totalEnergy = fftMag.sumOf { it.pow(2) } + 1e-8
        val energyVoluntary = sumEnergyInBand(fftMag, freqRes, 0.1, 4.0)
        val energyTremor = sumEnergyInBand(fftMag, freqRes, 4.0, 12.0)

        features.add((energyVoluntary / totalEnergy).toFloat())
        features.add((energyTremor / totalEnergy).toFloat())

        // -----------------------------
        // D. Wrist Mechanics (Cross-Axis Correlation)
        // -----------------------------
        // How axes move together describes the geometry of the wrist joint.
        features.add(correlation(x, y).toFloat())
        features.add(correlation(x, z).toFloat())
        features.add(correlation(y, z).toFloat())

        return features
    }

    // -----------------------------
    // Optimized Helpers
    // -----------------------------

    private fun DoubleArray.std(): Double {
        if (isEmpty()) return 0.0
        val m = average()
        var sum = 0.0
        for (v in this) sum += (v - m).pow(2)
        return sqrt(sum / size)
    }

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var num = 0.0
        var denA = 0.0
        var denB = 0.0
        for (i in a.indices) {
            val da = a[i] - ma
            val db = b[i] - mb
            num += da * db
            denA += da * da
            denB += db * db
        }
        val den = sqrt(denA * denB)
        return if (den == 0.0) 0.0 else num / den
    }

    private fun calculateSpectralEntropy(fftMag: DoubleArray): Double {
        val sum = fftMag.sum() + 1e-8
        val pdf = fftMag.map { it / sum }
        return -pdf.filter { it > 0 }.sumOf { it * ln(it) }
    }

    private fun sumEnergyInBand(fftMag: DoubleArray, res: Double, minHz: Double, maxHz: Double): Double {
        val minIdx = (minHz / res).toInt().coerceIn(fftMag.indices)
        val maxIdx = (maxHz / res).toInt().coerceIn(fftMag.indices)
        var sum = 0.0
        for (i in minIdx..maxIdx) {
            sum += fftMag[i].pow(2)
        }
        return sum
    }

    private fun nextPowerOfTwo(n: Int): Int {
        var count = 1
        while (count < n) count = count shl 1
        return count
    }
}