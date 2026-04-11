package com.ca.continuousauth.states

data class AuthVectorResult(
    val authType: String,             // "sensor" or "fusion"
    val isAuthenticated: Boolean,          // isAuthenticated
    val authenticationScore: Float,   // actual score (default 0.0 if null)
    val weightedConfidence: Double    // calculated weighted confidence
)