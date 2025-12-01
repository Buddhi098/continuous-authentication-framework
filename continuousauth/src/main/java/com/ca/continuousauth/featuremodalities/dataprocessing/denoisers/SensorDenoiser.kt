package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers

/**
 * Base interface for all denoisers.
 */
interface SensorDenoiser {

    /**
     * Denoises a single window of sensor data.
     *
     * @param window List of Pair(timestamp, rawData)
     * @return Denoised window
     */
    fun denoiseWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>>
}
