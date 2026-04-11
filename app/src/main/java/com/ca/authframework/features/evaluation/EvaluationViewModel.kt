package com.ca.authframework.features.evaluation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.ca.authframework.features.authentication.AuthenticationViewModel
import com.ca.authframework.features.evalhistory.EvaluationRecord
import com.ca.authframework.features.evalhistory.EvaluationRepository
import com.ca.authframework.features.evalhistory.EvaluatorLabel

class EvaluationViewModel(
    private val context: android.content.Context,
    private val auth: com.ca.continuousauth.ContinuousAuth,
    private val authViewModel: AuthenticationViewModel,
    private val repository: EvaluationRepository
) : ViewModel() {

    // ---------------- Evaluation State ----------------
    var evaluationRunning by mutableStateOf(false)
        private set

    var targetSamples by mutableStateOf(0)
        private set

    var processedSamples by mutableStateOf(0)
        private set

    var finalConfidence by mutableStateOf(0.0)
        private set

    var averageScore by mutableStateOf(0.0)
        private set

    var medianScore by mutableStateOf(0.0)
        private set

    // ✅ NEW: TRUE OVERALL WINDOW ACCURACY
    var overallWindowAccuracy by mutableStateOf(0.0)
        private set

    private var totalWindows = 0
    private var passedWindows = 0

    private var totalScoreSum = 0.0
    private val scoreBuffer = mutableListOf<Float>()

    var lastAuthResult by mutableStateOf<com.ca.continuousauth.states.AuthVectorResult?>(null)
        private set

    private var currentEvaluatorName: String = ""
    private var currentEvaluatorLabel: EvaluatorLabel = EvaluatorLabel.LEGITIMATE

    // ---------------- START ----------------
    fun startEvaluation(samples: Int, evaluatorName: String, evaluatorLabel: EvaluatorLabel) {
        if (samples <= 0 || evaluationRunning) return

        if (!auth.isCheckpointExists.value) return

        currentEvaluatorName = evaluatorName
        currentEvaluatorLabel = evaluatorLabel

        resetStats()
        targetSamples = samples
        evaluationRunning = true

        auth.startAuthentication { result ->
            if (!evaluationRunning) return@startAuthentication

            processResult(result)

            // push to global state
            authViewModel.processAuthResult(result)

            if (processedSamples >= targetSamples) {
                stopEvaluation()
            }
        }
    }

    // ---------------- RESET ----------------
    private fun resetStats() {
        processedSamples = 0
        totalScoreSum = 0.0
        scoreBuffer.clear()

        finalConfidence = 0.0
        averageScore = 0.0
        medianScore = 0.0

        // ✅ reset window metrics
        totalWindows = 0
        passedWindows = 0
        overallWindowAccuracy = 0.0

        lastAuthResult = null
    }

    // ---------------- PROCESS ----------------
    private fun processResult(result: com.ca.continuousauth.states.AuthVectorResult) {
        lastAuthResult = result
        processedSamples++

        // confidence
        finalConfidence = result.weightedConfidence * 100.0

        // -------- SCORE STATS --------
        result.authenticationScore.let { score ->
            totalScoreSum += score
            averageScore = totalScoreSum / processedSamples
            scoreBuffer.add(score)
            medianScore = calculateMedian(scoreBuffer)
        }

        overallWindowAccuracy = result.overallWindowAccuracy

    }

    // ---------------- STOP ----------------
    fun stopEvaluation() {
        val wasRunning = evaluationRunning
        evaluationRunning = false
        auth.stopAuthentication()

        if (wasRunning && processedSamples > 0 && currentEvaluatorName.isNotBlank()) {
            val record = EvaluationRecord(
                evaluatorName = currentEvaluatorName,
                evaluatorLabel = currentEvaluatorLabel,
                finalConfidence = finalConfidence,
                samplesProcessed = processedSamples,
                averageScore = averageScore,
                medianScore = medianScore,
                overallWindowAccuracy = overallWindowAccuracy
            )
            repository.saveRecord(record)
        }
    }

    // ---------------- UTILS ----------------
    private fun calculateMedian(list: List<Float>): Double {
        if (list.isEmpty()) return 0.0
        val sorted = list.sorted()
        val mid = list.size / 2

        return if (list.size % 2 == 0) {
            ((sorted[mid - 1] + sorted[mid]) / 2.0)
        } else {
            sorted[mid].toDouble()
        }
    }
}