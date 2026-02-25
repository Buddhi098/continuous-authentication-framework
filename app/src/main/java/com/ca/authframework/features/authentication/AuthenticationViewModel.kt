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
    val isFusionModelReady: StateFlow<Boolean> = auth.isFusionModelReady

    /* ----------------------------- */
    /* UI State                      */
    /* ----------------------------- */
    var authenticationRunning by mutableStateOf(false)
        private set

    var lastAuthResult by mutableStateOf<AuthVectorResult?>(null)
        private set

    var errorMessage by mutableStateOf("")
        private set

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
