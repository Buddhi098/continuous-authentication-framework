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
}
