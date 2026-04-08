package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser
import kotlin.math.PI
import kotlin.math.sqrt

class GravityHighPassDenoiser(
    val cutoff: Double = 0.3,
    val fs: Double = 100.0,
    private val numAxes: Int = 3
) : SensorDenoiser {

    private val b: DoubleArray
    private val a: DoubleArray

    // Maintain state (z-transform delays) between calls to prevent transients
    private val z1 = DoubleArray(numAxes) { 0.0 }
    private val z2 = DoubleArray(numAxes) { 0.0 }

    init {
        // Precise Butterworth coefficients via Bilinear Transform
        val tanW0 = kotlin.math.tan(PI * cutoff / fs)
        val k = tanW0
        val k2 = k * k
        val sqrt2 = sqrt(2.0)

        val norm = 1.0 / (1.0 + sqrt2 * k + k2)

        b = doubleArrayOf(
            norm,
            -2.0 * norm,
            norm
        )
        a = doubleArrayOf(
            1.0,
            2.0 * (k2 - 1.0) * norm,
            (1.0 - sqrt2 * k + k2) * norm
        )
    }

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        if (window.isEmpty()) return window

        return window.map { (timestamp, values) ->
            val denoisedValues = values.mapIndexed { axis, value ->
                val x = value.toDouble()

                // Direct Form II Transposed implementation
                val y = b[0] * x + z1[axis]
                z1[axis] = b[1] * x - a[1] * y + z2[axis]
                z2[axis] = b[2] * x - a[2] * y

                y.toFloat()
            }
            Pair(timestamp, denoisedValues)
        }
    }

    /**
     * Call this if the sensor stream is interrupted or a new user starts
     */
    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }
}