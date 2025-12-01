package com.ca.continuousauth

import android.content.Context
import com.ca.continuousauth.authmodel.AuthModel
import com.ca.continuousauth.featuremodalities.FeatureModel
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CollectionState(
    val remainingSamples: Int,
    val collectedList: List<List<Float>>
)

class ContinuousAuth(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val stateFile = File(context.filesDir, "collect_state.json")

    // -----------------------------
    // Sample collection state
    // -----------------------------
    private val _isCollecting = MutableStateFlow(false)
    val isCollecting: StateFlow<Boolean> = _isCollecting.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _collectedSamples = MutableSharedFlow<List<Float>>(extraBufferCapacity = 200)
    val collectedSamples: SharedFlow<List<Float>> = _collectedSamples.asSharedFlow()

    private var collectJob: Job? = null
    private var paused = false
    private var remainingSamples = 0
    private val collectedList = mutableListOf<List<Float>>()

    private val featureModel = FeatureModel()
    private val authModel = AuthModel(context)

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
        // Load previous state if exists
        loadCollectionState()?.let { state ->
            remainingSamples = state.remainingSamples
            collectedList.addAll(state.collectedList)
            Logger.d("Resuming from saved state: remainingSamples=$remainingSamples, collected=${collectedList.size}")
        }
    }

    // -----------------------------
    // Collect training samples
    // -----------------------------
    fun startCollecting(sampleCount: Int) {
        if (_isCollecting.value) return

        _isCollecting.value = true
        paused = false
        remainingSamples = sampleCount.coerceAtLeast(collectedList.size)
        updateProgress()

        val flow = featureModel.getFeatureFlow(context)

        collectJob = scope.launch {
            try {
                flow.collect { vector ->
                    if (paused) {
                        saveCollectionState()
                        return@collect
                    }

                    _collectedSamples.emit(vector)
                    collectedList.add(vector)
                    remainingSamples = (sampleCount - collectedList.size).coerceAtLeast(0)
                    updateProgress()

                    Logger.d("Collected sample ${collectedList.size}, dim:${vector.size}")

                    if (collectedList.size >= sampleCount) {
                        Logger.d("Training sample collection completed.")
                        _isCollecting.value = false
                        clearCollectionState()
                        cancel()
                    }
                }
            } catch (e: CancellationException) {
                Logger.d("Collection job cancelled")
            }
        }
    }

    fun pauseCollecting() {
        if (!_isCollecting.value || paused) return
        paused = true
        collectJob?.cancel()
        _isCollecting.value = false
        saveCollectionState()
        Logger.d("Collection paused. Remaining samples: $remainingSamples")
    }

    fun resumeCollecting() {
        if (!_isCollecting.value && paused && remainingSamples > 0) {
            paused = false
            startCollecting(remainingSamples + collectedList.size)
            Logger.d("Collection resumed. Remaining samples: $remainingSamples")
        }
    }

    fun stopCollecting() {
        collectJob?.cancel()
        collectJob = null
        _isCollecting.value = false
        paused = false
        remainingSamples = 0
        collectedList.clear()
        _progress.value = 0f
        clearCollectionState()
        Logger.d("Collection stopped and cleared.")
    }

    private fun updateProgress() {
        _progress.value = if (remainingSamples + collectedList.size == 0) 0f
        else collectedList.size.toFloat() / (remainingSamples + collectedList.size)
    }

    fun getCollectedSampleCount(): Int {
        return collectedList.size
    }


    // -----------------------------
    // Save/load collection state
    // -----------------------------
    private fun saveCollectionState() {
        try {
            val jsonArray = JSONArray()
            collectedList.forEach { sample ->
                val sampleArray = JSONArray()
                sample.forEach { sampleArray.put(it) }
                jsonArray.put(sampleArray)
            }
            val stateJson = JSONObject()
            stateJson.put("remainingSamples", remainingSamples)
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
            val remaining = json.getInt("remainingSamples")
            val collected = mutableListOf<List<Float>>()
            val array = json.getJSONArray("collectedList")
            for (i in 0 until array.length()) {
                val sampleArray = array.getJSONArray(i)
                val sample = MutableList(sampleArray.length()) { j -> sampleArray.getDouble(j).toFloat() }
                collected.add(sample)
            }
            CollectionState(remaining, collected)
        } catch (e: Exception) {
            Logger.e("Failed to load collection state: ${e.message}")
            null
        }
    }

    private fun clearCollectionState() {
        if (stateFile.exists()) stateFile.delete()
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
