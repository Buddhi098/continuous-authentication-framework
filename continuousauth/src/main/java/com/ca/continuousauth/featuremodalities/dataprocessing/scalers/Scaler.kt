package com.ca.continuousauth.featuremodalities.dataprocessing.scalers

/**
 * Interface for all feature scalers.
 */
interface Scaler {

    /**
     * Fit the scaler on the training data and return transformed features.
     */
    fun fitTransform(features: List<List<Float>>): List<List<Float>>

    /**
     * Transform new data using the previously fitted scaler.
     */
    fun transform(features: List<List<Float>>): List<List<Float>>
}
