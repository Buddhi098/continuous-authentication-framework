package com.ca.continuousauth.config

/**
 * Immutable configuration for the Continuous Authentication Framework.
 *
 * This configuration is static and must be defined once during initialization.
 */
@ConsistentCopyVisibility
data class AuthConfig private constructor(

    /* ------------------------------------------------------------------
     * 1. Data Collection
     * ------------------------------------------------------------------ */
    val sampleCollectionFrequencyHz: Int,
    val enrollmentSamples: Int,
    val windowSize: Int,
    val windowOverlapRatio: Double,
    val shouldLogFeatureVector: Boolean,

    /* ------------------------------------------------------------------
     * 2. Model Training
     * ------------------------------------------------------------------ */
    val trainingEpochs: Int,
    val trainingBatchSize: Int,
    val featureDimension: Int,

    /* ------------------------------------------------------------------
     * 3. System Settings
     * ------------------------------------------------------------------ */
    val enableLogging: Boolean
) {

    init {
        require(sampleCollectionFrequencyHz > 0) {
            "sampleCollectionFrequencyHz must be > 0"
        }
        require(enrollmentSamples > 0) {
            "enrollmentSamples must be > 0"
        }
        require(windowSize > 0) {
            "windowSize must be > 0"
        }
        require(windowOverlapRatio in 0.0..0.9) {
            "windowOverlapRatio must be between 0.0 and 0.9"
        }
        require(trainingEpochs > 0) {
            "trainingEpochs must be > 0"
        }
        require(trainingBatchSize > 0) {
            "trainingBatchSize must be > 0"
        }
        require(featureDimension > 0) {
            "featureDimension must be > 0"
        }
    }

    /**
     * Builder for creating a static [AuthConfig].
     * Intended to be used once during framework initialization.
     */
    class Builder {

        /* Data Collection */
        private var sampleCollectionFrequencyHz: Int = 50
        private var enrollmentSamples: Int = 2000
        private var windowSize: Int = 128
        private var windowOverlapRatio: Double = 0.5

        private var shouldLogFeatureVector: Boolean = false

        /* Model Training */
        private var trainingEpochs: Int = 30
        private var trainingBatchSize: Int = 32
        private var featureDimension: Int = 64

        /* System */
        private var enableLogging: Boolean = true

        fun sampleCollectionFrequencyHz(value: Int) = apply {
            sampleCollectionFrequencyHz = value
        }

        fun enrollmentSamples(value: Int) = apply {
            enrollmentSamples = value
        }

        fun windowSize(value: Int) = apply {
            windowSize = value
        }

        fun windowOverlapRatio(value: Double) = apply {
            windowOverlapRatio = value
        }

        fun shouldLogFeatureVector(value: Boolean) = apply {
            shouldLogFeatureVector = value
        }

        fun trainingEpochs(value: Int) = apply {
            trainingEpochs = value
        }

        fun trainingBatchSize(value: Int) = apply {
            trainingBatchSize = value
        }

        fun featureDimension(value: Int) = apply {
            featureDimension = value
        }

        fun enableLogging(value: Boolean) = apply {
            enableLogging = value
        }

        fun build(): AuthConfig =
            AuthConfig(
                sampleCollectionFrequencyHz = sampleCollectionFrequencyHz,
                enrollmentSamples = enrollmentSamples,
                windowSize = windowSize,
                windowOverlapRatio = windowOverlapRatio,
                trainingEpochs = trainingEpochs,
                trainingBatchSize = trainingBatchSize,
                featureDimension = featureDimension,
                enableLogging = enableLogging,
                shouldLogFeatureVector = shouldLogFeatureVector
            )
    }

    companion object {

        /**
         * Provides a safe default static configuration.
         */
        fun default(): AuthConfig = Builder().build()
    }
}
