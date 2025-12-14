package com.ca.authframework.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.ca.continuousauth.ContinuousAuth
import com.ca.continuousauth.states.EnrollmentResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class EnrollmentViewModel(
    private val context: Context,
    private val auth: ContinuousAuth
) : ViewModel() {

    // --- Exposed StateFlows ---
    val isCollecting: StateFlow<Boolean> = auth.isCollecting
    val progress: StateFlow<Float> = auth.progress
    val collectedSampleCount: StateFlow<Int> = auth.collectedSamplesCount
    val isPaused: StateFlow<Boolean> = auth.isPaused

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> get() = _statusMessage

    private val _threshold = MutableStateFlow<Float>(0f)
    val threshold: StateFlow<Float> get() = _threshold

    // --- Internal variables ---
    private var enrollmentTriggered = false
    private var progressJob: Job? = null

    // --- Initialization ---
    init {
        _threshold.value = auth.getThreshold() ?: 0f
        if(_threshold.value  > 0f){
            onEnrollmentCompleted()
        }
    }

    // --- Collection APIs ---
    fun startCollection(targetSamples: Int) {
        auth.startCollecting()
        resetEnrollmentTrigger()
        observeProgress()
    }

    fun pauseCollection() {
        if (progress.value >= 1f) return
        auth.pauseCollecting()
        progressJob?.cancel()
    }

    fun resumeCollection() {
        if (progress.value >= 1f) return
        auth.resumeCollecting()
        resetEnrollmentTrigger()
        observeProgress()
    }

    fun clearCollection() {
        auth.clearCollection()
        auth.clearEnrollmentFiles()
        _statusMessage.value = ""
        progressJob?.cancel()
        enrollmentTriggered = false
    }

    // --- Progress Observation & Enrollment Trigger ---
    private fun resetEnrollmentTrigger() {
        enrollmentTriggered = false
        progressJob?.cancel()
    }

    private fun observeProgress() {
        progressJob = viewModelScope.launch {
            progress.collectLatest { p ->
                if (!enrollmentTriggered && p >= 1f) {
                    enrollmentTriggered = true
                    enqueueEnrollmentWork()
                }
            }
        }
    }

    // --- Status updates ---
    private fun onEnrollmentStarted() {
        _statusMessage.value = "Training model..."
    }

    private fun onEnrollmentCompleted() {
        _statusMessage.value = "Model trained. Threshold: ${"%.2f".format(_threshold.value)}"
        auth.refreshCheckpointState()
    }

    private fun onEnrollmentFailed() {
        _statusMessage.value = "Model training failed. Please try again."
    }

    // --- WorkManager enrollment ---
    private fun enqueueEnrollmentWork() {
        val workManager = WorkManager.getInstance(context)

        val workRequest = OneTimeWorkRequestBuilder<EnrollmentWorker>()
            .setInputData(
                workDataOf(
                    "checkpointFile" to auth.checkpointFile.absolutePath,
                    "thresholdFile" to auth.thresholdFile.absolutePath
                )
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .build()

        onEnrollmentStarted()

        workManager.enqueueUniqueWork(
            "enrollment_work",
            ExistingWorkPolicy.KEEP,
            workRequest
        )

        workManager.getWorkInfoByIdLiveData(workRequest.id)
            .observeForever { workInfo ->
                workInfo?.let {
                    when (it.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val newThreshold = it.outputData.getFloat("threshold", 0f)
                            _threshold.value = newThreshold
                            onEnrollmentCompleted()
                        }
                        WorkInfo.State.FAILED -> onEnrollmentFailed()
                        else -> {}
                    }
                }
            }
    }

    // --- Worker class ---
    class EnrollmentWorker(
        context: Context,
        workerParams: WorkerParameters
    ) : CoroutineWorker(context, workerParams) {

        override suspend fun doWork(): Result {
            return try {
                val checkpointPath = inputData.getString("checkpointFile")
                    ?: return Result.failure()
                val thresholdPath = inputData.getString("thresholdFile")
                    ?: return Result.failure()

                val auth = ContinuousAuth(applicationContext)

                if (auth.collectedSamplesCount.value == 0) return Result.failure()

                val enrollmentResult = suspendEnrollment(auth)

                if (enrollmentResult.success) {
                    val output = workDataOf("threshold" to enrollmentResult.threshold)
                    Result.success(output)
                } else {
                    Result.failure()
                }

            } catch (e: Exception) {
                Log.e("EnrollmentWorker", "Enrollment failed: ${e.message}")
                Result.retry()
            }
        }

        private suspend fun suspendEnrollment(auth: ContinuousAuth): EnrollmentResult {
            val deferred = CompletableDeferred<EnrollmentResult>()
            auth.startEnrollment { result ->
                deferred.complete(result)
            }
            return deferred.await()
        }
    }
}
