package com.ca.authframework.viewmodels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.ca.continuousauth.ContinuousAuth
import com.ca.continuousauth.states.AuthVectorResult
import kotlinx.coroutines.flow.StateFlow

class AuthenticationViewModel(
    private val auth: ContinuousAuth
) : ViewModel() {

    // -----------------------------
    // Auth state
    // -----------------------------
    val isCheckpointExists: StateFlow<Boolean> = auth.isCheckpointExists

    // -----------------------------
    // UI State
    // -----------------------------
    var authenticationRunning by mutableStateOf(false)
        private set

    var lastAuthResult by mutableStateOf<AuthVectorResult?>(null)
        private set

    var errorMessage by mutableStateOf("")
        private set

    // -----------------------------
    // Evaluation State
    // -----------------------------
    var evaluationRunning by mutableStateOf(false)
        private set

    var processedSamples by mutableStateOf(0)
        private set

    var targetSamples by mutableStateOf(0)
        private set

    var acceptedCount by mutableStateOf(0)
        private set

    var authPercentage by mutableStateOf(0f)
        private set

    var averageScore by mutableStateOf(0f)
        private set

    var medianScore by mutableStateOf(0f)   // ✅ NEW
        private set

    private var totalScoreSum = 0f
    private val scoreBuffer = mutableListOf<Float>() // ✅ for median

    // -----------------------------
    // Start Authentication
    // -----------------------------
    fun startAuthentication() {
        errorMessage = ""
        lastAuthResult = null

        if (!isCheckpointExists.value) {
            errorMessage = "Authentication model not enrolled."
            return
        }

        authenticationRunning = true

        auth.startAuthentication { result ->
            lastAuthResult = result
        }
    }

    // -----------------------------
    // Stop Authentication
    // -----------------------------
    fun stopAuthentication() {
        authenticationRunning = false
        lastAuthResult = null
        auth.stopAuthentication()
    }

    // -----------------------------
    // Evaluation Function
    // -----------------------------
    fun startEvaluation(samples: Int) {
        if (!isCheckpointExists.value) {
            errorMessage = "Authentication model not enrolled."
            return
        }
        if (samples <= 0) return

        targetSamples = samples
        processedSamples = 0
        acceptedCount = 0
        authPercentage = 0f
        averageScore = 0f
        medianScore = 0f
        totalScoreSum = 0f
        scoreBuffer.clear()
        evaluationRunning = true

        auth.startAuthentication { result ->
            if (!evaluationRunning) return@startAuthentication

            processedSamples++

            if (result.isAuthenticated) {
                acceptedCount++
            }

            // Auth percentage
            authPercentage =
                (acceptedCount.toFloat() / processedSamples.toFloat()) * 100f

            // Score tracking
            result.score?.let { score ->
                totalScoreSum += score
                averageScore = totalScoreSum / processedSamples

                scoreBuffer.add(score)
                medianScore = scoreBuffer.median()
            }

            lastAuthResult = result

            if (processedSamples >= targetSamples) {
                stopEvaluation()
            }
        }
    }

    fun stopEvaluation() {
        evaluationRunning = false
        auth.stopAuthentication()
    }
}

/* -----------------------------
 * Helper: Median
 * ----------------------------- */
private fun List<Float>.median(): Float {
    if (isEmpty()) return 0f
    val sorted = sorted()
    val mid = size / 2
    return if (size % 2 == 0) {
        (sorted[mid - 1] + sorted[mid]) / 2f
    } else {
        sorted[mid]
    }
}
