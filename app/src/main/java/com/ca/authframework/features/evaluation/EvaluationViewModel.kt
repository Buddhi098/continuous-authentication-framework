package com.ca.authframework.features.evaluation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.ca.authframework.features.authentication.AuthenticationViewModel

/**
 * EvaluationViewModel - Manages evaluation state and coordinates with AuthenticationViewModel This
 * ViewModel is responsible for running authentication evaluations over a set of test samples
 */
class EvaluationViewModel(
        private val context: android.content.Context,
        private val auth: com.ca.continuousauth.ContinuousAuth,
        private val authViewModel: AuthenticationViewModel
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

    // Local TDT State
    private val tdtWindowSize = 10
    private val authWindow = ArrayDeque<Boolean>(tdtWindowSize)

    var tdtAccuracy by mutableStateOf(0.0)
        private set

    var authenticatedWindows by mutableStateOf(0)
        private set

    var totalWindows by mutableStateOf(0)
        private set

    /** Start evaluation with specified number of samples */
    fun startEvaluation(samples: Int) {
        if (samples <= 0 || evaluationRunning) return

        if (!auth.isCheckpointExists.value) {
            // Cannot start without model
            return
        }

        resetStats()
        targetSamples = samples
        evaluationRunning = true

        // Start authentication process
        auth.startAuthentication { result ->
            if (!evaluationRunning) return@startAuthentication

            processResult(result)

            // 2. Push to Global Auth State (TDT, Top Bar)
            authViewModel.processAuthResult(result)

            // 3. Check for Completion
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

        // Reset TDT
        authWindow.clear()
        totalWindows = 0
        authenticatedWindows = 0
        tdtAccuracy = 0.0
    }

    private fun processResult(result: com.ca.continuousauth.states.AuthVectorResult) {
        lastAuthResult = result
        processedSamples++

        // 1. Update Local Metrics
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

        // 2. Update Local TDT
        updateTdt(result.isAuthenticated)
    }

    private fun updateTdt(isAuthenticated: Boolean) {
        authWindow.addLast(isAuthenticated)

        if (authWindow.size == tdtWindowSize) {
            val authenticatedCount = authWindow.count { it }
            val isAuthenticatedWindow = (authenticatedCount.toFloat() / tdtWindowSize) >= 0.5f

            totalWindows++

            if (isAuthenticatedWindow) {
                authenticatedWindows++
            }

            tdtAccuracy = authenticatedWindows.toDouble() / totalWindows.toDouble()

            // Non-Overlapping Window -> Clear
            authWindow.clear()
        }
    }

    /** Stop the current evaluation */
    fun stopEvaluation() {
        evaluationRunning = false
        auth.stopAuthentication()
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
