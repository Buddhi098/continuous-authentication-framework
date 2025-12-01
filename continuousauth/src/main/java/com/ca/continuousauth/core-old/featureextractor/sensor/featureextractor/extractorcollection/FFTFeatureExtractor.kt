package com.ca.continuousauth.`core-old`.featureextractor.sensor.featureextractor.extractorcollection

import com.ca.continuousauth.`core-old`.featureextractor.sensor.featureextractor.SensorFeatureExtractorInterface
import kotlin.math.*
// ----------------------------
// FFT Feature Extractor (Optional)
// Simple magnitude of FFT for each axis
// ----------------------------
class FFTFeatureExtractor : SensorFeatureExtractorInterface {
    override fun extract(samples: List<FloatArray>): FloatArray {
        if (samples.isEmpty()) return floatArrayOf()
        val dims = samples[0].size
        val n = samples.size

        val features = mutableListOf<Float>()

        // Compute FFT magnitude per axis (simple DFT, naive)
        for (axis in 0 until dims) {
            val real = FloatArray(n) { k -> samples[k][axis] }
            val imag = FloatArray(n) { 0f }

            val fftMag = FloatArray(n / 2)
            for (f in 0 until n / 2) {
                var re = 0.0
                var im = 0.0
                for (t in 0 until n) {
                    val angle = 2.0 * PI * f * t / n
                    re += real[t] * cos(angle) + imag[t] * sin(angle)
                    im += -real[t] * sin(angle) + imag[t] * cos(angle)
                }
                fftMag[f] = sqrt((re * re + im * im)).toFloat()
            }
            features.addAll(fftMag.take(5)) // take first 5 freq bins as features
        }
        return features.toFloatArray()
    }
}