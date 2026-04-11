package com.ca.continuousauth.states

data class AuthVectorResult(
    val authType: String,
    val isAuthenticated: Boolean,
    val authenticationScore: Float,
    val weightedConfidence: Double,

    // Window-level outputs
    val windowPassed: Boolean = false,
    val overallWindowAccuracy: Double = 0.0
)