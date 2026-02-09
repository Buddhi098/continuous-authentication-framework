package com.ca.continuousauth

import android.content.Context
import com.ca.continuousauth.authengine.AuthenticationManager
import com.ca.continuousauth.authengine.EnrollmentManager
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
    private val stateFile = File(context.filesDir, "collect_state.json")
    val checkpointFile = File(context.filesDir, "auth_model.chk")
    val thresholdFile = File(context.filesDir, "auth_threshold.bin")
    val metadataFile = File(context.filesDir, "auth_metadata.json")
    val storedVectorsFile = File(context.filesDir, "stored_vectors.bin")

    // -----------------------------
    // Core class objects
    // -----------------------------
    private val featureModel = FeatureModel()
    // AuthModel is AutoCloseable now
    private val authModel = AuthModel(context)
    private val scaler = StandardScaler(context)

    private val authManager =
            AuthenticationManager(
                    context = context,
                    authModel = authModel,
                    checkpointFile = checkpointFile,
                    thresholdFile = thresholdFile,
                    storedVectorsFile = storedVectorsFile,
                    maxStoredVectors = AuthConfigManager.config.maxStoredAuthenticatedVectors
            )

    private val enrollmentManager =
            EnrollmentManager(
                    authModel = authModel,
                    checkpointFile = checkpointFile,
                    thresholdFile = thresholdFile,
                    metadataFile = metadataFile
            )

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
    private val collectedList = mutableListOf<List<Float>>()
    private val collectionLock = Mutex()

    // -----------------------------
    // Auth model training and authetication states
    // -----------------------------
    private val _isCheckpointExists = MutableStateFlow(checkpointFile.exists())
    val isCheckpointExists: StateFlow<Boolean> = _isCheckpointExists.asStateFlow()

    // Flag to prevent double-start of authentication
    private val isAuthenticating = AtomicBoolean(false)

    init {
        // Parameter validation
        if (!context.filesDir.exists() && !context.filesDir.mkdirs()) {
            Logger.e("Context filesDir does not exist and could not be created.")
        }

        require(enrollmentSamples > 0) { "Enrollment samples must be greater than 0" }

        // Initialize Logger
        Logger.setEnabled(enableLog)

        // Load previous state if exists
        loadCollectionState()?.let { state ->
            runBlocking {
                collectionLock.withLock {
                    collectedList.addAll(state.collectedList.take(enrollmentSamples))
                    _collectedSamplesCount.value = collectedList.size
                    remainingSamples = (enrollmentSamples - collectedList.size).coerceAtLeast(0)
                }
            }
            // If we have remaining samples, we are paused implicitly until resumed
            remainingSamples?.let {
                if (it > 0) {
                    _isPaused.value = true
                }
            }
            updateProgress()
            Logger.d(
                    "Resuming from saved state: remainingSamples=$remainingSamples, collected=${collectedList.size}"
            )
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

        val flow = featureModel.getFeatureFlowAtFrequency(context, touchEventFlow)

        collectJob =
                scope.launch {
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
                            if (!isValidFeatureVector(vector)) {
                                Logger.d("Dropped invalid feature vector")
                                return@collect
                            }

                            if(AuthConfigManager.config.shouldLogFeatureVector){
                                Logger.d("$vector")
                            }

                            collectionLock.withLock {
                                // --- Check before adding to prevent extra sample ---
                                if (collectedList.size >= enrollmentSamples) {
                                    Logger.d(
                                            "Training sample collection completed. Count: ${collectedList.size}"
                                    )
                                    _isCollecting.value = false
                                    remainingSamples = 0

                                    clearCollectionState()

                                    // We don't save the full collection state file anymore if we
                                    // are done,
                                    // or maybe we should until enrollment is triggered?
                                    // Logic says: clear state file because we are done collecting.
                                    // But what if app crashes before enrollment?
                                    // Ideally save strict state. For now, following original logic
                                    // of clearing.

                                    // saveCollectionState() // Original code saved here?
                                    // Actually if we clearCollectionState, we shouldn't save.

                                    updateProgress()
                                    cancel() // Cancel this job
                                    return@withLock
                                } else {
                                    // --- Add the sample only if below limit ---
                                    collectedList.add(vector)
                                    _collectedSamplesCount.value = collectedList.size
                                    updateProgress()
                                }
                            }

                            if (shouldLogFeatureVector) {
                                Logger.d("Collected sample. Dim:${vector.size}")
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

        runBlocking { collectionLock.withLock { collectedList.clear() } }

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
                val snapshot = collectionLock.withLock { collectedList.toList() }

                val jsonArray = JSONArray()
                snapshot.forEach { sample ->
                    val sampleArray = JSONArray()
                    sample.forEach { sampleArray.put(it) }
                    jsonArray.put(sampleArray)
                }
                val stateJson = JSONObject()
                stateJson.put("collectedList", jsonArray)

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
            val collected = mutableListOf<List<Float>>()
            val array = json.getJSONArray("collectedList")
            for (i in 0 until array.length()) {
                val sampleArray = array.getJSONArray(i)
                val sample =
                        MutableList(sampleArray.length()) { j ->
                            sampleArray.getDouble(j).toFloat()
                        }
                collected.add(sample)
            }
            CollectionState(collectedList = collected)
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
                val snapshot = collectionLock.withLock { collectedList.toList() }

                if (snapshot.size < 10) { // Minimum samples check
                    withContext(Dispatchers.Main) {
                        onComplete(
                                EnrollmentResult(
                                        success = false,
                                        message = "Not enough samples. Count: ${snapshot.size}"
                                )
                        )
                    }
                    return@launch
                }

                // Mutate list for transformation? FeatureModel.applyFitTransform returns NEW list
                // usually
                // But casting to MutableList<List<Float>> implies it might be same or new.
                // Safest to treat snapshot as input

                // NOTE: Enrollment might take time, run on Default/IO
                val transformedList =
                        withContext(Dispatchers.Default) {
                            featureModel.applyFitTransform(scaler, snapshot)
                        }

                val result = enrollmentManager.enroll(transformedList)

                _isCheckpointExists.value = checkpointFile.exists()
                clearCollectionState()

                withContext(Dispatchers.Main) { onComplete(result) }
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
            authManager.loadModel()
            authManager.loadThresholdOnce()
            scaler.load()

            // Get feature flow
            val featureFlow = featureModel.getFeatureFlowAtFrequency(context, touchEventFlow)

            // Launch a coroutine to collect the flow asynchronously
            authScope.launch {
                try {
                    featureFlow.collect { vector ->
                        if (!isActive) return@collect

                        try {
                            if (!isValidFeatureVector(vector)) {
                                Logger.d("Dropped invalid vector during auth")
                                return@collect
                            }

                            // Scaling
                            val vector2D: List<List<Float>> = listOf(vector)

                            // Timings can be noisy, maybe reduce log frequency?
                            val scaled2D: List<List<Float>> =
                                    featureModel.applyTransform(scaler, vector2D)
                            val scaledVector = scaled2D.firstOrNull() ?: vector

                            // Inference
                            val startTime = System.nanoTime()
                            val result = authManager.authenticateFeatureVector(scaledVector)
                            val durationMs = (System.nanoTime() - startTime) / 1_000_000.0

                            // Check for re-enrollment availability
                            checkReEnrollmentStatus()

                            Logger.d(
                                    "Auth execution time: ${"%.3f".format(durationMs)}ms. Authenticated: ${result.isAuthenticated}"
                            )

                            withContext(Dispatchers.Main) { onResult(result) }
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

        authManager.stopAuthentication()
        authManager.resetAuthenticationCounters()
        Logger.d("Authentication stopped")
    }

    // --------------------------------------------------
    // Re-enrollment
    // --------------------------------------------------
    private val _isReEnrollmentAvailable = MutableStateFlow(false)
    val isReEnrollmentAvailable: StateFlow<Boolean> = _isReEnrollmentAvailable.asStateFlow()

    fun checkReEnrollmentStatus() {
        _isReEnrollmentAvailable.value = authManager.isReadyForReEnrollment()
    }

    val storedVectorCount: StateFlow<Int> = authManager.storedVectorCount
    val maxStoredVectors: Int = AuthConfigManager.config.maxStoredAuthenticatedVectors

    fun reEnroll(onResult: (EnrollmentResult) -> Unit) {
        if (isAuthenticating.get()) {
            stopAuthentication()
        }

        scope.launch {
            try {
                val vectors = authManager.getStoredVectors()
                if (vectors.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        onResult(
                                EnrollmentResult(
                                        false,
                                        message = "No vectors available for re-enrollment"
                                )
                        )
                    }
                    return@launch
                }

                Logger.d("Starting re-enrollment with ${vectors.size} vectors")
                // NOTE: Vectors are already scaled from previous sessions.
                // We reuse them to train a new model.
                val result = enrollmentManager.enroll(vectors)

                if (result.success) {
                    Logger.d("Re-enrollment successful")

                    authManager.clearStoredVectors()
                    checkReEnrollmentStatus()

                    // Force refresh of model for next auth session
                    authManager.loadModel()
                    authManager.loadThresholdOnce()
                }

                withContext(Dispatchers.Main) { onResult(result) }
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
            stopAuthentication()
            authManager.clearStoredVectors()
            _isCheckpointExists.value = checkpointFile.exists()
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
        return enrollmentManager.loadThreshold()
    }

    fun getTrainedSampleCount(): Int? {
        return enrollmentManager.loadMetadata()
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
            authManager.stopAuthentication(resetCounters = true)
        } catch (e: Exception) {
            Logger.e("Error stopping auth manager: ${e.message}")
        }

        // 3. Close AuthModel (TFLite interpreter)
        try {
            authModel.close()
        } catch (e: Exception) {
            Logger.e("Error closing auth model: ${e.message}")
        }

        Logger.d("ContinuousAuth closed.")
    }
}
