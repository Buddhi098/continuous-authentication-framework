package com.ca.continuousauth

import android.content.Context
import android.view.View
import com.ca.continuousauth.authengine.AuthenticationManager
import com.ca.continuousauth.authengine.EnrollmentManager
import com.ca.continuousauth.authmodel.AuthModel
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.FeatureModel
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.MinMaxScaler
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.StandardScaler
import com.ca.continuousauth.states.AuthVectorResult
import com.ca.continuousauth.states.CollectionState
import com.ca.continuousauth.states.EnrollmentResult
import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ContinuousAuth(
    private val context: Context,
    private var enrollmentSamples: Int,
    private val touchEventFlow: Flow<TouchEventData>? = null,
    private val shouldLogFeatureVector: Boolean = AuthConfigManager.config.shouldLogFeatureVector,
    private val enableLog: Boolean = AuthConfigManager.config.enableLogging
) {

    // -----------------------------
    // Coroutine scope
    // -----------------------------
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val authScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // -----------------------------
    // Stored files
    // -----------------------------
    private val stateFile = File(context.filesDir, "collect_state.json")
    val checkpointFile = File(context.filesDir, "auth_model.chk")
    val thresholdFile = File(context.filesDir, "auth_threshold.bin")
    val storedVectorsFile = File(context.filesDir, "stored_vectors.bin")

    // -----------------------------
    // Core class objects
    // -----------------------------
    private val featureModel = FeatureModel()
    private val authModel = AuthModel(context)
    private val scaler = StandardScaler(context)
    private  val authManager = AuthenticationManager(
        context = context,
        authModel = authModel,
        checkpointFile = checkpointFile,
        thresholdFile = thresholdFile,
        storedVectorsFile = storedVectorsFile,
        maxStoredVectors = 10
    )
    private val enrollmentManager = EnrollmentManager(
        authModel = authModel,
        checkpointFile = checkpointFile,
        thresholdFile = thresholdFile
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
    private var remainingSamples : Int? = null
    private var collectedList = mutableListOf<List<Float>>()

    // -----------------------------
    // Auth model training and authetication states
    // -----------------------------
    private val _isCheckpointExists = MutableStateFlow(checkpointFile.exists())
    val isCheckpointExists: StateFlow<Boolean> = _isCheckpointExists.asStateFlow()


    init {
        // Parameter validation
        require(context.filesDir.exists() || context.filesDir.mkdirs()) {
            "Context filesDir does not exist and could not be created."
        }
        require(context.filesDir.canWrite()) {
            "Cannot write to context filesDir: ${context.filesDir.absolutePath}"
        }
        require(enrollmentSamples > 0) { "Enrollment samples must be greater than 0" }

        // Initialize Logger
        Logger.setEnabled(enableLog)

        // Load previous state if exists
        loadCollectionState()?.let { state ->
            collectedList.addAll(state.collectedList.take(enrollmentSamples))
            _collectedSamplesCount.value = collectedList.size
            remainingSamples = (enrollmentSamples - collectedList.size).coerceAtLeast(0)
            remainingSamples?.let {
                if(it > 0){
                    _isPaused.value = true
                }
            }
            updateProgress()
            Logger.d("Resuming from saved state: remainingSamples=$remainingSamples, collected=${collectedList.size}")
        }
    }

    // -----------------------------
    // Collect training samples
    // -----------------------------

    fun startCollecting() {
        if (_isCollecting.value || remainingSamples == 0) return
        Logger.d("remaining samples : $remainingSamples")
        _isCollecting.value = true
        _isPaused.value = false
        updateProgress()

        val flow = featureModel.getFeatureFlowAtFrequency(context, touchEventFlow)

        collectJob = scope.launch {
            try {
                flow.collect { vector ->
                    if (_isPaused.value) {
                        saveCollectionState()
                        return@collect
                    }
                    // -------------------------------
                    // 🔒 Filter illegal feature vectors
                    // -------------------------------
                    if (!isValidFeatureVector(vector)) {
                        Logger.d("Dropped invalid feature vector: $vector")
                        return@collect
                    }

                    // --- Check before adding to prevent extra sample ---
                    if (collectedList.size >= enrollmentSamples) {
                        Logger.d("Training sample collection completed. | Sample count : ${collectedList.size}}")
                        _isCollecting.value = false
                        remainingSamples = 0
                        clearCollectionState()
                        saveCollectionState()
                        updateProgress()
                        collectJob?.cancel()
                        return@collect
                    }else{
                        // --- Add the sample only if below limit ---
                        updateProgress()
                        collectedList.add(vector)
                        _collectedSamplesCount.value = collectedList.size
                    }

                    if (shouldLogFeatureVector) {
                        Logger.d("$vector")
                        Logger.d("Collected sample ${collectedList.size}, dim:${vector.size}")
                    }
                }
            } catch (e: CancellationException) {
                Logger.d("Collection job cancelled")
            } catch (e: Exception) {
                Logger.e("Collection failed", e)
            }
        }
    }

    private fun isValidFeatureVector(
        vector: List<Float>,
        expectedSize: Int? = null
    ): Boolean {
        if (vector.isEmpty()) return false
        if (expectedSize != null && vector.size != expectedSize) return false

        return vector.all { v ->
            !v.isNaN() && !v.isInfinite()
        }
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
            Logger.d("Collection resumed. Remaining samples: $remainingSamples")
        }
    }

    fun clearCollection() {
        collectJob?.cancel()
        collectJob = null
        _isCollecting.value = false
        _isPaused.value = false
        remainingSamples = null
        collectedList.clear()
        _collectedSamplesCount.value = 0
        _progress.value = 0f
        clearCollectionState()
        Logger.d("Collection stopped and cleared.")
    }

    private fun clearCollectionState() {
        if (stateFile.exists()) stateFile.delete()
    }

    private fun updateProgress() {
        _progress.value = if (enrollmentSamples == 0) 0f else collectedList.size.toFloat() / enrollmentSamples
    }

    private fun saveCollectionState() {
        try {
            val jsonArray = JSONArray()
            collectedList.forEach { sample ->
                val sampleArray = JSONArray()
                sample.forEach { sampleArray.put(it) }
                jsonArray.put(sampleArray)
            }
            val stateJson = JSONObject()
            stateJson.put("collectedList", jsonArray)
            stateFile.writeText(stateJson.toString())
            Logger.d("Collection state saved.")
        } catch (e: Exception) {
            Logger.e("Failed to save collection state: ${e.message}")
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
                val sample = MutableList(sampleArray.length()) { j -> sampleArray.getDouble(j).toFloat() }
                collected.add(sample)
            }
            CollectionState(collectedList = collected)
        } catch (e: Exception) {
            Logger.e("Failed to load collection state: ${e.message}")
            null
        }
    }

    // --------------------------------------------------
    // Public API: Start Enrollment
    // --------------------------------------------------
    fun startEnrollment(
        onComplete: (EnrollmentResult) -> Unit
    ) {
        scope.launch {
            try {
                collectedList = featureModel.applyFitTransform(scaler , collectedList) as MutableList<List<Float>>
                val result = enrollmentManager.enroll(collectedList)
                _isCheckpointExists.value = checkpointFile.exists()
                clearCollectionState()
                onComplete(result)
            } catch (e: Exception) {
                Logger.e("Enrollment exception: ${e.message}", e)
                onComplete(
                    EnrollmentResult(
                        success = false,
                        message = "Enrollment failed: ${e.message}"
                    )
                )
            }
        }
    }

    // --------------------------------------------------
    // Public API: Start Authentication
    // --------------------------------------------------
    fun startAuthentication(
        onResult: (AuthVectorResult) -> Unit
    ) {
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
                        try {

                            if (!isValidFeatureVector(vector)) {
                                Logger.d("Dropped invalid feature vector: $vector")
                                return@collect
                            }

                            // Convert 1D vector to 2D list with a single row
                            val vector2D: List<List<Float>> = listOf(vector)
                            val startTime1 = System.nanoTime()
                            val scaled2D: List<List<Float>> = featureModel.applyTransform(scaler, vector2D)
                            val endTime1 = System.nanoTime()
                            val elapsedMs = (endTime1 - startTime1) / 1_000_000.0
                            Logger.d("Scaling execution time: $elapsedMs ms")
                            val scaledVector = scaled2D.firstOrNull() ?: vector
                            Logger.d("Received Scaled feature vector from flow: $scaledVector")

                            val startTime = System.nanoTime()  // start timing
                            val result = authManager.authenticateFeatureVector(scaledVector)
                            val endTime = System.nanoTime()    // end timing
                            val durationMs = (endTime - startTime) / 1_000_000.0  // convert to milliseconds

                            Logger.d(
                                "Authentication executionTime=${"%.3f".format(durationMs)}ms"
                            )

                            onResult(result)

                        } catch (e: Exception) {
                            Logger.e("Error while authenticating feature vector: ${e.message}", e)
                        }
                    }
                } catch (e: CancellationException) {
                    Logger.d("Authentication flow cancelled")
                } catch (e: Exception) {
                    Logger.e("Authentication flow crashed: ${e.message}", e)
                }
            }

        } catch (e: Exception) {
            Logger.e("Failed to start authentication: ${e.message}", e)
        }
    }


    // --------------------------------------------------
    // Stop authentication
    // --------------------------------------------------
    fun stopAuthentication() {
        authScope.coroutineContext.cancelChildren()
        authManager.stopAuthentication()
        authManager.resetAuthenticationCounters()
        Logger.d("Authentication stopped")
    }

    fun clearEnrollmentFiles(): Boolean {
        return try {
            checkpointFile.takeIf { it.exists() }?.delete()
            thresholdFile.takeIf { it.exists() }?.delete()
            stopAuthentication()
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

}
