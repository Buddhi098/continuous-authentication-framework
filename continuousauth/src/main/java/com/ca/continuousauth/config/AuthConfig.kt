package com.ca.continuousauth.config

@ConsistentCopyVisibility
data class AuthConfig
private constructor(

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
        val modelFileName: String,
        val trainingEpochs: Int,
        val trainingBatchSize: Int,
        val featureDimension: Int,

        // Train / validation split
        val trainValidationRatio: Double,

        // Enrollment data filtering (ratio of samples to discard)
        val enrollmentDataFilterRatio: Double,

        // Signals
        val sigTrain: String,
        val sigInfer: String,
        val sigInit: String,
        val sigSave: String,
        val sigRestore: String,

        // Tensor names
        val inputKey: String,
        val outputReconstruction: String,
        val outputLoss: String,
        val outputStatus: String,
        val outputReconstructionError: String,

        /* ------------------------------------------------------------------
         * 3. System Settings
         * ------------------------------------------------------------------ */
        val enableLogging: Boolean,
        val maxStoredAuthenticatedVectors: Int
) {

    init {
        require(sampleCollectionFrequencyHz > 0) { "sampleCollectionFrequencyHz must be > 0" }
        require(enrollmentSamples > 0) { "enrollmentSamples must be > 0" }
        require(windowSize > 0) { "windowSize must be > 0" }
        require(windowOverlapRatio in 0.0..0.9) { "windowOverlapRatio must be between 0.0 and 0.9" }
        require(trainingEpochs > 0) { "trainingEpochs must be > 0" }
        require(trainingBatchSize > 0) { "trainingBatchSize must be > 0" }
        require(featureDimension > 0) { "featureDimension must be > 0" }
        require(trainValidationRatio in 0.5..0.95) {
            "trainValidationRatio must be between 0.5 and 0.95"
        }
        require(enrollmentDataFilterRatio in 0.0..0.3) {
            "enrollmentDataFilterRatio must be between 0.0 and 0.3"
        }
        require(maxStoredAuthenticatedVectors > 0) { "maxStoredAuthenticatedVectors must be > 0" }
    }

    class Builder {

        /* -------------------- Data Collection -------------------- */
        private var sampleCollectionFrequencyHz: Int = 100
        private var enrollmentSamples: Int = 2000
        private var windowSize: Int = 100
        private var windowOverlapRatio: Double = 0.5
        private var shouldLogFeatureVector: Boolean = false
        private var maxStoredAuthenticatedVectors: Int = 200

        /* -------------------- Model Training --------------------- */
        private var modelFileName: String = "model.tflite"
        private var trainingEpochs: Int = 300
        private var trainingBatchSize: Int = 32
        private var featureDimension: Int = 34
        private var trainValidationRatio: Double = 0.8
        private var enrollmentDataFilterRatio: Double = 0.1

        /* ------------------------ Signals ------------------------ */
        private var sigTrain: String = "train"
        private var sigInfer: String = "infer"
        private var sigInit: String = "init_model"
        private var sigSave: String = "save"
        private var sigRestore: String = "restore"

        /* ---------------------- Tensor Names --------------------- */
        private var inputKey: String = "inputs"
        private var outputReconstruction: String = "reconstruction"
        private var outputLoss: String = "loss"
        private var outputStatus: String = "status"
        private var outputReconstructionError: String = "reconstruction_error"

        /* --------------------- System ---------------------------- */
        private var enableLogging: Boolean = true

        /* ------------------ Builder Setters ---------------------- */
        fun sampleCollectionFrequencyHz(value: Int) = apply { sampleCollectionFrequencyHz = value }

        fun enrollmentSamples(value: Int) = apply { enrollmentSamples = value }

        fun windowSize(value: Int) = apply { windowSize = value }

        fun windowOverlapRatio(value: Double) = apply { windowOverlapRatio = value }

        fun shouldLogFeatureVector(value: Boolean) = apply { shouldLogFeatureVector = value }

        fun modelFileName(value: String) = apply { modelFileName = value }

        fun trainingEpochs(value: Int) = apply { trainingEpochs = value }

        fun trainingBatchSize(value: Int) = apply { trainingBatchSize = value }

        fun featureDimension(value: Int) = apply { featureDimension = value }

        fun trainValidationRatio(value: Double) = apply { trainValidationRatio = value }

        fun enrollmentDataFilterRatio(value: Double) = apply { enrollmentDataFilterRatio = value }

        fun sigTrain(value: String) = apply { sigTrain = value }
        fun sigInfer(value: String) = apply { sigInfer = value }
        fun sigInit(value: String) = apply { sigInit = value }
        fun sigSave(value: String) = apply { sigSave = value }
        fun sigRestore(value: String) = apply { sigRestore = value }

        fun inputKey(value: String) = apply { inputKey = value }
        fun outputReconstruction(value: String) = apply { outputReconstruction = value }

        fun outputLoss(value: String) = apply { outputLoss = value }

        fun outputStatus(value: String) = apply { outputStatus = value }

        fun outputReconstructionError(value: String) = apply { outputReconstructionError = value }

        fun enableLogging(value: Boolean) = apply { enableLogging = value }

        fun maxStoredAuthenticatedVectors(value: Int) = apply {
            maxStoredAuthenticatedVectors = value
        }

        fun build(): AuthConfig =
                AuthConfig(
                        sampleCollectionFrequencyHz = sampleCollectionFrequencyHz,
                        enrollmentSamples = enrollmentSamples,
                        windowSize = windowSize,
                        windowOverlapRatio = windowOverlapRatio,
                        shouldLogFeatureVector = shouldLogFeatureVector,
                        modelFileName = modelFileName,
                        trainingEpochs = trainingEpochs,
                        trainingBatchSize = trainingBatchSize,
                        featureDimension = featureDimension,
                        trainValidationRatio = trainValidationRatio,
                        enrollmentDataFilterRatio = enrollmentDataFilterRatio,
                        sigTrain = sigTrain,
                        sigInfer = sigInfer,
                        sigInit = sigInit,
                        sigSave = sigSave,
                        sigRestore = sigRestore,
                        inputKey = inputKey,
                        outputReconstruction = outputReconstruction,
                        outputLoss = outputLoss,
                        outputStatus = outputStatus,
                        outputReconstructionError = outputReconstructionError,
                        enableLogging = enableLogging,
                        maxStoredAuthenticatedVectors = maxStoredAuthenticatedVectors
                )
    }

    companion object {
        fun default(): AuthConfig = Builder().build()
    }
}
