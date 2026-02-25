package com.ca.authframework.features.evaluation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.ca.authframework.features.authentication.AuthenticationViewModel
import com.ca.authframework.features.evalhistory.EvaluationRecord
import com.ca.authframework.features.evalhistory.EvaluationRepository
import com.ca.authframework.features.evalhistory.EvaluatorLabel
import com.ca.authframework.features.tdtlock.TdtComputer

/**
 * EvaluationViewModel - Manages evaluation state and coordinates with AuthenticationViewModel This
 * ViewModel is responsible for running authentication evaluations over a set of test samples.
 *
 * Uses a local TdtComputer instance for evaluation-scoped TDT metrics that reset between
 * evaluations.
 */
class EvaluationViewModel(
        private val context: android.content.Context,
        private val auth: com.ca.continuousauth.ContinuousAuth,
        private val authViewModel: AuthenticationViewModel,
        private val repository: EvaluationRepository
) : ViewModel() {

    // Evaluation state
    var evaluationRunning by mutableStateOf(false)
        private set

    var targetSamples by mutableStateOf(0)
        private set

    var processedSamples by mutableStateOf(0)
        private set

    var authPercentage by mutableStateOf(0.0)
        private set

    var averageScore by mutableStateOf(0.0)
        private set

    var medianScore by mutableStateOf(0.0)
        private set

    private var acceptedCount = 0
    private var totalScoreSum = 0.0
    private val scoreBuffer = mutableListOf<Float>()

    var lastAuthResult by mutableStateOf<com.ca.continuousauth.states.AuthVectorResult?>(null)
        private set

    // Evaluator info (set when starting evaluation)
    private var currentEvaluatorName: String = ""
    private var currentEvaluatorLabel: EvaluatorLabel = EvaluatorLabel.LEGITIMATE

    // Local TDT Computer for evaluation-scoped metrics
    private val localTdtComputer = TdtComputer()

    val tdtAccuracy: Float
        get() = localTdtComputer.tdtAccuracy.value.toDouble().run { this }.toFloat()
    val authenticatedWindows: Int
        get() = localTdtComputer.authenticatedWindows.value
    val totalWindows: Int
        get() = localTdtComputer.totalWindows.value

    /** Start evaluation with specified number of samples and evaluator info */
    fun startEvaluation(samples: Int, evaluatorName: String, evaluatorLabel: EvaluatorLabel) {
        if (samples <= 0 || evaluationRunning) return

        if (!auth.isCheckpointExists.value) {
            // Cannot start without model
            return
        }

        // Store evaluator info
        currentEvaluatorName = evaluatorName
        currentEvaluatorLabel = evaluatorLabel

        resetStats()
        targetSamples = samples
        evaluationRunning = true

        // Start authentication process
        auth.startAuthentication { result ->
            if (!evaluationRunning) return@startAuthentication

            processResult(result)

            // Push to Global Auth State (TDT, Top Bar)
            authViewModel.processAuthResult(result)

            // Check for Completion
            if (targetSamples > 0 && processedSamples >= targetSamples) {
                stopEvaluation()
            }
        }
    }

    private fun resetStats() {
        processedSamples = 0
        acceptedCount = 0
        totalScoreSum = 0.0
        scoreBuffer.clear()
        authPercentage = 0.0
        averageScore = 0.0
        medianScore = 0.0
        lastAuthResult = null

        // Reset local TDT
        localTdtComputer.reset()
    }

    private fun processResult(result: com.ca.continuousauth.states.AuthVectorResult) {
        lastAuthResult = result
        processedSamples++

        // Update Local Metrics
        if (result.isAuthenticated) {
            acceptedCount++
        }
        authPercentage = (acceptedCount.toDouble() / processedSamples) * 100.0

        result.score?.let { score ->
            totalScoreSum += score
            averageScore = totalScoreSum / processedSamples
            scoreBuffer.add(score)
            medianScore = calculateMedian(scoreBuffer)
        }

        // Update Local TDT
        localTdtComputer.addResult(result.isAuthenticated)
    }

    /** Stop the current evaluation and save record */
    fun stopEvaluation() {
        val wasRunning = evaluationRunning
        evaluationRunning = false
        auth.stopAuthentication()

        // Save evaluation record if we have processed samples
        if (wasRunning && processedSamples > 0 && currentEvaluatorName.isNotBlank()) {
            val record =
                    EvaluationRecord(
                            evaluatorName = currentEvaluatorName,
                            evaluatorLabel = currentEvaluatorLabel,
                            avgConfidence = authPercentage,
                            tdtAccuracy = tdtAccuracy,
                            samplesProcessed = processedSamples,
                            averageScore = averageScore,
                            medianScore = medianScore
                    )
            repository.saveRecord(record)
        }
    }

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
