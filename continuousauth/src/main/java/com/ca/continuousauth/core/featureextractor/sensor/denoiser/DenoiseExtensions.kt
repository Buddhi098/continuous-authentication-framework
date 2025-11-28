package com.ca.continuousauth.core.featureextractor.sensor.denoiser
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.flow.*

/**
 * Flow extension to denoise sensor batches using any SensorDenoiser.
 */
fun Flow<List<FloatArray>>.denoiseWith(
    denoiser: SensorDenoiserInterface,
    enableLogging: Boolean = false
): Flow<List<FloatArray>> {

    var batchCounter = 0

    return this.map { batch ->
        batchCounter++

        // Log before denoising
        if (enableLogging) {
            Logger.d("denoiseWith → Received Batch #$batchCounter (size=${batch.size})")
            batch.forEachIndexed { i, sample ->
                Logger.d(
                    "denoiseWith → Batch #$batchCounter raw[$i] = ${
                        sample.joinToString(prefix = "[", postfix = "]")
                    }"
                )
            }
        }

        // Denoise
        val denoised = denoiser.denoise(batch)

        // Log after denoising
        if (enableLogging) {
            Logger.d("denoiseWith → Denoised Batch #$batchCounter")
            denoised.forEachIndexed { i, sample ->
                Logger.d(
                    "denoiseWith → Batch #$batchCounter denoised[$i] = ${
                        sample.joinToString(prefix = "[", postfix = "]")
                    }"
                )
            }
        }

        denoised
    }
}

