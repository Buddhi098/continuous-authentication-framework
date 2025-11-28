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
    val windowSize: Int, // The main window size
    val windowOverlapRatio: Double,

    // 3. Feature Toggles
    val enableSensorFeature: Boolean,
    val enableKeystrokeFeature: Boolean,
    val enableTouchDynamicFeature: Boolean,

    // 4. ML Model & Remote Config
    val modelDownloadUrl: String?,
    val googleDriveFileId: String?,
    val localModelPath: File?, // Useful for bundling models within the app

    // 6. System
    val enableLogging: Boolean
) {

    /**
     * Builder for constructing an [AuthConfig] instance.
     */
    class Builder {
        // Defaults based on your requirements
        private var authenticationFrequencyMs: Long = 10_000L // Changed to 10s
        private var sampleCollectionFrequencyHz: Int = 64     // 50 Hz
        private var enrollmentSamples: Int = 2000

        // Renamed/Simplified from minWindowSize/windowSize in original
        private var windowSize: Int = 32
        private var windowOverlapRatio: Double = 0.2

        private var enableSensorFeature: Boolean = true
        private var enableKeystrokeFeature: Boolean = true
        private var enableTouchDynamicFeature: Boolean = true

        private var modelDownloadUrl: String? = null
        private var googleDriveFileId: String? = null
        private var localModelPath: File? = null

        private var enableLogging: Boolean = true

        // --- Setters ---

        fun setAuthenticationFrequency(milliseconds: Long) = apply { this.authenticationFrequencyMs = milliseconds }

        /**
         * Sets the sampling rate for sensors in Hertz (samples per second).
         */
        fun setSampleCollectionFrequency(hz: Int) = apply { this.sampleCollectionFrequencyHz = hz }

        fun setEnrollmentSample(samples: Int) = apply { this.enrollmentSamples = samples }

        /**
         * Sets the window size (number of samples) and the overlap ratio for data processing.
         */
        fun setWindowingConfig(size: Int, overlapRatio: Double) = apply {
            this.windowSize = size // Using the size parameter
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
        fun setLoggingEnabled(enabled: Boolean) = apply { this.enableLogging = enabled }

        /**
         * Validates and builds the config object.
         * @throws IllegalArgumentException if configuration values are invalid.
         */
        fun build(): AuthConfig {
            // Validation Logic
            require(windowOverlapRatio in 0.0..1.0) { "Overlap ratio must be between 0.0 and 1.0" }
            // Validation is now on the single windowSize property
            require(windowSize > 25) { "Window size must be greater than 25" }
            require(sampleCollectionFrequencyHz > 0) { "Frequency must be positive" }

            return AuthConfig(
                authenticationFrequencyMs,
                sampleCollectionFrequencyHz,
                enrollmentSamples,
                windowSize, // Passed the correct property
                windowOverlapRatio,
                enableSensorFeature,
                enableKeystrokeFeature,
                enableTouchDynamicFeature,
                modelDownloadUrl,
                googleDriveFileId,
                localModelPath,
                enableLogging
            )
        }
    }
}