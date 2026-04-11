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
    val sensorTrainingEpochs: Int,
    val fusionTrainingEpochs: Int,
    val trainingBatchSize: Int,
    val sensorFeatureDimension: Int,
    val fusionFeatureDimension: Int,
    val sensorScoreWeight: Float,
    val fusionScoreWeight: Float,

    val minSensorSamples: Int,
    val minFusionSamples: Int,

    // Input dimension types
    val sensorInputDim: String,
    val fusionInputDim: String,

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
     * 2. Window-Based Decision (NEW)
     * ------------------------------------------------------------------ */
    val windowSizeForDecision: Int,
    val windowConfidenceThreshold: Double,

    /* ------------------------------------------------------------------
     * 3. System Settings
     * ------------------------------------------------------------------ */
    val enableLogging: Boolean,
    val maxStoredAuthenticatedVectors: Int,
    val emaAlpha: Float
) {

    init {
        require(sampleCollectionFrequencyHz > 0)
        require(enrollmentSamples > 0)
        require(windowSize > 0)
        require(windowOverlapRatio in 0.0..0.9)
        require(sensorTrainingEpochs > 0)
        require(fusionTrainingEpochs > 0)
        require(trainingBatchSize > 0)
        require(sensorFeatureDimension > 0)
        require(fusionFeatureDimension > 0)
        require(sensorScoreWeight + fusionScoreWeight == 1.0f)
        require(sensorScoreWeight in 0.0f..1.0f)
        require(fusionScoreWeight in 0.0f..1.0f)
        require(maxStoredAuthenticatedVectors > 0)
        require(emaAlpha in 0.0f..1.0f)

        require(minSensorSamples > 0)
        require(minFusionSamples > 0)

        require(sensorInputDim == "1D" || sensorInputDim == "2D")
        require(fusionInputDim == "1D" || fusionInputDim == "2D")

        // ✅ NEW VALIDATIONS
        require(windowSizeForDecision > 0) {
            "windowSizeForDecision must be > 0"
        }
        require(windowConfidenceThreshold in 0.0..1.0) {
            "windowConfidenceThreshold must be between 0.0 and 1.0"
        }
    }

    class Builder {

        /* -------------------- Data Collection -------------------- */
        private var sampleCollectionFrequencyHz: Int = 100
        private var enrollmentSamples: Int = 2000
        private var windowSize: Int = 200
        private var windowOverlapRatio: Double = 0.5
        private var shouldLogFeatureVector: Boolean = true
        private var maxStoredAuthenticatedVectors: Int = 2000
        private var emaAlpha: Float = 1f
        private var minSensorSamples: Int = 20
        private var minFusionSamples: Int = 20

        /* -------------------- Model Training --------------------- */
        private var sensorFeatureDimension: Int = 8
        private var sensorInputDim: String = "2D"
        private var fusionFeatureDimension: Int = 29
        private var fusionInputDim: String = "1D"

        private var sensorModelFileName: String = "sensor_model.tflite"
        private var fusionModelFileName: String = "fusion_model.tflite"
        private var sensorTrainingEpochs: Int = 80
        private var fusionTrainingEpochs: Int = 120
        private var trainingBatchSize: Int = 32
        private var sensorScoreWeight: Float = 0.2f
        private var fusionScoreWeight: Float = 0.8f

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

        /* ------------------ Window Decision (NEW) ---------------- */
        private var windowSizeForDecision: Int = 8
        private var windowConfidenceThreshold: Double = 0.5

        /* --------------------- System ---------------------------- */
        private var enableLogging: Boolean = true

        /* ------------------ Builder Setters ---------------------- */
        fun sampleCollectionFrequencyHz(value: Int) = apply { sampleCollectionFrequencyHz = value }
        fun enrollmentSamples(value: Int) = apply { enrollmentSamples = value }
        fun windowSize(value: Int) = apply { windowSize = value }
        fun windowOverlapRatio(value: Double) = apply { windowOverlapRatio = value }
        fun shouldLogFeatureVector(value: Boolean) = apply { shouldLogFeatureVector = value }

        fun minSensorSamples(value: Int) = apply { minSensorSamples = value }
        fun minFusionSamples(value: Int) = apply { minFusionSamples = value }

        fun sensorModelFileName(value: String) = apply { sensorModelFileName = value }
        fun fusionModelFileName(value: String) = apply { fusionModelFileName = value }
        fun sensorTrainingEpochs(value: Int) = apply { sensorTrainingEpochs = value }
        fun fusionTrainingEpochs(value: Int) = apply { fusionTrainingEpochs = value }
        fun trainingBatchSize(value: Int) = apply { trainingBatchSize = value }

        fun sensorFeatureDimension(value: Int) = apply { sensorFeatureDimension = value }
        fun fusionFeatureDimension(value: Int) = apply { fusionFeatureDimension = value }

        fun sensorInputDim(value: String) = apply { sensorInputDim = value }
        fun fusionInputDim(value: String) = apply { fusionInputDim = value }

        fun sensorScoreWeight(value: Float) = apply { sensorScoreWeight = value }
        fun fusionScoreWeight(value: Float) = apply { fusionScoreWeight = value }

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

        // ✅ NEW SETTERS
        fun windowSizeForDecision(value: Int) = apply { windowSizeForDecision = value }
        fun windowConfidenceThreshold(value: Double) = apply { windowConfidenceThreshold = value }

        fun enableLogging(value: Boolean) = apply { enableLogging = value }
        fun maxStoredAuthenticatedVectors(value: Int) = apply { maxStoredAuthenticatedVectors = value }
        fun emaAlpha(value: Float) = apply { emaAlpha = value }

        fun build(): AuthConfig =
            AuthConfig(
                sampleCollectionFrequencyHz,
                enrollmentSamples,
                windowSize,
                windowOverlapRatio,
                shouldLogFeatureVector,
                sensorModelFileName,
                fusionModelFileName,
                sensorTrainingEpochs,
                fusionTrainingEpochs,
                trainingBatchSize,
                sensorFeatureDimension,
                fusionFeatureDimension,
                sensorScoreWeight,
                fusionScoreWeight,
                minSensorSamples,
                minFusionSamples,
                sensorInputDim,
                fusionInputDim,
                sigTrain,
                sigInfer,
                sigInit,
                sigSave,
                sigRestore,
                inputKey,
                outputReconstruction,
                outputLoss,
                outputStatus,
                outputReconstructionError,
                windowSizeForDecision,
                windowConfidenceThreshold,
                enableLogging,
                maxStoredAuthenticatedVectors,
                emaAlpha
            )
    }

    companion object {
        fun default(): AuthConfig = Builder().build()
    }
}