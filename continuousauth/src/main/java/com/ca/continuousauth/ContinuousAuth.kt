package com.ca.continuousauth

import android.content.Context
import com.ca.continuousauth.authengine.AuthenticationManager
import com.ca.continuousauth.authengine.EnrollmentManager
import com.ca.continuousauth.authengine.WeightedScoreFusionStrategy
import com.ca.continuousauth.authmodel.AuthModel
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.FeatureModel
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.StandardScaler
import com.ca.continuousauth.states.AuthVectorResult
import com.ca.continuousauth.states.CollectionState
import com.ca.continuousauth.states.EnrollmentResult
import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

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

    val fusionCheckpointFile = File(context.filesDir, "fusion_auth_model.chk")
    val fusionThresholdFile = File(context.filesDir, "fusion_auth_threshold.bin")
    val fusionMetadataFile = File(context.filesDir, "fusion_auth_metadata.json")
    val fusionStoredVectorsFile = File(context.filesDir, "fusion_stored_vectors.bin")

    // -----------------------------
    // Core class objects
    // -----------------------------
    private val stateFile = File(context.filesDir, "collect_state.json")

    private val featureModel by lazy { FeatureModel() }

    // --- Sensor Pipeline ---
    private val sensorScaler by lazy { StandardScaler(context) }
    private val sensorAuthModel by lazy {
        AuthModel(
                context = context,
                modelFileName = AuthConfigManager.config.sensorModelFileName,
                inputDim = AuthConfigManager.config.sensorFeatureDimension
        )
    }
    private val sensorAuthManager by lazy {
        AuthenticationManager(
                context = context,
                authModel = sensorAuthModel,
                checkpointFile = checkpointFile,
                thresholdFile = thresholdFile,
                storedVectorsFile = storedVectorsFile,
                maxStoredVectors = AuthConfigManager.config.maxStoredAuthenticatedVectors
        )
    }
    private val sensorEnrollmentManager by lazy {
        EnrollmentManager(
                authModel = sensorAuthModel,
                checkpointFile = checkpointFile,
                thresholdFile = thresholdFile,
                metadataFile = metadataFile
        )
    }

    // --- Fusion Pipeline ---
    private val fusionScaler by lazy { StandardScaler(context) }
    private val fusionAuthModel by lazy {
        AuthModel(
                context = context,
                modelFileName = AuthConfigManager.config.fusionModelFileName,
                inputDim = AuthConfigManager.config.fusionFeatureDimension
        )
    }
    private val fusionAuthManager by lazy {
        AuthenticationManager(
                context = context,
                authModel = fusionAuthModel,
                checkpointFile = fusionCheckpointFile,
                thresholdFile = fusionThresholdFile,
                storedVectorsFile = fusionStoredVectorsFile,
                maxStoredVectors = AuthConfigManager.config.maxStoredAuthenticatedVectors
        )
    }
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
    private val _isCheckpointExists = MutableStateFlow(checkpointFile.exists())
    val isCheckpointExists: StateFlow<Boolean> = _isCheckpointExists.asStateFlow()

    private val _isFusionModelReady = MutableStateFlow(false)
    val isFusionModelReady: StateFlow<Boolean> = _isFusionModelReady.asStateFlow()

    // Flag to prevent double-start of authentication
    private val isAuthenticating = AtomicBoolean(false)

    init {
        // 1. Parameter validation (fail fast)
        require(enrollmentSamples > 0) { "Enrollment samples must be greater than 0" }

        // 2. Ensure filesDir exists (defensive, non-blocking)
        if (!context.filesDir.exists() && !context.filesDir.mkdirs()) {
            Logger.e("Context filesDir does not exist and could not be created.")
        }

        // 3. Configure logger (preferably app-level, but kept here)
        Logger.setEnabled(enableLog)

        // 4. Initialize fusion readiness from disk
        _isFusionModelReady.value = fusionCheckpointFile.exists() && fusionThresholdFile.exists()

        // 5. Trigger async state restoration
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
                remainingSamples = (enrollmentSamples - sensorCollectedList.size).coerceAtLeast(0)

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
                            // 🔒 Filter illegal feature vectors
                            // -------------------------------
                            val isSensorValid =
                                    isValidFeatureVector(
                                            vector.sensorVector,
                                            AuthConfigManager.config.sensorFeatureDimension
                                    )
                            val isFusionValid =
                                    vector.fusionVector != null &&
                                            vector.touchTime != lastCollectedTouchTime &&
                                            isValidFeatureVector(
                                                    vector.fusionVector,
                                                    AuthConfigManager.config.fusionFeatureDimension
                                            )

                            if (!isSensorValid) {
                                Logger.d("Dropped invalid sensor feature vector")
                                // If sensor is invalid, we might skip both? Usually sensor is base.
                            }

                            if (AuthConfigManager.config.shouldLogFeatureVector) {
                                Logger.d("$vector") // log the DualFeatureVector
                            }

                            collectionLock.withLock {
                                val needSensor = sensorCollectedList.size < enrollmentSamples
                                val needFusion = fusionCollectedList.size < enrollmentSamples

                                // --- Add the sample only if below limit ---
                                if (needSensor && isSensorValid) {
                                    sensorCollectedList.add(vector.sensorVector)
                                }

                                if (needFusion && isFusionValid) {
                                    fusionCollectedList.add(vector.fusionVector!!)
                                    lastCollectedTouchTime = vector.touchTime
                                }

                                _collectedSamplesCount.value = sensorCollectedList.size
                                updateProgress()

                                if (sensorCollectedList.size >= enrollmentSamples &&
                                                fusionCollectedList.size >= enrollmentSamples
                                ) {
                                    Logger.d("Training sample collection completed.")
                                    _isCollecting.value = false
                                    remainingSamples = 0
                                    clearCollectionState()
                                    updateProgress()
                                    cancel() // Cancel this job
                                    return@withLock
                                }
                            }

                            if (shouldLogFeatureVector) {
                                Logger.d(
                                        "Collected sample. SensorDim: ${vector.sensorVector.size}, FusionDim: ${vector.fusionVector?.size}"
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

    private fun isValidFeatureVector(vector: List<Float>, expectedSize: Int? = null): Boolean {
        if (vector.isEmpty()) return false
        if (expectedSize != null && vector.size != expectedSize) return false

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

        runBlocking {
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
                // Snapshot list
                val sensorSnapshot = collectionLock.withLock { sensorCollectedList.toList() }
                val fusionSnapshot = collectionLock.withLock { fusionCollectedList.toList() }

                val sensorArray = JSONArray()
                sensorSnapshot.forEach { sample ->
                    val sampleArray = JSONArray()
                    sample.forEach { sampleArray.put(it) }
                    sensorArray.put(sampleArray)
                }

                val fusionArray = JSONArray()
                fusionSnapshot.forEach { sample ->
                    val sampleArray = JSONArray()
                    sample.forEach { sampleArray.put(it) }
                    fusionArray.put(sampleArray)
                }

                val stateJson = JSONObject()
                stateJson.put("sensorCollectedList", sensorArray)
                stateJson.put("fusionCollectedList", fusionArray)

                // File I/O
                withContext(Dispatchers.IO) { stateFile.writeText(stateJson.toString()) }
                Logger.d("Collection state saved.")
            } catch (e: Exception) {
                Logger.e("Failed to save collection state: ${e.message}")
            }
        }
    }

    private fun loadCollectionState(): CollectionState? {
        return try {
            if (!stateFile.exists()) return null
            val text = stateFile.readText()
            val json = JSONObject(text)
            val sensorArray =
                    json.optJSONArray("sensorCollectedList")
                            ?: json.optJSONArray("collectedList") ?: JSONArray()
            val sensorCollected = mutableListOf<List<Float>>()
            for (i in 0 until sensorArray.length()) {
                val sampleArray = sensorArray.getJSONArray(i)
                val sample =
                        MutableList(sampleArray.length()) { j ->
                            sampleArray.getDouble(j).toFloat()
                        }
                sensorCollected.add(sample)
            }

            val fusionArray = json.optJSONArray("fusionCollectedList") ?: JSONArray()
            val fusionCollected = mutableListOf<List<Float>>()
            for (i in 0 until fusionArray.length()) {
                val sampleArray = fusionArray.getJSONArray(i)
                val sample =
                        MutableList(sampleArray.length()) { j ->
                            sampleArray.getDouble(j).toFloat()
                        }
                fusionCollected.add(sample)
            }
            CollectionState(
                    sensorCollectedList = sensorCollected,
                    fusionCollectedList = fusionCollected
            )
        } catch (e: Exception) {
            Logger.e("Failed to load collection state: ${e.message}")
            // If failed, maybe corrupt? delete?
            // stateFile.delete()
            null
        }
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
                val sensorSnapshot = collectionLock.withLock { sensorCollectedList.toList() }
                val fusionSnapshot = collectionLock.withLock { fusionCollectedList.toList() }

                if (sensorSnapshot.size < 10) {
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
                val transformedSensorList =
                        withContext(Dispatchers.Default) {
                            featureModel.applyFitTransform(sensorScaler, sensorSnapshot)
                        }
                val sensorResult = sensorEnrollmentManager.enroll(transformedSensorList)

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
                val minFusionSamples = 20
                var fusionAvailable = false
                var fusionThreshold: Float? = null

                if (fusionSnapshot.size >= minFusionSamples) {
                    val transformedFusionList =
                            withContext(Dispatchers.Default) {
                                featureModel.applyFitTransform(fusionScaler, fusionSnapshot)
                            }
                    val fusionResult = fusionEnrollmentManager.enroll(transformedFusionList)
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
            sensorAuthManager.loadModel()
            sensorAuthManager.loadThresholdOnce()
            sensorScaler.load()

            val useFusion = _isFusionModelReady.value && fusionCheckpointFile.exists()
            if (useFusion) {
                fusionAuthManager.loadModel()
                fusionAuthManager.loadThresholdOnce()
                fusionScaler.load()
            }

            Logger.d(
                    "Authentication starting. Mode: ${if (useFusion) "Multi-modal (Sensor + Touch)" else "Sensor-only"}"
            )

            val scoreFusionStrategy =
                    WeightedScoreFusionStrategy(
                            sensorWeight = AuthConfigManager.config.sensorScoreWeight,
                            fusionWeight = AuthConfigManager.config.fusionScoreWeight
                    )

            // Get feature flow
            val featureFlow = featureModel.getDualFeatureFlowAtFrequency(context, touchEventFlow)

            // Launch a coroutine to collect the flow asynchronously
            authScope.launch {
                var lastAuthenticatedTouchTime = -1L
                try {
                    featureFlow.collect { vector ->
                        if (!isActive) return@collect

                        try {
                            if (!isValidFeatureVector(
                                            vector.sensorVector,
                                            AuthConfigManager.config.sensorFeatureDimension
                                    )
                            ) {
                                Logger.d("Dropped invalid sensor vector during auth")
                                return@collect
                            }

                            val startTime = System.nanoTime()

                            // --- Sensor Inference ---
                            val scaledSensor2D =
                                    featureModel.applyTransform(
                                            sensorScaler,
                                            listOf(vector.sensorVector)
                                    )
                            val scaledSensor = scaledSensor2D.firstOrNull() ?: vector.sensorVector
                            val sensorResult =
                                    sensorAuthManager.authenticateFeatureVector(scaledSensor)

                            val sensorScore = sensorResult.score
                            val sensorThreshold = sensorResult.threshold

                            if (sensorScore == null || sensorThreshold == null) {
                                Logger.e("Sensor inference returned null score or threshold.")
                                return@collect
                            }

                            val finalAuthResult: AuthVectorResult

                            if (useFusion) {
                                // --- Fusion Inference ---
                                var fusionResult: AuthVectorResult? = null
                                val isFusionValid =
                                        vector.fusionVector != null &&
                                                vector.touchTime != lastAuthenticatedTouchTime &&
                                                isValidFeatureVector(
                                                        vector.fusionVector,
                                                        AuthConfigManager.config
                                                                .fusionFeatureDimension
                                                )
                                if (isFusionValid) {
                                    lastAuthenticatedTouchTime = vector.touchTime
                                    val scaledFusion2D =
                                            featureModel.applyTransform(
                                                    fusionScaler,
                                                    listOf(vector.fusionVector!!)
                                            )
                                    val scaledFusion =
                                            scaledFusion2D.firstOrNull() ?: vector.fusionVector
                                    fusionResult =
                                            fusionAuthManager.authenticateFeatureVector(
                                                    scaledFusion
                                            )
                                }

                                if (isFusionValid && fusionResult != null) {
                                    // --- Fused Vector Path ---
                                    // Both sensor and fusion scores are available;
                                    // compare the weighted fused score against the
                                    // weighted fused threshold.
                                    val fusionScore = fusionResult.score
                                    val fusionThreshold = fusionResult.threshold

                                    val finalScore =
                                            scoreFusionStrategy.fuseScores(sensorScore, fusionScore)
                                    val finalThreshold =
                                            scoreFusionStrategy.fuseScores(
                                                    sensorThreshold,
                                                    fusionThreshold
                                            )

                                    val isAuthenticated = finalScore < finalThreshold

                                    Logger.d(
                                            "Using FUSED threshold ($finalThreshold) " +
                                                    "for fused vector. Score: $finalScore"
                                    )

                                    finalAuthResult =
                                            AuthVectorResult(
                                                    isAuthenticated = isAuthenticated,
                                                    score = finalScore,
                                                    threshold = finalThreshold,
                                                    authPercentage = sensorResult.authPercentage,
                                                    totalAuthentications =
                                                            sensorResult.totalAuthentications
                                            )
                                } else {
                                    // --- Sensor-Only Vector Path (fusion model is
                                    //     loaded but no valid fusion vector this frame) ---
                                    // Only sensor data is available; compare the
                                    // sensor score directly against the sensor-specific
                                    // threshold. Do NOT apply fusion weights.
                                    val isAuthenticated = sensorScore < sensorThreshold

                                    Logger.d(
                                            "Using SENSOR-ONLY threshold ($sensorThreshold) " +
                                                    "for sensor vector. Score: $sensorScore"
                                    )

                                    finalAuthResult =
                                            AuthVectorResult(
                                                    isAuthenticated = isAuthenticated,
                                                    score = sensorScore,
                                                    threshold = sensorThreshold,
                                                    authPercentage = sensorResult.authPercentage,
                                                    totalAuthentications =
                                                            sensorResult.totalAuthentications
                                            )
                                }
                            } else {
                                // --- Sensor-Only Mode (no fusion model available) ---
                                finalAuthResult = sensorResult
                            }

                            val durationMs = (System.nanoTime() - startTime) / 1_000_000.0

                            // Check for re-enrollment availability
                            checkReEnrollmentStatus()

                            Logger.d(
                                    "Auth execution time: ${"%.3f".format(durationMs)}ms. Authenticated: ${finalAuthResult.isAuthenticated}"
                            )

                            withContext(Dispatchers.Main) { onResult(finalAuthResult) }
                        } catch (e: Exception) {
                            Logger.e("Error isolating feature vector auth: ${e.message}")
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

    // --------------------------------------------------
    // Stop authentication
    // --------------------------------------------------
    fun stopAuthentication() {
        authScope.coroutineContext.cancelChildren()

        // Wait for children? No, fire and forget cancel.
        // But we need to reset flag. Use invokeOnCompletion on job?
        // Or closely manage job ref.
        // Simple: manual reset here assuming cancel is swift.
        isAuthenticating.set(false)

        sensorAuthManager.stopAuthentication()
        sensorAuthManager.resetAuthenticationCounters()
        fusionAuthManager.stopAuthentication()
        fusionAuthManager.resetAuthenticationCounters()
        Logger.d("Authentication stopped")
    }

    // --------------------------------------------------
    // Re-enrollment
    // --------------------------------------------------
    private val _isReEnrollmentAvailable = MutableStateFlow(false)
    val isReEnrollmentAvailable: StateFlow<Boolean> = _isReEnrollmentAvailable.asStateFlow()

    fun checkReEnrollmentStatus() {
        // Technically both could be ready, base it on Sensor
        _isReEnrollmentAvailable.value = sensorAuthManager.isReadyForReEnrollment()
    }

    val storedVectorCount: StateFlow<Int> = sensorAuthManager.storedVectorCount
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
                val sensorResult = sensorEnrollmentManager.enroll(sensorVectors)

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
                        val fusionResult = fusionEnrollmentManager.enroll(fusionVectors)

                        if (fusionResult.success) {
                            fusionAvailable = true
                            fusionThreshold = fusionResult.threshold
                            Logger.d(
                                    "Fusion re-enrollment successful. New threshold: ${fusionResult.threshold}"
                            )
                            fusionAuthManager.clearStoredVectors()
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
            sensorAuthManager.clearStoredVectors()
            fusionAuthManager.clearStoredVectors()

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
