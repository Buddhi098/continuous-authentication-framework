package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * IMU Feature Extractor based on BehaveFormer:
 * - Raw values (x, y, z)
 * - First-order derivatives
 * - Second-order derivatives
 * - FFT magnitude (per axis)
 *
 * Output: 12 features per sensor
 */
class IMUFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        if (window.isEmpty()) return emptyList()

        val xs = window.map { it.second[0] }
        val ys = window.map { it.second[1] }
        val zs = window.map { it.second[2] }

        val dx = firstDerivative(xs)
        val dy = firstDerivative(ys)
        val dz = firstDerivative(zs)

        val ddx = firstDerivative(dx)
        val ddy = firstDerivative(dy)
        val ddz = firstDerivative(dz)

        val fftX = fftMagnitude(xs)
        val fftY = fftMagnitude(ys)
        val fftZ = fftMagnitude(zs)

        return listOf(
            xs.average().toFloat(),
            ys.average().toFloat(),
            zs.average().toFloat(),

            dx.average().toFloat(),
            dy.average().toFloat(),
            dz.average().toFloat(),

            ddx.average().toFloat(),
            ddy.average().toFloat(),
            ddz.average().toFloat(),

            fftX,
            fftY,
            fftZ
        )
    }

    /**
     * First-order derivative (finite difference)
     */
    private fun firstDerivative(signal: List<Float>): List<Float> {
        if (signal.size < 2) return List(signal.size) { 0f }
        return signal.zipWithNext { a, b -> b - a }
    }

    /**
     * Lightweight FFT magnitude (DFT)
     * Returns average magnitude (paper takes abs values)
     */
    private fun fftMagnitude(signal: List<Float>): Float {
        val n = signal.size
        if (n == 0) return 0f

        var real: Double
        var imag: Double
        var magnitudeSum = 0.0

        for (k in 0 until n) {
            real = 0.0
            imag = 0.0
            for (t in 0 until n) {
                val angle = 2 * PI * k * t / n
                real += signal[t] * cos(angle)
                imag -= signal[t] * sin(angle)
            }
            magnitudeSum += sqrt(real * real + imag * imag)
        }

        return (magnitudeSum / n).toFloat()
    }
}
