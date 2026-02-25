package com.ca.authframework.features.enrollment

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ca.authframework.service.EnrollmentForegroundService
import com.ca.authframework.service.EnrollmentResultBus
import com.ca.continuousauth.ContinuousAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class EnrollmentViewModel(private val context: Context, private val auth: ContinuousAuth) :
        ViewModel() {

    companion object {
        private const val TAG = "CAFramework"
    }

    // ---- Exposed state ----
    val isCollecting: StateFlow<Boolean> = auth.isCollecting
    val progress: StateFlow<Float> = auth.progress
    val collectedSampleCount: StateFlow<Int> = auth.collectedSamplesCount
    val isPaused: StateFlow<Boolean> = auth.isPaused

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _threshold = MutableStateFlow(0f)
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _fusionThreshold = MutableStateFlow(0f)
    val fusionThreshold: StateFlow<Float> = _fusionThreshold.asStateFlow()

    private val _trainedSampleCount = MutableStateFlow<Int?>(null)
    val trainedSampleCount: StateFlow<Int?> = _trainedSampleCount.asStateFlow()

    val isReEnrollmentAvailable: StateFlow<Boolean> = auth.isReEnrollmentAvailable
    val storedVectorCount: StateFlow<Int> = auth.storedVectorCount
    val maxStoredVectorCount: Int = auth.maxStoredVectors
    val isFusionModelReady: StateFlow<Boolean> = auth.isFusionModelReady

    private var enrollmentTriggered = false
    private var progressJob: Job? = null

    init {
        try {
            _threshold.value = auth.getThreshold() ?: 0f
            _fusionThreshold.value = auth.getFusionThreshold() ?: 0f
            Log.d(
                    TAG,
                    "Loaded thresholds: sensor=${_threshold.value}, fusion=${_fusionThreshold.value}"
            )

            if (_threshold.value > 0f) {
                // Load metadata if model exists
                val count = auth.getTrainedSampleCount()
                if (count != null) {
                    _trainedSampleCount.value = count
                }
                onEnrollmentCompleted()
            }

            observeEnrollmentResults()
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
            _statusMessage.value = "Initialization error"
        }
    }

    // ---- Collection control ----

    @RequiresApi(Build.VERSION_CODES.O)
    fun startCollection() {
        try {
            Log.d(TAG, "Starting collection")
            auth.startCollecting()
            resetTrigger()
            observeProgress()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start collection", e)
            _statusMessage.value = "Failed to start collection"
        }
    }

    fun pauseCollection() {
        try {
            Log.d(TAG, "Pausing collection")
            auth.pauseCollecting()
            progressJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause collection", e)
            _statusMessage.value = "Failed to pause collection"
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun resumeCollection() {
        try {
            Log.d(TAG, "Resuming collection")
            auth.resumeCollecting()
            resetTrigger()
            observeProgress()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume collection", e)
            _statusMessage.value = "Failed to resume collection"
        }
    }

    fun clearAll() {
        try {
            Log.d(TAG, "Clearing collection and enrollment files")
            auth.clearCollection()
            auth.clearEnrollmentFiles()
            _statusMessage.value = ""
            _statusMessage.value = ""
            _threshold.value = 0f
            _fusionThreshold.value = 0f
            _trainedSampleCount.value = null
            enrollmentTriggered = false
            progressJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all data", e)
            _statusMessage.value = "Failed to clear data"
        }
    }

    fun clearCollection() {
        try {
            Log.d(TAG, "Clearing collection")
            auth.clearCollection()
            _statusMessage.value = ""
            enrollmentTriggered = false
            progressJob?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear collection", e)
            _statusMessage.value = "Failed to clear collection"
        }
    }

    fun clearEnrollmentFiles() {
        try {
            Log.d(TAG, "Clearing enrollment files")
            Log.d(TAG, "Clearing enrollment files")
            auth.clearEnrollmentFiles()
            _threshold.value = 0f
            _fusionThreshold.value = 0f
            _trainedSampleCount.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear enrollment files", e)
            _statusMessage.value = "Failed to clear enrollment files"
        }
    }

    fun reEnroll() {
        viewModelScope.launch {
            _statusMessage.value = "Re-enrolling..."
            auth.reEnroll { result ->
                if (result.success) {
                    _statusMessage.value =
                            "Re-enrollment successful. New Threshold: ${"%.2f".format(result.threshold)}"
                    _threshold.value = result.threshold ?: 0f
                    _trainedSampleCount.value = result.trainedSampleCount
                } else {
                    _statusMessage.value = result.message ?: "Re-enrollment failed"
                }
            }
        }
    }

    // ---- Progress monitoring ----

    private fun resetTrigger() {
        enrollmentTriggered = false
        progressJob?.cancel()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun observeProgress() {
        progressJob =
                viewModelScope.launch {
                    try {
                        progress.collectLatest { p ->
                            if (!enrollmentTriggered && p >= 1f) {
                                Log.d(TAG, "Progress reached 100%, triggering enrollment")
                                enrollmentTriggered = true
                                startEnrollmentService()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Progress observation failed", e)
                    }
                }
    }

    // ---- Enrollment ----

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startEnrollmentService() {
        try {
            Log.d(TAG, "Starting enrollment foreground service")
            onEnrollmentStarted()

            EnrollmentForegroundService.start(
                    context = context,
                    checkpointPath = auth.checkpointFile.absolutePath,
                    thresholdPath = auth.thresholdFile.absolutePath
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start enrollment service", e)
            onEnrollmentFailed()
        }
    }

    private fun observeEnrollmentResults() {
        viewModelScope.launch {
            try {
                EnrollmentResultBus.results.collect { result ->
                    if (result.success) {
                        val newThreshold = result.threshold
                        if (newThreshold != null) {
                            Log.d(
                                    TAG,
                                    "Enrollment succeeded. Threshold=$newThreshold, fusionAvailable=${result.fusionAvailable}"
                            )
                            _threshold.value = newThreshold
                            _fusionThreshold.value = result.fusionThreshold ?: 0f
                            _trainedSampleCount.value = result.trainedSampleCount
                            onEnrollmentCompleted(result.message)
                        } else {
                            Log.e(TAG, "Enrollment success but threshold is null")
                            onEnrollmentFailed()
                        }
                    } else {
                        Log.e(TAG, "Enrollment failed: ${result}")
                        onEnrollmentFailed()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error collecting enrollment results", e)
                onEnrollmentFailed()
            }
        }
    }

    // ---- UI messages ----

    private fun onEnrollmentStarted() {
        _statusMessage.value = "Training model…"
    }

    private fun onEnrollmentCompleted(resultMessage: String? = null) {
        val mode =
                if (auth.isFusionModelReady.value)
                        "Multi-modal authentication active (Sensor + Touch)"
                else "Sensor-only authentication active"
        _statusMessage.value =
                resultMessage
                        ?: "Model trained. Threshold: ${"%.2f".format(_threshold.value)}. $mode"

        try {
            auth.refreshCheckpointState()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh checkpoint state", e)
        }
    }

    private fun onEnrollmentFailed() {
        _statusMessage.value = "Model training failed. Please try again."
    }

    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "ViewModel cleared")
        progressJob?.cancel()
    }
}
