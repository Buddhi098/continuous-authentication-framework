package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class FrequencyDomainFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        return try {
            if (window.isEmpty()) return emptyList()
            val axisCount = window.first().second.size
            val features = mutableListOf<Float>()

            for (axis in 0 until axisCount) {
                val values = window.map { it.second[axis] }
                val fft = fftMagnitude(values)

                // Basic features
                features.add(fft.mean())
                features.add(fft.std())
                features.add(fft.maxOrNull() ?: 0f)
                features.add(fft.energy())

                // Discriminative frequency features
                features.add(fft.spectralCentroid())
                features.add(fft.spectralBandwidth())
                features.add(fft.spectralEntropy())
            }

            features
        } catch (ex: Exception) {
            Logger.e("FrequencyDomainFeatureExtractor error", ex)
            emptyList()
        }
    }

    // ------------------- Utility functions -------------------
    private fun List<Float>.mean(): Float = if (isEmpty()) 0f else this.fold(0f) { acc, f -> acc + f } / size

    private fun List<Float>.std(): Float {
        if (isEmpty()) return 0f
        val m = mean()
        return sqrt(this.fold(0f) { acc, f -> acc + (f - m).pow(2) } / size)
    }

    private fun List<Float>.energy(): Float = this.fold(0f) { acc, f -> acc + f * f }

    private fun List<Float>.spectralCentroid(): Float {
        val n = size
        if (n == 0) return 0f
        val weightedSum = this.mapIndexed { i, mag -> i * mag }.fold(0f) { acc, f -> acc + f }
        val totalMag = this.fold(0f) { acc, f -> acc + f }.takeIf { it != 0f } ?: 1f
        return weightedSum / totalMag
    }

    private fun List<Float>.spectralBandwidth(): Float {
        val centroid = spectralCentroid()
        val totalMag = this.fold(0f) { acc, f -> acc + f }.takeIf { it != 0f } ?: 1f
        return sqrt(this.mapIndexed { i, mag -> ((i - centroid).pow(2)) * mag }.fold(0f) { acc, f -> acc + f } / totalMag)
    }

    private fun List<Float>.spectralEntropy(): Float {
        val totalMag = this.fold(0f) { acc, f -> acc + f }.takeIf { it != 0f } ?: 1f
        val probs = this.map { mag -> mag / totalMag }
        return -probs.map { p -> if (p > 0) p * ln(p.toDouble()) else 0.0 }.sum().toFloat()
    }

    /**
     * Computes FFT magnitude for a 1D signal (simple DFT).
     */
    private fun fftMagnitude(signal: List<Float>): List<Float> {
        val n = signal.size
        if (n == 0) return emptyList()
        val result = mutableListOf<Float>()
        for (k in 0 until n) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val angle = 2.0 * Math.PI * k * t / n
                re += signal[t] * cos(angle)
                im -= signal[t] * sin(angle)
            }
            result.add(hypot(re, im).toFloat())
        }
        return result
    }
}
