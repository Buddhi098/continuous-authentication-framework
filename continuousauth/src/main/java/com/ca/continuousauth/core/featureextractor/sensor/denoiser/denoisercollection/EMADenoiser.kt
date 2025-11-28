package com.ca.continuousauth.core.featureextractor.sensor.denoiser.denoisercollection

import com.ca.continuousauth.core.featureextractor.sensor.denoiser.SensorDenoiserInterface

class EMADenoiser(private val alpha: Float = 0.2f) : SensorDenoiserInterface {
    override fun denoise(batch: List<FloatArray>): List<FloatArray> {
        if (batch.isEmpty()) return emptyList()

        val dimCount = batch.first().size
        val denoisedBatch = mutableListOf<FloatArray>()

        // Initialize EMA with first sample
        var prevEMA = batch.first().copyOf()
        denoisedBatch.add(prevEMA.copyOf())

        for (i in 1 until batch.size) {
            val sample = batch[i]
            val emaSample = FloatArray(dimCount) { dim ->
                alpha * sample[dim] + (1 - alpha) * prevEMA[dim]
            }
            denoisedBatch.add(emaSample)
            prevEMA = emaSample
        }

        return denoisedBatch
    }
}