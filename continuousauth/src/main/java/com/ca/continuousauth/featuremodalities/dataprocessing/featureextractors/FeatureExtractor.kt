package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors

interface FeatureExtractor {
    /**
     * Extracts features from a single window of sensor data.
     *
     * @param window List of Pair(timestamp, List<Float>)
     * @return List<Float> representing extracted features
     */
    fun extract(window: List<Pair<Long, List<Float>>>): List<Float>
}
