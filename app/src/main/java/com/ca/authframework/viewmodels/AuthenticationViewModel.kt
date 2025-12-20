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
    // Auth state (directly exposed)
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
        evaluationRunning = true

        auth.startAuthentication { result ->
            if (!evaluationRunning) return@startAuthentication

            processedSamples++
            if (result.isAuthenticated) {
                acceptedCount++
            }

            authPercentage = (acceptedCount.toFloat() / processedSamples.toFloat()) * 100f
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
