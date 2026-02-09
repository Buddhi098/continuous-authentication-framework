package com.ca.authframework.features.authentication

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.ca.authframework.features.tdtlock.TdtComputer
import com.ca.continuousauth.ContinuousAuth
import com.ca.continuousauth.states.AuthVectorResult
import kotlinx.coroutines.flow.StateFlow

class AuthenticationViewModel(
        private val auth: ContinuousAuth,
        private val tdtComputer: TdtComputer = TdtComputer()
) : ViewModel() {

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
    /* TDT State (delegated to TdtComputer)              */
    /* ================================================= */
    val tdtWindowSize: Int
        get() = 10 // For backward compatibility

    val totalWindows: StateFlow<Int> = tdtComputer.totalWindows

    val authenticatedWindows: StateFlow<Int> = tdtComputer.authenticatedWindows

    val tdtAccuracy: StateFlow<Float> = tdtComputer.tdtAccuracy

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
        // Delegate to TdtComputer
        tdtComputer.addResult(result.isAuthenticated)
    }

    /* ----------------------------- */
    /* Reset TDT                     */
    /* ----------------------------- */
    fun resetTdt() {
        tdtComputer.reset()
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
