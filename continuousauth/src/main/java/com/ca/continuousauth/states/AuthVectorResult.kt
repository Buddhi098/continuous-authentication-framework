package com.ca.continuousauth.states

data class AuthVectorResult(
    val featureVector: List<Float>,
    val isAuthenticated: Boolean,
    val score: Float?,
    val threshold: Float?
)