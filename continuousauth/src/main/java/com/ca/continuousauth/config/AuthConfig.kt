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
        val sensorModelFileName: String,
        val fusionModelFileName: String,
        val trainingEpochs: Int,
        val trainingBatchSize: Int,
        val sensorFeatureDimension: Int,
        val fusionFeatureDimension: Int,
        val sensorScoreWeight: Float,
        val fusionScoreWeight: Float,

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
        val maxStoredAuthenticatedVectors: Int,
        val emaAlpha: Float
) {

    init {
        require(sampleCollectionFrequencyHz > 0) { "sampleCollectionFrequencyHz must be > 0" }
        require(enrollmentSamples > 0) { "enrollmentSamples must be > 0" }
        require(windowSize > 0) { "windowSize must be > 0" }
        require(windowOverlapRatio in 0.0..0.9) { "windowOverlapRatio must be between 0.0 and 0.9" }
        require(trainingEpochs > 0) { "trainingEpochs must be > 0" }
        require(trainingBatchSize > 0) { "trainingBatchSize must be > 0" }
        require(sensorFeatureDimension > 0) { "sensorFeatureDimension must be > 0" }
        require(fusionFeatureDimension > 0) { "fusionFeatureDimension must be > 0" }
        require(sensorScoreWeight + fusionScoreWeight == 1.0f) { "Weights must sum to 1.0" }
        require(sensorScoreWeight in 0.0f..1.0f) { "sensorScoreWeight must be between 0.0 and 1.0" }
        require(fusionScoreWeight in 0.0f..1.0f) { "fusionScoreWeight must be between 0.0 and 1.0" }
        require(trainValidationRatio in 0.5..0.95) {
            "trainValidationRatio must be between 0.5 and 0.95"
        }
        require(enrollmentDataFilterRatio in 0.0..0.3) {
            "enrollmentDataFilterRatio must be between 0.0 and 0.3"
        }
        require(maxStoredAuthenticatedVectors > 0) { "maxStoredAuthenticatedVectors must be > 0" }
        require(emaAlpha in 0.0f..1.0f) { "emaAlpha must be between 0.0 and 1.0" }
    }

    class Builder {

        /* -------------------- Data Collection -------------------- */
        private var sampleCollectionFrequencyHz: Int = 100
        private var enrollmentSamples: Int = 2000
        private var windowSize: Int = 100
        private var windowOverlapRatio: Double = 0.5
        private var shouldLogFeatureVector: Boolean = false
        private var maxStoredAuthenticatedVectors: Int = 2000
        private var emaAlpha: Float = 1f

        /* -------------------- Model Training --------------------- */
        private var sensorModelFileName: String = "sensor_model.tflite"
        private var fusionModelFileName: String = "fusion_model.tflite"
        private var trainingEpochs: Int = 30
        private var trainingBatchSize: Int = 16
        private var sensorFeatureDimension: Int = 8 //  gyro(4) + totalAccel(4) + Magno(4)
        private var fusionFeatureDimension: Int = 22 // sensor(12) + touch(14)
        private var sensorScoreWeight: Float = 0.5f
        private var fusionScoreWeight: Float = 0.5f
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
        private var outputLoss: String = "loss_ae_total"
        private var outputStatus: String = "status"
        private var outputReconstructionError: String = "anomaly_score"

        /* --------------------- System ---------------------------- */
        private var enableLogging: Boolean = true

        /* ------------------ Builder Setters ---------------------- */
        fun sampleCollectionFrequencyHz(value: Int) = apply { sampleCollectionFrequencyHz = value }

        fun enrollmentSamples(value: Int) = apply { enrollmentSamples = value }

        fun windowSize(value: Int) = apply { windowSize = value }

        fun windowOverlapRatio(value: Double) = apply { windowOverlapRatio = value }
        fun shouldLogFeatureVector(value: Boolean) = apply { shouldLogFeatureVector = value }

        fun sensorModelFileName(value: String) = apply { sensorModelFileName = value }
        fun fusionModelFileName(value: String) = apply { fusionModelFileName = value }

        fun trainingEpochs(value: Int) = apply { trainingEpochs = value }

        fun trainingBatchSize(value: Int) = apply { trainingBatchSize = value }

        fun sensorFeatureDimension(value: Int) = apply { sensorFeatureDimension = value }
        fun fusionFeatureDimension(value: Int) = apply { fusionFeatureDimension = value }

        fun sensorScoreWeight(value: Float) = apply { sensorScoreWeight = value }
        fun fusionScoreWeight(value: Float) = apply { fusionScoreWeight = value }

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

        fun emaAlpha(value: Float) = apply { emaAlpha = value }

        fun build(): AuthConfig =
                AuthConfig(
                        sampleCollectionFrequencyHz = sampleCollectionFrequencyHz,
                        enrollmentSamples = enrollmentSamples,
                        windowSize = windowSize,
                        windowOverlapRatio = windowOverlapRatio,
                        shouldLogFeatureVector = shouldLogFeatureVector,
                        sensorModelFileName = sensorModelFileName,
                        fusionModelFileName = fusionModelFileName,
                        trainingEpochs = trainingEpochs,
                        trainingBatchSize = trainingBatchSize,
                        sensorFeatureDimension = sensorFeatureDimension,
                        fusionFeatureDimension = fusionFeatureDimension,
                        sensorScoreWeight = sensorScoreWeight,
                        fusionScoreWeight = fusionScoreWeight,
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
                        maxStoredAuthenticatedVectors = maxStoredAuthenticatedVectors,
                        emaAlpha = emaAlpha
                )
    }

    companion object {
        fun default(): AuthConfig = Builder().build()
    }
}
