package com.ca.continuousauth.`core-old`.featureextractor.sensor.denoiser

interface SensorDenoiserInterface {
    fun denoise(batch: List<FloatArray>): List<FloatArray>
}