package com.ca.continuousauth.featuremodalities.dataprocessing.postprocessers

interface PostProcessor {
    /**
     * Processes a single feature vector.
     *
     * @param features List<Float> input feature vector
     * @return List<Float> processed feature vector
     */
    fun process(features: List<Float>): List<Float>
}