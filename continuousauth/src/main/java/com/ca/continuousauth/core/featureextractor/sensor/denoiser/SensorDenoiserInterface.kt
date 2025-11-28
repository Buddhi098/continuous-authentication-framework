package com.ca.continuousauth.core.featureextractor.sensor.denoiser

interface SensorDenoiserInterface {
    fun denoise(batch: List<FloatArray>): List<FloatArray>
}