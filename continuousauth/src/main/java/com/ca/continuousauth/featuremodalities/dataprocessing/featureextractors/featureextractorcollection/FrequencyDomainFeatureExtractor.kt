package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.cos
import kotlin.math.hypot
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

                features.add(fft.mean())
                features.add(fft.std())
                features.add(fft.max())
                features.add(fft.energy())
            }

            features
        } catch (ex: Exception) {
            Logger.e("FrequencyDomainFeatureExtractor error", ex)
            emptyList()
        }
    }

    private fun List<Float>.mean() = if (isEmpty()) 0f else sum() / size
    private fun List<Float>.std() = if (isEmpty()) 0f else sqrt(map { (it - mean()).pow(2) }.sum() / size)
    private fun List<Float>.energy() = map { it * it }.sum()

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
