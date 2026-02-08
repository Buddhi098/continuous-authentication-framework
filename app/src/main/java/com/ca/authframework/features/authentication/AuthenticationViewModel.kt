package com.ca.authframework.features.authentication

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.ca.continuousauth.ContinuousAuth
import com.ca.continuousauth.states.AuthVectorResult
import kotlinx.coroutines.flow.StateFlow

class AuthenticationViewModel(private val auth: ContinuousAuth) : ViewModel() {

    /* ----------------------------- */
    /* Auth state                    */
    /* ----------------------------- */
    val isCheckpointExists: StateFlow<Boolean> = auth.isCheckpointExists

    /* ----------------------------- */
    /* UI State                      */
    /* ----------------------------- */
    var authenticationRunning by mutableStateOf(false)
        private set

    var lastAuthResult by mutableStateOf<AuthVectorResult?>(null)
        private set

    var errorMessage by mutableStateOf("")
        private set

    /* ================================================= */
    /* ✅ TDT NON-OVERLAPPING WINDOW (GLOBAL STATE)       */
    /* ================================================= */
    val tdtWindowSize = 10
    private val authWindow = ArrayDeque<Boolean>(tdtWindowSize)

    var totalWindows by mutableStateOf(0)
        private set

    var authenticatedWindows by mutableStateOf(0)
        private set

    var tdtAccuracy by mutableStateOf(0f)
        private set
    /* ================================================= */

    /* ----------------------------- */
    /* Start Authentication          */
    /* ----------------------------- */
    fun startAuthentication() {
        errorMessage = ""
        lastAuthResult = null

        if (!isCheckpointExists.value) {
            errorMessage = "Authentication model not enrolled."
            return
        }

        authenticationRunning = true

        auth.startAuthentication { result -> processAuthResult(result) }
    }

    /* ----------------------------- */
    /* Stop Authentication           */
    /* ----------------------------- */
    fun stopAuthentication() {
        authenticationRunning = false
        lastAuthResult = null
        auth.stopAuthentication()
    }

    /* ----------------------------- */
    /* Process Result (Public)       */
    /* ----------------------------- */
    fun processAuthResult(result: AuthVectorResult) {
        lastAuthResult = result
        // ✅ UPDATE TDT (Global Security State)
        updateTdt(result.isAuthenticated)
    }

    /* ================================================= */
    /* ✅ TDT CORE LOGIC (REUSABLE)                      */
    /* ================================================= */
    private fun updateTdt(isAuthenticated: Boolean) {
        authWindow.addLast(isAuthenticated)

        if (authWindow.size == tdtWindowSize) {

            val authenticatedCount = authWindow.count { it }

            val isAuthenticatedWindow = (authenticatedCount.toFloat() / tdtWindowSize) >= 0.5f

            totalWindows++

            if (isAuthenticatedWindow) {
                authenticatedWindows++
            }

            tdtAccuracy = authenticatedWindows.toFloat() / totalWindows.toFloat()

            // 🔁 NON-OVERLAPPING → CLEAR WINDOW
            authWindow.clear()
        }
    }

    private fun resetTdt() {
        authWindow.clear()
        totalWindows = 0
        authenticatedWindows = 0
        tdtAccuracy = 0f
    }
}

/* ----------------------------- */
/* Helper: Median                */
/* ----------------------------- */
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
