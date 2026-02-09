package com.ca.continuousauth.featuremodalities.dataprocessing.scalers

/** Interface for all feature scalers. */
interface Scaler {

    /** Fit the scaler on the training data and return transformed features. */
    fun fitTransform(features: List<List<Float>>): List<List<Float>>

    /** Transform new data using the previously fitted scaler. */
    fun transform(features: List<List<Float>>): List<List<Float>>

    /**
     * Save the scaler parameters (like min/max) to persistent storage. Default implementation does
     * nothing.
     */
    fun save() {
        // optional, override in concrete scaler
    }

    /**
     * Load the scaler parameters (like min/max) from persistent storage. Default implementation
     * does nothing.
     */
    fun load() {
        // optional, override in concrete scaler
    }

    /**
     * Check if the scaler has been fitted with training data. Default implementation returns false.
     */
    fun isFitted(): Boolean = false
}
