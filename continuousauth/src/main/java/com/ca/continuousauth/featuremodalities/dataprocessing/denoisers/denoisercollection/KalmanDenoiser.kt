package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.denoisercollection

import com.ca.continuousauth.featuremodalities.dataprocessing.denoisers.SensorDenoiser

/**
 * A 1D Kalman Filter for sensor denoising. Applies a basic scalar Kalman filter independently to
 * each axis of the sensor data.
 */
class KalmanDenoiser(
        private val processNoiseQ: Float = 1e-4f,
        private val measurementNoiseR: Float = 1e-2f
) : SensorDenoiser {

    override fun denoiseWindow(
            window: List<Pair<Long, List<Float>>>
    ): List<Pair<Long, List<Float>>> {
        if (window.isEmpty()) return window

        val numSamples = window.size
        val numAxes = window[0].second.size

        // State arrays for each axis: [estimates, error covariances]
        val estimates = FloatArray(numAxes) { i -> window[0].second.getOrElse(i) { 0f } }
        val errorCovariances = FloatArray(numAxes) { 1.0f } // Initial uncertainty

        val denoisedWindow = ArrayList<Pair<Long, List<Float>>>(numSamples)

        for (i in 0 until numSamples) {
            val timestamp = window[i].first
            val measurement = window[i].second
            val denoisedValues = ArrayList<Float>(numAxes)

            for (axis in 0 until numAxes) {
                // Measurement z_t
                val z = measurement.getOrElse(axis) { 0f }

                // Prediction Phase
                val pPred = errorCovariances[axis] + processNoiseQ

                // Update Phase
                val kalmanGain = pPred / (pPred + measurementNoiseR)
                val estimateUpdate = estimates[axis] + kalmanGain * (z - estimates[axis])
                val errorCovUpdate = (1.0f - kalmanGain) * pPred

                estimates[axis] = estimateUpdate
                errorCovariances[axis] = errorCovUpdate

                denoisedValues.add(estimateUpdate)
            }
            denoisedWindow.add(Pair(timestamp, denoisedValues))
        }

        return denoisedWindow
    }
}
