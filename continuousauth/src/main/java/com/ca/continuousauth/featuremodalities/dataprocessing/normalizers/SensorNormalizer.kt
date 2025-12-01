package com.ca.continuousauth.featuremodalities.dataprocessing.normalizers

interface SensorNormalizer {
    /**
     * Normalizes a single window of sensor data.
     *
     * @param window List of Pair(timestamp, rawData)
     * @return Normalized window
     */
    fun normalizeWindow(window: List<Pair<Long, List<Float>>>): List<Pair<Long, List<Float>>>
}
