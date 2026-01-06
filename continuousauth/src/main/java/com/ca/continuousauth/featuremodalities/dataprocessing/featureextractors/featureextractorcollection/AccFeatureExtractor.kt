package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import org.apache.commons.math3.stat.descriptive.moment.Kurtosis
import org.apache.commons.math3.stat.descriptive.moment.Skewness
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import kotlin.math.*

/**
 * Optimized Accelerometer Feature Extractor for Human Identification.
 * Focuses on Gait Analysis (Frequency), Motor Control (Jerk), and Orientation-Invariant (Magnitude) features.
 * Input speed: 100Hz.
 */
class AccFeatureExtractor(private val samplingRate: Double = 100.0) : FeatureExtractor {

    private val fft = FastFourierTransformer(DftNormalization.STANDARD)
    private val skewness = Skewness()
    private val kurtosis = Kurtosis()

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        if (window.isEmpty()) return emptyList()

        val size = window.size
        val features = mutableListOf<Float>()

        // 1. Pre-process: Extract Raw Axes and Calculate Magnitude (SVM)
        // SVM is critical as it makes features robust to phone orientation/rotation.
        val x = DoubleArray(size)
        val y = DoubleArray(size)
        val z = DoubleArray(size)
        val mag = DoubleArray(size)

        for (i in 0 until size) {
            val (vX, vY, vZ) = window[i].second
            x[i] = vX.toDouble()
            y[i] = vY.toDouble()
            z[i] = vZ.toDouble()
            mag[i] = sqrt(x[i] * x[i] + y[i] * y[i] + z[i] * z[i])
        }

        // -----------------------------
        // A. Magnitude (SVM) Statistics
        // -----------------------------
        // Basic stats on the total force vector (Orientation Invariant)
        features.add(mag.average().toFloat())      // Mean Intensity
        features.add(mag.std().toFloat())          // Variability
        features.add(skewness.evaluate(mag).toFloat()) // Asymmetry of force distribution
        features.add(kurtosis.evaluate(mag).toFloat()) // Impact sharpness (heavy tails)

        // -----------------------------
        // B. Jerk Features (Smoothness)
        // -----------------------------
        // Jerk is the derivative of acceleration. It measures motor control fine-tuning.
        // High jerk = sudden movements; Low jerk = fluid movements. Highly unique.
        val jerkMag = DoubleArray(size - 1) { i ->
            val djx = x[i+1] - x[i]
            val djy = y[i+1] - y[i]
            val djz = z[i+1] - z[i]
            sqrt(djx*djx + djy*djy + djz*djz) * samplingRate // Scale by Hz
        }
        features.add(jerkMag.average().toFloat())
        features.add(jerkMag.std().toFloat())

        // -----------------------------
        // C. Frequency Domain (Gait/Tremor Analysis)
        // -----------------------------
        // Pad to next power of 2 for Apache Commons FFT
        val paddedSize = nextPowerOfTwo(size)
        val fftData = DoubleArray(paddedSize) { i -> if (i < size) mag[i] else 0.0 }

        // Remove DC component (Gravity) before FFT to focus on motion
        val meanMag = mag.average()
        for(i in 0 until size) fftData[i] -= meanMag

        val fftResult = fft.transform(fftData, TransformType.FORWARD)
        val fftMag = DoubleArray(fftResult.size / 2) { i -> fftResult[i].abs() } // Take first half (Nyquist)

        // 1. Spectral Entropy (Complexity of movement)
        features.add(calculateSpectralEntropy(fftMag).toFloat())

        // 2. Dominant Frequency (Cadence/Walking speed)
        val maxIndex = fftMag.indices.maxByOrNull { fftMag[it] } ?: 0
        val freqBinResolution = samplingRate / paddedSize
        features.add((maxIndex * freqBinResolution).toFloat())

        // 3. Energy Band Ratios (Motion vs Tremor)
        // Band 1: 0.5 - 5 Hz (Voluntary human motion, walking)
        // Band 2: 5 - 20 Hz (Tremors, artifacts, rapid shakes)
        val energyTotal = fftMag.sumOf { it.pow(2) } + 1e-8
        val energyBandLow = sumEnergyInBand(fftMag, freqBinResolution, 0.5, 5.0)
        val energyBandHigh = sumEnergyInBand(fftMag, freqBinResolution, 5.0, 20.0)

        features.add((energyBandLow / energyTotal).toFloat())
        features.add((energyBandHigh / energyTotal).toFloat())

        // -----------------------------
        // D. Structural / Correlation Features
        // -----------------------------
        // Auto-correlation at Lag 1 (Short-term predictability)
        features.add(autocorrelationLag(mag, 1).toFloat())

        // Cross-Axis Correlations (Captures specific hand grip/holding angle patterns)
        features.add(correlation(x, y).toFloat())
        features.add(correlation(x, z).toFloat())
        features.add(correlation(y, z).toFloat())

        return features
    }

    // -----------------------------
    // Helpers
    // -----------------------------

    private fun DoubleArray.std(): Double {
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

    private fun autocorrelationLag(data: DoubleArray, lag: Int): Double {
        if (lag >= data.size) return 0.0
        val mean = data.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until data.size) {
            den += (data[i] - mean).pow(2)
            if (i < data.size - lag) {
                num += (data[i] - mean) * (data[i + lag] - mean)
            }
        }
        return if (den == 0.0) 0.0 else num / den
    }

    private fun calculateSpectralEntropy(fftMag: DoubleArray): Double {
        val sum = fftMag.sum() + 1e-8
        // Normalize PDF
        val pdf = fftMag.map { it / sum }
        return -pdf.filter { it > 0 }.sumOf { it * ln(it) }
    }

    private fun sumEnergyInBand(fftMag: DoubleArray, res: Double, minHz: Double, maxHz: Double): Double {
        val minIdx = (minHz / res).toInt().coerceIn(fftMag.indices)
        val maxIdx = (maxHz / res).toInt().coerceIn(fftMag.indices)
        var sum = 0.0
        for(i in minIdx..maxIdx) {
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