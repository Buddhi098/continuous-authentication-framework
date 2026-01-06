package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers

import com.ca.continuousauth.config.AuthConfigManager
import kotlin.math.*

/**
 * A Band-Pass filter designed to isolate Physiological Tremors (8Hz - 12Hz).
 * * This filter suppresses:
 * 1. Voluntary movements (0 - 5 Hz)
 * 2. High-frequency sensor noise (> 15 Hz)
 * * It preserves the specific micro-vibrations of the hand that act as a biometric signature.
 */
class TremorBandPassDenoiser(
    private val samplingRate: Int = AuthConfigManager.config.sampleCollectionFrequencyHz
) : SensorDenoiser {

    // Target Frequency: Center of 8-12Hz range
    private val centerFreq = 10.0
    // Bandwidth: 4Hz wide (8Hz to 12Hz)
    private val bandwidth = 12.0

    // Filter Coefficients
    private val a0: Double
    private val a1: Double
    private val a2: Double
    private val b0: Double
    private val b1: Double
    private val b2: Double

    init {
        // Calculate Biquad Coefficients (Butterworth Band-Pass)
        // Formulas based on Audio EQ Cookbook
        val omega = 2.0 * PI * centerFreq / samplingRate
        val sn = sin(omega)
        val cs = cos(omega)
        val alpha = sn * sinh(ln(2.0) / 2.0 * bandwidth * omega / sn)

        val a0Raw = 1.0 + alpha

        // Normalize coefficients by a0
        a0 = 1.0 // Normalized
        a1 = (-2.0 * cs) / a0Raw
        a2 = (1.0 - alpha) / a0Raw
        b0 = alpha / a0Raw
        b1 = 0.0
        b2 = -alpha / a0Raw
    }

    override fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>> {
        if (window.isEmpty()) return emptyList()

        val numAxes = window[0].second.size

        // maintain filter history state for each axis independently
        val axisStates = Array(numAxes) { FilterState() }

        // Process the window row by row (time step by time step)
        return window.map { (timestamp, values) ->

            // Apply filter to each axis (X, Y, Z)
            val filteredValues = values.mapIndexed { index, value ->
                if (index < numAxes) {
                    processSample(value.toDouble(), axisStates[index]).toFloat()
                } else {
                    value // Pass through if axis index exceeds state (edge case)
                }
            }

            Pair(timestamp, filteredValues)
        }
    }

    /**
     * Processes a single sample using the Direct Form I difference equation.
     * y[n] = b0*x[n] + b1*x[n-1] + b2*x[n-2] - a1*y[n-1] - a2*y[n-2]
     */
    private fun processSample(input: Double, state: FilterState): Double {
        val output = (b0 * input) + (b1 * state.x1) + (b2 * state.x2) -
                (a1 * state.y1) - (a2 * state.y2)

        // Shift history
        state.x2 = state.x1
        state.x1 = input
        state.y2 = state.y1
        state.y1 = output

        return output
    }

    // Helper class to hold history for IIR filter (prev inputs/outputs)
    private class FilterState {
        var x1 = 0.0 // x[n-1]
        var x2 = 0.0 // x[n-2]
        var y1 = 0.0 // y[n-1]
        var y2 = 0.0 // y[n-2]
    }

    // Math helpers
    private fun sinh(x: Double) = (exp(x) - exp(-x)) / 2.0
}