package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors

/**
 * FeatureExtractor interface for sensor feature extraction.
 *
 * The extractor may return different feature structures depending
 * on the model requirements.
 *
 * Supported return types:
 *
 * 1D Features  -> List<Float>
 *      Example: statistical features (mean, std, min, max)
 *
 * 2D Features  -> List<List<Float>>
 *      Shape: (window, features)
 *      Example: raw sequence or temporal feature representation
 */
interface FeatureExtractor {

    /**
     * Extracts features from a single sensor window.
     *
     * @param window List of Pair(timestamp, sensorValues)
     *               sensorValues = List<Float> (x, y, z, ...)
     *
     * @return
     *      List<Float>           -> 1D feature vector
     *      List<List<Float>>     -> 2D feature matrix (window, features)
     */
    fun extract(window: List<Pair<Long, List<Float>>>): Any
}