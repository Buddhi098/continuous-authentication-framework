package com.ca.continuousauth.states

data class TouchEventData(
    val action: Int,
    val timestamp: Long,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val size: Float,
    val orientation: Float,
    val touchMajor: Float,
    val touchMinor: Float,
    val pointerCount: Int
)
