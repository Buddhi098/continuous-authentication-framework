package com.ca.continuousauth

import android.content.Context
import com.ca.continuousauth.authengine.AuthenticationManager
import com.ca.continuousauth.authengine.EnrollmentManager
import com.ca.continuousauth.authengine.WeightedScoreFusionStrategy
import com.ca.continuousauth.authmodel.AuthModel
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.data.ReEnrollmentDataManager
import com.ca.continuousauth.featuremodalities.FeatureModel
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.MinMaxScaler
import com.ca.continuousauth.states.AuthVectorResult
import com.ca.continuousauth.states.CollectionState
import com.ca.continuousauth.states.EnrollmentResult
import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ContinuousAuth(
        private val context: Context,
        private var enrollmentSamples: Int,
        private val touchEventFlow: Flow<TouchEventData>? = null,
        private val shouldLogFeatureVector: Boolean =
                AuthConfigManager.config.shouldLogFeatureVector,
        private val enableLog: Boolean = AuthConfigManager.config.enableLogging
) : AutoCloseable {

    // -----------------------------
    // Coroutine scope
    // -----------------------------
    // Main scope for general management and enrollment
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    // Dedicated scope for authentication flow to allow independent cancellation
    private val authScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // -----------------------------
    // Stored files
    // -----------------------------
    val checkpointFile = File(context.filesDir, "sensor_auth_model.chk")
    val thresholdFile = File(context.filesDir, "sensor_auth_threshold.bin")
    val metadataFile = File(context.filesDir, "sensor_auth_metadata.json")
    val storedVectorsFile = File(context.filesDir, "sensor_stored_vectors.bin")
    private val reEnrollmentManager by lazy {
        ReEnrollmentDataManager(
            storedVectorsFile = storedVectorsFile,
            requiredVectors = AuthConfigManager.config.maxStoredAuthenticatedVectors
        )
    }

    val fusionCheckpointFile = File(context.filesDir, "fusion_auth_model.chk")
    val fusionThresholdFile = File(context.filesDir, "fusion_auth_threshold.bin")
    val fusionMetadataFile = File(context.filesDir, "fusion_auth_metadata.json")
    val fusionStoredVectorsFile = File(context.filesDir, "fusion_stored_vectors.bin")
    private val reEnrollmentManagerFusion by lazy {
        ReEnrollmentDataManager(
            storedVectorsFile = fusionStoredVectorsFile,
            requiredVectors = AuthConfigManager.config.maxStoredAuthenticatedVectors
        )
    }

    // -----------------------------
    // Core class objects
    // -----------------------------
    private val stateFile = File(context.filesDir, "collect_state.json")

    private val featureModel by lazy { FeatureModel() }


    // --- Sensor Pipeline ---
    private val sensorScaler by lazy { MinMaxScaler(context, "sensor_min_max_scaler_prefs") }
    private val sensorAuthModel by lazy {
        AuthModel(
                context = context,
                modelFileName = AuthConfigManager.config.sensorModelFileName,
                fallbackInputDim = AuthConfigManager.config.sensorFeatureDimension
        )
    }
    private val sensorAuthManager =
        AuthenticationManager(
                context = context,
                authModel = sensorAuthModel,
                checkpointFile = checkpointFile,
                thresholdFile = thresholdFile,
                reEnrollmentManager = reEnrollmentManager,
        )

    private val sensorEnrollmentManager by lazy {
        EnrollmentManager(
                authModel = sensorAuthModel,
                checkpointFile = checkpointFile,
                thresholdFile = thresholdFile,
                metadataFile = metadataFile
        )
    }

    // --- Fusion Pipeline ---
    private val fusionScaler by lazy { MinMaxScaler(context, "fusion_min_max_scaler_prefs") }
    private val fusionAuthModel by lazy {
        AuthModel(
                context = context,
                modelFileName = AuthConfigManager.config.fusionModelFileName,
                fallbackInputDim = AuthConfigManager.config.fusionFeatureDimension
        )
    }
    private val fusionAuthManager =
        AuthenticationManager(
                context = context,
                authModel = fusionAuthModel,
                checkpointFile = fusionCheckpointFile,
                thresholdFile = fusionThresholdFile,
                reEnrollmentManager = reEnrollmentManagerFusion,
        )
    private val fusionEnrollmentManager by lazy {
        EnrollmentManager(
                authModel = fusionAuthModel,
                checkpointFile = fusionCheckpointFile,
                thresholdFile = fusionThresholdFile,
                metadataFile = fusionMetadataFile
        )
    }

    // -----------------------------
    // Data collection & feature extraction component states
    // -----------------------------
    private val _isCollecting = MutableStateFlow(false)
    val isCollecting: StateFlow<Boolean> = _isCollecting.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _collectedSamplesCount = MutableStateFlow(0)
    val collectedSamplesCount: StateFlow<Int> = _collectedSamplesCount.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isCollectionSaved = MutableStateFlow(false)
    val isCollectionSaved: StateFlow<Boolean> = _isCollectionSaved.asStateFlow()

    private var collectJob: Job? = null
    private var remainingSamples: Int? = null

    // Protected by Mutex for thread safety
    private val sensorCollectedList = mutableListOf<List<Float>>()
    private val fusionCollectedList = mutableListOf<List<Float>>()
    private val collectionLock = Mutex()

    // -----------------------------
    // Auth model training and authetication states
    // -----------------------------
    private val _isCheckpointExists = MutableStateFlow(false)
    val isCheckpointExists: StateFlow<Boolean> = _isCheckpointExists.asStateFlow()

    private val _isFusionModelReady = MutableStateFlow(false)
    val isFusionModelReady: StateFlow<Boolean> = _isFusionModelReady.asStateFlow()

    // Flag to prevent double-start of authentication
    private val isAuthenticating = AtomicBoolean(false)

    init {
        // 1. Parameter validation (fail fast)
        require(enrollmentSamples > 0) { "Enrollment samples must be greater than 0" }

        // 2. Configure logger (preferably app-level, but kept here)
        Logger.setEnabled(enableLog)

        // 3. Initialize file structure and models readiness from disk (background)
        scope.launch(Dispatchers.IO) {
            if (!context.filesDir.exists() && !context.filesDir.mkdirs()) {
                Logger.e("Context filesDir does not exist and could not be created.")
            }
            _isCheckpointExists.value = checkpointFile.exists()
            _isFusionModelReady.value = fusionCheckpointFile.exists() && fusionThresholdFile.exists()
        }

        // 4. Trigger async state restoration
        restorePreviousState()
    }

    private fun restorePreviousState() {
        scope.launch {
            val state = loadCollectionState() ?: return@launch

            collectionLock.withLock {
                sensorCollectedList.clear()
                fusionCollectedList.clear()
                sensorCollectedList.addAll(state.sensorCollectedList.take(enrollmentSamples))
                fusionCollectedList.addAll(state.fusionCollectedList.take(enrollmentSamples))

                _collectedSamplesCount.value = sensorCollectedList.size
                remainingSamples =
                        (enrollmentSamples - _collectedSamplesCount.value).coerceAtLeast(0)

                _isPaused.value = remainingSamples!! > 0
            }

            updateProgress()

            Logger.d("Resumed from saved state: remainingSamples=$remainingSamples")
        }
    }

    // -----------------------------
    // Collect training samples
    // -----------------------------
    fun startCollecting() {
        if (_isCollecting.value || remainingSamples == 0) return
        Logger.d("Starting collection. Remaining samples : $remainingSamples")

        _isCollecting.value = true
        _isPaused.value = false
        updateProgress()

        val flow = featureModel.getDualFeatureFlowAtFrequency(context, touchEventFlow)

        collectJob =
                scope.launch {
                    var lastCollectedTouchTime = -1L
                    try {
                        flow.collect { vector ->
                            if (!isActive) return@collect

                            if (_isPaused.value) {
                                // Pause logic handled by UI mostly, but if we get here, save and
                                // skip
                                saveCollectionState()
                                return@collect
                            }

                            // -------------------------------
                            // 🔒 Type-safe feature vectors
                            // -------------------------------
                            val sensorVector =
                                    extractFeatureList(vector.sensorVector) ?: emptyList()
                            val fusionVector = extractFeatureList(vector.fusionVector)
                            val touchTime = vector.touchTime

                            // -------------------------------
                            // 🔒 Filter illegal feature vectors
                            // -------------------------------
                            val isSensorValid =
                                    isValidFeatureVector(
                                            sensorVector,
                                            AuthConfigManager.config.sensorFeatureDimension
                                    )
                            val isFusionValid =
                                    fusionVector != null &&
                                            touchTime != lastCollectedTouchTime &&
                                            isValidFeatureVector(
                                                    fusionVector,
                                                    AuthConfigManager.config.fusionFeatureDimension
                                            )

                            if (!isSensorValid) {
                                Logger.d("Dropped invalid sensor feature vector")
                                // If sensor is invalid, we might skip both? Usually sensor is base.
                            }

                            if (AuthConfigManager.config.shouldLogFeatureVector) {
                                Logger.d("Sensor Vector: $sensorVector") // log the DualFeatureVector
                                Logger.d("Fusion Vector: $fusionVector") // log the DualFeatureVector
                            }

                            collectionLock.withLock {
                                val needSensor = sensorCollectedList.size < enrollmentSamples
                                val needFusion = fusionCollectedList.size < enrollmentSamples

                                // --- Add the sample only if below limit ---
                                if (needSensor && isSensorValid) {
                                    sensorCollectedList.add(sensorVector)
                                }

                                if (needFusion && isFusionValid) {
                                    fusionCollectedList.add(fusionVector!!)
                                    lastCollectedTouchTime = touchTime
                                }

                                _collectedSamplesCount.value = sensorCollectedList.size
                                updateProgress()

                                val isSensorComplete = sensorCollectedList.size >= enrollmentSamples
                                val isFusionComplete =
                                        touchEventFlow == null ||
                                                fusionCollectedList.size >= enrollmentSamples

                                if (isSensorComplete) {
                                    Logger.d("Training sample collection completed.")
                                    _isCollecting.value = false
                                    remainingSamples = 0
                                    clearCollectionState()
                                    updateProgress()
                                    cancel() // Cancel this job
                                    return@withLock
                                }
                            }

                            if (AuthConfigManager.config.shouldLogFeatureVector) {
                                Logger.d(
                                        "Collected sample. SensorDim: ${sensorVector.size}, FusionDim: ${fusionVector?.size}"
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        Logger.d("Collection job cancelled")
                    } catch (e: Exception) {
                        Logger.e("Collection failed", e)
                        _isCollecting.value = false
                    }
                }
    }

    private fun extractFeatureList(data: Any?): List<Float>? {
        if (data == null) return null
        return when (data) {
            is List<*> -> {
                if (data.isEmpty()) return emptyList()
                when (data.first()) {
                    is Float -> data.filterIsInstance<Float>()
                    is List<*> ->
                            data.filterIsInstance<List<*>>().flatMap {
                                it.filterIsInstance<Float>()
                            }
                    else -> null
                }
            }
            is FloatArray -> data.toList()
            else -> null
        }
    }

    private fun isValidFeatureVector(vector: List<Float>, expectedSize: Int? = null): Boolean {
        if (vector.isEmpty()) return false
        if (expectedSize != null) {
            val windowSize = AuthConfigManager.config.windowSize
            val expected2DSize = expectedSize * windowSize
            if (vector.size != expectedSize && vector.size != expected2DSize) {
                return false
            }
        }

        return vector.all { v -> !v.isNaN() && !v.isInfinite() }
    }

    fun pauseCollecting() {
        if (!_isCollecting.value || _isPaused.value) return
        _isPaused.value = true
        collectJob?.cancel()
        _isCollecting.value = false
        saveCollectionState()
        Logger.d("Collection paused. Remaining samples: $remainingSamples")
    }

    fun resumeCollecting() {
        if (!_isCollecting.value && _isPaused.value) {
            _isPaused.value = false
            startCollecting()
            Logger.d("Collection resumed.")
        }
    }

    fun clearCollection() {
        collectJob?.cancel()
        collectJob = null

        _isCollecting.value = false
        _isPaused.value = false
        remainingSamples = null

        // Clear collected data without blocking the calling thread.
        // The job is already cancelled above, so no new items are being added.
        scope.launch {
            collectionLock.withLock {
                sensorCollectedList.clear()
                fusionCollectedList.clear()
            }
        }

        _collectedSamplesCount.value = 0
        _progress.value = 0f

        clearCollectionState()
        Logger.d("Collection stopped and cleared.")
    }

    private fun clearCollectionState() {
        if (stateFile.exists()) stateFile.delete()
    }

    private fun updateProgress() {
        // Safe read of list size? Ideally yes, but size is volatile-like enough for UI
        val size = _collectedSamplesCount.value
        _progress.value = if (enrollmentSamples == 0) 0f else size.toFloat() / enrollmentSamples
    }

    private fun saveCollectionState() {
        scope.launch {
            try {
                // Snapshot lists under lock
                val sensorSnapshot = collectionLock.withLock { sensorCollectedList.toList() }
                val fusionSnapshot = collectionLock.withLock { fusionCollectedList.toList() }

                // Binary format: much smaller and faster than JSON for large float arrays
                // Format: [sensorCount][sensorDim][sensor floats...][fusionCount][fusionDim][fusion floats...]
                withContext(Dispatchers.IO) {
                    DataOutputStream(BufferedOutputStream(FileOutputStream(stateFile))).use { dos ->
                        // Write sensor data
                        dos.writeInt(sensorSnapshot.size)
                        val sensorDim = sensorSnapshot.firstOrNull()?.size ?: 0
                        dos.writeInt(sensorDim)
                        for (sample in sensorSnapshot) {
                            for (value in sample) {
                                dos.writeFloat(value)
                            }
                        }

                        // Write fusion data
                        dos.writeInt(fusionSnapshot.size)
                        val fusionDim = fusionSnapshot.firstOrNull()?.size ?: 0
                        dos.writeInt(fusionDim)
                        for (sample in fusionSnapshot) {
                            for (value in sample) {
                                dos.writeFloat(value)
                            }
                        }
                    }
                }
                Logger.d("Collection state saved (binary). Sensor=${sensorSnapshot.size}, Fusion=${fusionSnapshot.size}")
            } catch (e: Exception) {
                Logger.e("Failed to save collection state: ${e.message}")
            }
        }
    }

    private fun loadCollectionState(): CollectionState? {
        return try {
            if (!stateFile.exists()) return null

            DataInputStream(BufferedInputStream(FileInputStream(stateFile))).use { dis ->
                // Read sensor data
                val sensorCount = dis.readInt()
                val sensorDim = dis.readInt()
                val sensorCollected = ArrayList<List<Float>>(sensorCount)
                repeat(sensorCount) {
                    val sample = FloatArray(sensorDim) { dis.readFloat() }
                    sensorCollected.add(sample.toList())
                }

                // Read fusion data
                val fusionCount = dis.readInt()
                val fusionDim = dis.readInt()
                val fusionCollected = ArrayList<List<Float>>(fusionCount)
                repeat(fusionCount) {
                    val sample = FloatArray(fusionDim) { dis.readFloat() }
                    fusionCollected.add(sample.toList())
                }

                CollectionState(
                    sensorCollectedList = sensorCollected,
                    fusionCollectedList = fusionCollected
                )
            }
        } catch (e: Exception) {
            Logger.e("Failed to load collection state: ${e.message}")
            stateFile.delete()
            null
        }
    }

    fun reshapeSnapshotChunks(
        sensorSnapshot: List<List<Float>>,
        numFeatures: Int,
        inputDim: String
    ): List<List<Float>> {

        // Return empty safely
        if (sensorSnapshot.isEmpty()) return sensorSnapshot

        // Validate inputDim
        require(inputDim == "1D" || inputDim == "2D") {
            "inputDim must be either '1D' or '2D'"
        }

        // ✅ 1D → flatten each sample (no chunking)
        if (inputDim == "1D") {
            return sensorSnapshot
        }

        // ✅ 2D → chunk into [timeSteps, features]
        val result = mutableListOf<List<Float>>()

        for (sample in sensorSnapshot) {
            var i = 0
            while (i + numFeatures <= sample.size) {
                val chunk = sample.subList(i, i + numFeatures)
                result.add(chunk)
                i += numFeatures
            }
            // NOTE: ignoring leftover values (incomplete chunk)
        }

        return result
    }

    fun mergeFeatureSetsFlattened(
        sensorSnapshot: List<List<Float>>,
        sequenceLength: Int,
        inputDim: String,
        mergeMode: String = "row"
    ): List<List<Float>> {

        val numFeatures = sensorSnapshot.firstOrNull()?.size ?: return sensorSnapshot

        // ✅ 1D → flatten each sample (no chunking)
        if (inputDim == "1D") {
            return sensorSnapshot
        }

        val result = mutableListOf<List<Float>>()
        var i = 0
        while (i < sensorSnapshot.size) {
            // Take next `sequenceLength` rows as a block
            val block = sensorSnapshot.subList(i, (i + sequenceLength).coerceAtMost(sensorSnapshot.size))
            val numFeatures = block.first().size
            val flatBlock = mutableListOf<Float>()

            if (mergeMode.lowercase() == "column") {
                // Column-wise merge
                val mergedBlock = MutableList(numFeatures) { mutableListOf<Float>() }
                for (row in block) {
                    for (f in 0 until numFeatures) {
                        mergedBlock[f].add(row[f])
                    }
                }
                for (col in mergedBlock) {
                    flatBlock.addAll(col)
                }
            } else if (mergeMode.lowercase() == "row") {
                // Row-wise merge (simply flatten rows)
                for (row in block) {
                    flatBlock.addAll(row)
                }
            } else {
                throw IllegalArgumentException("mergeMode must be either 'column' or 'row'")
            }

            result.add(flatBlock)
            i += sequenceLength
        }

        return result
    }

    // --------------------------------------------------
    // Public API: Start Enrollment
    // --------------------------------------------------
    fun startEnrollment(onComplete: (EnrollmentResult) -> Unit) {
        scope.launch {
            try {
                // Ensure collection is stopped
                pauseCollecting()

                // Get snapshot and validate
                var sensorSnapshot = collectionLock.withLock { sensorCollectedList.toList() }
                var fusionSnapshot = collectionLock.withLock { fusionCollectedList.toList() }

                if (sensorSnapshot.size < AuthConfigManager.config.minSensorSamples) {
                    withContext(Dispatchers.Main) {
                        onComplete(
                                EnrollmentResult(
                                        success = false,
                                        message =
                                                "Not enough sensor samples (${sensorSnapshot.size}). Need at least 10."
                                )
                        )
                    }
                    return@launch
                }

                // ---- Sensor Enrollment (always required) ----
                sensorSnapshot = reshapeSnapshotChunks(sensorSnapshot, AuthConfigManager.config.sensorFeatureDimension,
                    AuthConfigManager.config.sensorInputDim )
                var transformedSensorList =
                        withContext(Dispatchers.Default) {
                            featureModel.applyFitTransform(sensorScaler, sensorSnapshot)
                        }
                transformedSensorList = mergeFeatureSetsFlattened(transformedSensorList, AuthConfigManager.config.windowSize,
                    AuthConfigManager.config.sensorInputDim)
                val sensorResult = sensorEnrollmentManager.enroll(transformedSensorList ,
                    AuthConfigManager.config.sensorTrainingEpochs)

                if (!sensorResult.success) {
                    _isCheckpointExists.value = false
                    clearCollectionState()
                    withContext(Dispatchers.Main) {
                        onComplete(
                                EnrollmentResult(
                                        success = false,
                                        message =
                                                "Sensor enrollment failed: ${sensorResult.message}"
                                )
                        )
                    }
                    return@launch
                }

                // ---- Fusion Enrollment (optional, graceful fallback) ----
                val minFusionSamples = AuthConfigManager.config.minFusionSamples
                var fusionAvailable = false
                var fusionThreshold: Float? = null

                if (fusionSnapshot.size >= minFusionSamples) {
                    fusionSnapshot = reshapeSnapshotChunks(fusionSnapshot, AuthConfigManager.config.fusionFeatureDimension,
                        AuthConfigManager.config.fusionInputDim )
                    var transformedFusionList =
                            withContext(Dispatchers.Default) {
                                featureModel.applyFitTransform(fusionScaler, fusionSnapshot)
                            }
                    transformedFusionList = mergeFeatureSetsFlattened(transformedFusionList, AuthConfigManager.config.windowSize,
                        AuthConfigManager.config.fusionInputDim)
                    val fusionResult = fusionEnrollmentManager.enroll(transformedFusionList,
                        AuthConfigManager.config.fusionTrainingEpochs)

                    fusionAvailable = fusionResult.success
                    if (fusionAvailable) {
                        fusionThreshold = fusionResult.threshold
                    } else {
                        Logger.d(
                                "Fusion enrollment did not succeed: ${fusionResult.message}. Falling back to sensor-only."
                        )
                    }
                } else {
                    Logger.d(
                            "Insufficient fusion data (${fusionSnapshot.size}/$minFusionSamples). Sensor-only mode."
                    )
                }

                _isFusionModelReady.value = fusionAvailable
                _isCheckpointExists.value = checkpointFile.exists()
                clearCollectionState()

                val resultMessage =
                        if (fusionAvailable) "Multi-modal authentication active (Sensor + Touch)"
                        else "Sensor-only authentication active. Not enough touch data for fusion."

                withContext(Dispatchers.Main) {
                    onComplete(
                            EnrollmentResult(
                                    success = true,
                                    threshold = sensorResult.threshold,
                                    fusionThreshold = fusionThreshold,
                                    trainedSampleCount = sensorResult.trainedSampleCount,
                                    message = resultMessage,
                                    fusionAvailable = fusionAvailable
                            )
                    )
                }
            } catch (e: Exception) {
                Logger.e("Enrollment exception: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onComplete(
                            EnrollmentResult(
                                    success = false,
                                    message = "Enrollment failed: ${e.message}"
                            )
                    )
                }
            }
        }
    }

    // --------------------------------------------------
    // Public API: Start Authentication
    // --------------------------------------------------
    fun startAuthentication(onResult: (AuthVectorResult) -> Unit) {
        if (!isAuthenticating.compareAndSet(false, true)) {
            Logger.d("Authentication already running. Ignoring start request.")
            return
        }

        try {
            // --- Load Models ---
            sensorAuthManager.loadModel()
            sensorAuthManager.loadThresholdOnce()
            sensorScaler.load()

            val useFusion = _isFusionModelReady.value && fusionCheckpointFile.exists()
            Logger.d(" Huuuuuuuuu ${useFusion}")
            if (useFusion) {
                fusionAuthManager.loadModel()
                fusionAuthManager.loadThresholdOnce()
                fusionScaler.load()
            }

            Logger.d(
                "Authentication starting. Mode: ${if (useFusion) "Fusion-first (fallback: Sensor)" else "Sensor-only"}"
            )

            val featureFlow = featureModel.getDualFeatureFlowAtFrequency(context, touchEventFlow)

            authScope.launch {
                var lastAuthenticatedTouchTime = -1L

                // --- Counters for Weighted Confidence ---
                var sensorPass = 0
                var sensorTotal = 0
                var fusionPass = 0
                var fusionTotal = 0

                try {
                    featureFlow.collect { vector ->
                        if (!isActive) return@collect

                        try {
                            val startTime = System.nanoTime()

                            val sensorVector = extractFeatureList(vector.sensorVector) ?: emptyList()
                            val fusionVector = extractFeatureList(vector.fusionVector)
                            val touchTime = vector.touchTime

                            // --- Decide which vector to use ---
                            val authResult: AuthVectorResult = if (useFusion &&
                                fusionVector != null &&
                                touchTime != lastAuthenticatedTouchTime &&
                                isValidFeatureVector(fusionVector, AuthConfigManager.config.fusionFeatureDimension)
                            ) {
                                // --- FUSION PIPELINE ---
                                lastAuthenticatedTouchTime = touchTime

                                val fusionSnapshot = reshapeSnapshotChunks(
                                    listOf(fusionVector),
                                    AuthConfigManager.config.fusionFeatureDimension,
                                    AuthConfigManager.config.fusionInputDim
                                )

                                var transformedFusion = withContext(Dispatchers.Default) {
                                    featureModel.applyTransform(fusionScaler, fusionSnapshot)
                                }

                                transformedFusion = mergeFeatureSetsFlattened(
                                    transformedFusion,
                                    AuthConfigManager.config.windowSize,
                                    AuthConfigManager.config.fusionInputDim
                                )

                                val fusionResultMap = fusionAuthManager.authenticateFeatureVector(
                                    transformedFusion.first(),
                                    fusionVector
                                )

                                fusionTotal++
                                if (fusionResultMap["isAuthenticated"] as Boolean) fusionPass++

                                Logger.d("Using FUSION result")

                                AuthVectorResult(
                                    authType = "fusion",
                                    isAuthenticated = fusionResultMap["isAuthenticated"] as Boolean,
                                    authenticationScore = fusionResultMap["score"] as Float,
                                    weightedConfidence = calculateWeightedConfidence(
                                        sensorPass, sensorTotal, fusionPass, fusionTotal
                                    )
                                )
                            } else {
                                // --- SENSOR PIPELINE ---
                                if (!isValidFeatureVector(sensorVector, AuthConfigManager.config.sensorFeatureDimension)) {
                                    Logger.d("Dropped invalid sensor vector")
                                    return@collect
                                }

                                val sensorSnapshot = reshapeSnapshotChunks(
                                    listOf(sensorVector),
                                    AuthConfigManager.config.sensorFeatureDimension,
                                    AuthConfigManager.config.sensorInputDim
                                )

                                var transformedSensor = withContext(Dispatchers.Default) {
                                    featureModel.applyTransform(sensorScaler, sensorSnapshot)
                                }

                                transformedSensor = mergeFeatureSetsFlattened(
                                    transformedSensor,
                                    AuthConfigManager.config.windowSize,
                                    AuthConfigManager.config.sensorInputDim
                                )

                                val sensorResultMap = sensorAuthManager.authenticateFeatureVector(
                                    transformedSensor.first(),
                                    sensorVector
                                )

                                sensorTotal++
                                if (sensorResultMap["isAuthenticated"] as Boolean) sensorPass++

                                Logger.d("Using SENSOR result")

                                AuthVectorResult(
                                    authType = "sensor",
                                    isAuthenticated = sensorResultMap["isAuthenticated"] as Boolean,
                                    authenticationScore = sensorResultMap["score"] as Float,
                                    weightedConfidence = calculateWeightedConfidence(
                                        sensorPass, sensorTotal, fusionPass, fusionTotal
                                    )
                                )
                            }

                            checkReEnrollmentStatus()

                            val durationMs = (System.nanoTime() - startTime) / 1_000_000.0
                            Logger.d(
                                "Auth time: ${"%.3f".format(durationMs)}ms | " +
                                        "Auth type: ${authResult.authType} | " +
                                        "Confidence: ${"%.4f".format(authResult.weightedConfidence)}"
                            )

                            withContext(Dispatchers.Main) {
                                onResult(authResult)
                            }

                        } catch (e: Exception) {
                            Logger.e("Error in auth pipeline: ${e.message}")
                        }
                    }
                } catch (e: CancellationException) {
                    Logger.d("Authentication flow cancelled")
                } catch (e: Exception) {
                    Logger.e("Authentication flow crashed: ${e.message}", e)
                } finally {
                    isAuthenticating.set(false)
                }
            }
        } catch (e: Exception) {
            Logger.e("Failed to start authentication: ${e.message}", e)
            isAuthenticating.set(false)
        }
    }

    // --- Helper function to calculate weighted confidence ---
    private fun calculateWeightedConfidence(
        sensorPass: Int,
        sensorTotal: Int,
        fusionPass: Int,
        fusionTotal: Int
    ): Double {
        val sensorWeight = AuthConfigManager.config.sensorScoreWeight
        val fusionWeight = AuthConfigManager.config.fusionScoreWeight

        val sensorConfidence = if (sensorTotal > 0) sensorPass.toDouble() / sensorTotal else 0.0
        val fusionConfidence = if (fusionTotal > 0) fusionPass.toDouble() / fusionTotal else 0.0

        return (sensorWeight * sensorConfidence) + (fusionWeight * fusionConfidence)
    }

    // --------------------------------------------------
    // Stop authentication
    // --------------------------------------------------
    fun stopAuthentication() {
        authScope.coroutineContext.cancelChildren()

        isAuthenticating.set(false)

        sensorAuthManager.stopAuthentication()
        sensorAuthManager.resetAuthenticationCounters()
        fusionAuthManager.stopAuthentication()
        fusionAuthManager.resetAuthenticationCounters()

        // Flush any unsaved vectors to disk before stopping
        scope.launch {
            sensorAuthManager.flushToDisk()
            fusionAuthManager.flushToDisk()
        }
        Logger.d("Authentication stopped")
    }

    // --------------------------------------------------
    // Re-enrollment
    // --------------------------------------------------
    private val _isReEnrollmentAvailable = MutableStateFlow(false)
    val isReEnrollmentAvailable: StateFlow<Boolean> = _isReEnrollmentAvailable.asStateFlow()

    fun checkReEnrollmentStatus() {
        // Technically both could be ready, base it on Sensor
        _isReEnrollmentAvailable.value = reEnrollmentManager.isReadyForReEnrollment()
    }

    val storedVectorCount: StateFlow<Int> = reEnrollmentManager.vectorCount
    val maxStoredVectors: Int = AuthConfigManager.config.maxStoredAuthenticatedVectors

    fun reEnroll(onResult: (EnrollmentResult) -> Unit) {
        if (isAuthenticating.get()) {
            stopAuthentication()
        }

        scope.launch {
            try {
                // ---- Sensor Re-enrollment (always required) ----
                val sensorVectors = sensorAuthManager.getStoredVectors()
                if (sensorVectors.isEmpty()) {
                    Logger.d("Re-enrollment aborted: no sensor vectors available")
                    withContext(Dispatchers.Main) {
                        onResult(
                                EnrollmentResult(
                                        false,
                                        message = "No sensor vectors available for re-enrollment"
                                )
                        )
                    }
                    return@launch
                }

                Logger.d("Re-enrolling SENSOR model with ${sensorVectors.size} stored vectors")
                val reshapedSensors = reshapeSnapshotChunks(sensorVectors, AuthConfigManager.config.sensorFeatureDimension,
                    AuthConfigManager.config.sensorInputDim)
                var transformedSensorList = withContext(Dispatchers.Default) {
                    featureModel.applyFitTransform(sensorScaler, reshapedSensors)
                }
                transformedSensorList = mergeFeatureSetsFlattened(transformedSensorList, AuthConfigManager.config.windowSize ,
                    AuthConfigManager.config.sensorInputDim)
                val sensorResult = sensorEnrollmentManager.enroll(transformedSensorList,
                    AuthConfigManager.config.sensorTrainingEpochs)

                if (!sensorResult.success) {
                    Logger.e("Sensor re-enrollment failed: ${sensorResult.message}")
                    withContext(Dispatchers.Main) { onResult(sensorResult) }
                    return@launch
                }

                // Sensor re-enrollment succeeded — update state
                Logger.d(
                        "Sensor re-enrollment successful. New threshold: ${sensorResult.threshold}"
                )
                sensorAuthManager.clearStoredVectors()
                sensorAuthManager.invalidateCachedState()
                sensorAuthManager.loadModel()
                sensorAuthManager.loadThresholdOnce()

                // ---- Fusion Re-enrollment (conditional, only if fusion was active) ----
                var fusionAvailable = false
                var fusionThreshold: Float? = null
                val minFusionSamples = 20

                if (_isFusionModelReady.value) {
                    val fusionVectors = fusionAuthManager.getStoredVectors()

                    if (fusionVectors.size >= minFusionSamples) {
                        Logger.d(
                                "Re-enrolling FUSION model with ${fusionVectors.size} stored vectors"
                        )
                        val reshapedFusions = reshapeSnapshotChunks(fusionVectors, AuthConfigManager.config.fusionFeatureDimension,
                            AuthConfigManager.config.fusionInputDim)
                        var transformedFusionList = withContext(Dispatchers.Default) {
                            featureModel.applyFitTransform(fusionScaler, reshapedFusions)
                        }
                        transformedFusionList = mergeFeatureSetsFlattened(transformedFusionList, AuthConfigManager.config.windowSize,
                            AuthConfigManager.config.fusionInputDim)

                        val fusionResult = fusionEnrollmentManager.enroll(transformedFusionList,
                            AuthConfigManager.config.fusionTrainingEpochs)

                        if (fusionResult.success) {
                            fusionAvailable = true
                            fusionThreshold = fusionResult.threshold
                            Logger.d(
                                    "Fusion re-enrollment successful. New threshold: ${fusionResult.threshold}"
                            )
                            fusionAuthManager.clearStoredVectors()
                            fusionAuthManager.invalidateCachedState()
                            fusionAuthManager.loadModel()
                            fusionAuthManager.loadThresholdOnce()
                        } else {
                            Logger.d(
                                    "Fusion re-enrollment failed: ${fusionResult.message}. " +
                                            "Falling back to sensor-only."
                            )
                        }
                    } else {
                        Logger.d(
                                "Insufficient fusion vectors for re-enrollment " +
                                        "(${fusionVectors.size}/$minFusionSamples). " +
                                        "Falling back to sensor-only."
                        )
                    }
                } else {
                    Logger.d("Fusion model was not active — skipping fusion re-enrollment")
                }

                // ---- Update global state ----
                _isFusionModelReady.value = fusionAvailable
                checkReEnrollmentStatus()

                val resultMessage =
                        if (fusionAvailable) "Re-enrollment complete: Multi-modal (Sensor + Touch)"
                        else "Re-enrollment complete: Sensor-only"

                Logger.d(resultMessage)

                withContext(Dispatchers.Main) {
                    onResult(
                            EnrollmentResult(
                                    success = true,
                                    threshold = sensorResult.threshold,
                                    fusionThreshold = fusionThreshold,
                                    trainedSampleCount = sensorResult.trainedSampleCount,
                                    message = resultMessage,
                                    fusionAvailable = fusionAvailable
                            )
                    )
                }
            } catch (e: Exception) {
                Logger.e("Re-enrollment failed: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onResult(
                            EnrollmentResult(false, message = "Re-enrollment failed: ${e.message}")
                    )
                }
            }
        }
    }

    fun clearEnrollmentFiles(): Boolean {
        return try {
            checkpointFile.takeIf { it.exists() }?.delete()
            thresholdFile.takeIf { it.exists() }?.delete()
            metadataFile.takeIf { it.exists() }?.delete()

            fusionCheckpointFile.takeIf { it.exists() }?.delete()
            fusionThresholdFile.takeIf { it.exists() }?.delete()
            fusionMetadataFile.takeIf { it.exists() }?.delete()

            stopAuthentication()
            scope.launch {
                sensorAuthManager.clearStoredVectors()
                fusionAuthManager.clearStoredVectors()
            }

            _isCheckpointExists.value = checkpointFile.exists()
            _isFusionModelReady.value = false
            Logger.d("Enrollment files deleted successfully")
            true
        } catch (e: Exception) {
            Logger.e("Failed to delete enrollment files", e)
            false
        }
    }

    fun refreshCheckpointState() {
        _isCheckpointExists.value = checkpointFile.exists()
    }

    fun getThreshold(): Float? {
        // Return sensor threshold as primary indicator
        return sensorEnrollmentManager.loadThreshold()
    }

    fun getFusionThreshold(): Float? {
        return if (_isFusionModelReady.value) fusionEnrollmentManager.loadThreshold() else null
    }

    fun getTrainedSampleCount(): Int? {
        // Return sensor count as primary indicator
        return sensorEnrollmentManager.loadMetadata()
    }

    // --------------------------------------------------
    // AutoCloseable Implementation
    // --------------------------------------------------
    override fun close() {
        Logger.d("Closing ContinuousAuth resources...")

        // 1. Cancel all coroutines
        try {
            scope.cancel()
            authScope.cancel()
        } catch (e: Exception) {
            Logger.e("Error cancelling scopes: ${e.message}")
        }

        // 2. Stop AuthManager (reset state)
        try {
            sensorAuthManager.stopAuthentication(resetCounters = true)
            fusionAuthManager.stopAuthentication(resetCounters = true)
        } catch (e: Exception) {
            Logger.e("Error stopping auth manager: ${e.message}")
        }

        // 3. Close AuthModel (TFLite interpreter)
        try {
            sensorAuthModel.close()
            fusionAuthModel.close()
        } catch (e: Exception) {
            Logger.e("Error closing auth model: ${e.message}")
        }

        Logger.d("ContinuousAuth closed.")
    }
}
