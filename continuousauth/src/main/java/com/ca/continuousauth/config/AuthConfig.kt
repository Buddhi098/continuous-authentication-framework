package com.ca.continuousauth.config

import java.io.File

/**
 * Configuration class for the Continuous Authentication Framework.
 * Use [AuthConfig.Builder] to create an instance.
 */
@ConsistentCopyVisibility
data class AuthConfig private constructor(
    // 1. Timing & Frequency
    val authenticationFrequencyMs: Long,
    val sampleCollectionFrequencyHz: Int,
    val enrollmentSamples: Int,

    // 2. Data Processing & Windowing
    val windowSize: Int,
    val windowOverlapRatio: Double,

    // 3. Feature Toggles
    val enableSensorFeature: Boolean,
    val enableKeystrokeFeature: Boolean,
    val enableTouchDynamicFeature: Boolean,

    // 4. ML / DL Model, Training & Feature Dimensions
    val modelDownloadUrl: String?,
    val googleDriveFileId: String?,
    val localModelPath: File?,
    val trainingEpochs: Int,
    val trainingBatchSize: Int,
    val featureDimension: Int,

    // 5. System
    val enableLogging: Boolean
) {

    /**
     * Builder for constructing an [AuthConfig] instance.
     */
    class Builder {

        // --- Defaults ---
        private var authenticationFrequencyMs: Long = 10_000L
        private var sampleCollectionFrequencyHz: Int = 64
        private var enrollmentSamples: Int = 2000

        private var windowSize: Int = 32
        private var windowOverlapRatio: Double = 0.2

        private var enableSensorFeature: Boolean = true
        private var enableKeystrokeFeature: Boolean = true
        private var enableTouchDynamicFeature: Boolean = true

        private var modelDownloadUrl: String? = null
        private var googleDriveFileId: String? = null
        private var localModelPath: File? = null

        // ML / DL training defaults
        private var trainingEpochs: Int = 5
        private var trainingBatchSize: Int = 32

        // Feature dimension (number of features per sample)
        private var featureDimension: Int = 84

        private var enableLogging: Boolean = true

        // --- Setters ---
        fun setAuthenticationFrequency(milliseconds: Long) = apply {
            this.authenticationFrequencyMs = milliseconds
        }

        fun setSampleCollectionFrequency(hz: Int) = apply {
            this.sampleCollectionFrequencyHz = hz
        }

        fun setEnrollmentSample(samples: Int) = apply {
            this.enrollmentSamples = samples
        }

        fun setWindowingConfig(size: Int, overlapRatio: Double) = apply {
            this.windowSize = size
            this.windowOverlapRatio = overlapRatio
        }

        fun setFeatures(sensors: Boolean, keystroke: Boolean, touch: Boolean) = apply {
            this.enableSensorFeature = sensors
            this.enableKeystrokeFeature = keystroke
            this.enableTouchDynamicFeature = touch
        }

        fun setRemoteModelConfig(url: String?, driveId: String?) = apply {
            this.modelDownloadUrl = url
            this.googleDriveFileId = driveId
        }

        fun setLocalModel(file: File) = apply { this.localModelPath = file }

        fun setTrainingConfig(epochs: Int, batch: Int) = apply {
            this.trainingEpochs = epochs
            this.trainingBatchSize = batch
        }

        /**
         * Sets number of features per fused/sample vector.
         */
        fun setFeatureDimension(dim: Int) = apply {
            this.featureDimension = dim
        }

        fun setLoggingEnabled(enabled: Boolean) = apply {
            this.enableLogging = enabled
        }

        // --- Build ---
        fun build(): AuthConfig {
            require(windowOverlapRatio in 0.0..1.0) { "Overlap ratio must be between 0.0 and 1.0" }
            require(windowSize > 25) { "Window size must be greater than 25" }
            require(sampleCollectionFrequencyHz > 0) { "Frequency must be positive" }
            require(trainingEpochs > 0) { "Epochs must be > 0" }
            require(trainingBatchSize > 0) { "Batch size must be > 0" }
            require(featureDimension > 0) { "Feature dimension must be > 0" }

            return AuthConfig(
                authenticationFrequencyMs,
                sampleCollectionFrequencyHz,
                enrollmentSamples,
                windowSize,
                windowOverlapRatio,
                enableSensorFeature,
                enableKeystrokeFeature,
                enableTouchDynamicFeature,
                modelDownloadUrl,
                googleDriveFileId,
                localModelPath,
                trainingEpochs,
                trainingBatchSize,
                featureDimension,
                enableLogging
            )
        }
    }
}
