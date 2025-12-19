package com.ca.continuousauth.states

data class AuthVectorResult(
    val isAuthenticated: Boolean,
    val score: Float?,
    val threshold: Float?,
    val authPercentage: Float? = null // new field for percentage
)