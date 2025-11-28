package com.ca.continuousauth.core.featureextractor.sensor.denoiser.denoisercollection

import com.ca.continuousauth.core.featureextractor.sensor.denoiser.SensorDenoiserInterface

class SMADenoiser(private val windowSize: Int = 3) : SensorDenoiserInterface {
    override fun denoise(batch: List<FloatArray>): List<FloatArray> {
        if (batch.isEmpty()) return emptyList()
        val dimCount = batch.first().size

        return batch.mapIndexed { index, sample ->
            FloatArray(dimCount) { dim ->
                val start = maxOf(0, index - windowSize + 1)
                val end = index
                var sum = 0f
                for (i in start..end) sum += batch[i][dim]
                sum / (end - start + 1)
            }
        }
    }
}