package com.ca.continuousauth

import android.content.Context
import com.ca.continuousauth.authmodel.AuthModel
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.featuremodalities.FeatureModel
import com.ca.continuousauth.states.CollectionState
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class ContinuousAuth(
    private val context: Context,
    private var enrollmentSamples: Int = AuthConfigManager.config.enrollmentSamples,
    private val shouldLogFeatureVector: Boolean = AuthConfigManager.config.shouldLogFeatureVector,
    private val enableLog: Boolean = AuthConfigManager.config.enableLogging
) {

    // -----------------------------
    // Coroutine scope
    // -----------------------------
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // -----------------------------
    // Collection state
    // -----------------------------
    private val stateFile = File(context.filesDir, "collect_state.json")

    // -----------------------------
    // Core class objects
    // -----------------------------
    private val featureModel = FeatureModel()
    private val authModel = AuthModel(context)

    // -----------------------------
    // Data collection & feature extraction states
    // -----------------------------
    private val _isCollecting = MutableStateFlow(false)
    val isCollecting: StateFlow<Boolean> = _isCollecting.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()
    private val _collectedSamplesCount = MutableStateFlow(0)
    val collectedSamplesCount: StateFlow<Int> = _collectedSamplesCount.asStateFlow()
    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()
    private var collectJob: Job? = null
    private var remainingSamples : Int? = null
    private val collectedList = mutableListOf<List<Float>>()

    // -----------------------------
    // Training state
    // -----------------------------
    private val _isTraining = MutableStateFlow(false)
    val isTraining: StateFlow<Boolean> = _isTraining.asStateFlow()

    private val _trainingProgress = MutableStateFlow(0f)
    val trainingProgress: StateFlow<Float> = _trainingProgress.asStateFlow()

    private val _trainingStatus = MutableStateFlow("")
    val trainingStatus: StateFlow<String> = _trainingStatus.asStateFlow()

    private var trainingJob: Job? = null

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
            if(state.collectedList.size > enrollmentSamples){
                clearCollection()
            }else{
                collectedList.addAll(state.collectedList)
                _collectedSamplesCount.value = collectedList.size
                remainingSamples = (enrollmentSamples - collectedList.size).coerceAtLeast(0)
                remainingSamples?.let {
                    if(it > 0){
                        _isPaused.value = true
                    }
                }
            }
            Logger.d("Resuming from saved state: remainingSamples=$remainingSamples, collected=${collectedList.size}")
        }
    }

    // -----------------------------
    // Collect training samples
    // -----------------------------
    fun startCollecting() {
        if (_isCollecting.value || remainingSamples==0) return
        Logger.d("remaining samples : $remainingSamples")
        _isCollecting.value = true
        _isPaused.value = false
        updateProgress()

        val flow = featureModel.getFeatureFlow(context)

        collectJob = scope.launch {
            try {
                flow.collect { vector ->
                    if (_isPaused.value) {
                        saveCollectionState()
                        return@collect
                    }

                    collectedList.add(vector)
                    _collectedSamplesCount.value = collectedList.size
                    updateProgress()

                    if (shouldLogFeatureVector){
                        Logger.d("$vector")
                        Logger.d("Collected sample ${collectedList.size}, dim:${vector.size}")
                    }

                    if (collectedList.size >= enrollmentSamples) {
                        Logger.d("Training sample collection completed.")
                        _isCollecting.value = false
                        remainingSamples = 0
                        clearCollectionState()
                        saveCollectionState()
                        collectJob?.cancel()
                    }
                }
            } catch (e: CancellationException) {
                Logger.d("Collection job cancelled")
            }  catch (e: Exception) {
                Logger.e("Collection failed", e)
            }
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

    fun getCollectedSampleCount(): Int {
        return collectedList.size
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


    // -----------------------------
    // Training API
    // -----------------------------
    fun startTrainingModel(onComplete: (() -> Unit)? = null) {
        if (_isTraining.value) return
        if (collectedList.isEmpty()) {
            _trainingStatus.value = "No samples to train"
            onComplete?.invoke()
            return
        }

        trainingJob = scope.launch {
            _isTraining.value = true
            _trainingProgress.value = 0f
            _trainingStatus.value = "Training started..."

            try {
                authModel.runTrainingSession(collectedList)
                _trainingStatus.value = "Training completed successfully"
                Logger.d("Training completed successfully")
            } catch (e: Exception) {
                _trainingStatus.value = "Training failed: ${e.message}"
                Logger.e("Training failed: ${e.message}")
            } finally {
                _isTraining.value = false
                onComplete?.invoke()
            }
        }
    }

    fun getAllCollectedSamples(): List<List<Float>> {
        return collectedList.toList()
    }


    fun stopTraining() {
        trainingJob?.cancel()
        trainingJob = null
        _isTraining.value = false
        _trainingProgress.value = 0f
        _trainingStatus.value = "Training cancelled"
        Logger.d("Training stopped.")
    }
}
