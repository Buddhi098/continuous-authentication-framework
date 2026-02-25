package com.ca.continuousauth.states

data class EnrollmentResult(
        val success: Boolean,
        val threshold: Float? = null,
        val fusionThreshold: Float? = null,
        val trainedSampleCount: Int? = null,
        val message: String? = null,
        val fusionAvailable: Boolean = false
)
