package com.ca.continuousauth.featuremodalities.dataprocessing.denoisers

import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform

/**
 * Applies each denoiser sequentially to every window emitted by the upstream Flow.
 */
fun Flow<List<Pair<Long, List<Float>>>>.denoisePipeline(
    denoisers: List<SensorDenoiser>
): Flow<List<Pair<Long, List<Float>>>> = transform { window ->

    var processed = window

    for (denoiser in denoisers) {
        try {
            processed = denoiser.denoiseWindow(processed)
        } catch (e: Exception) {
            Logger.e("Denoiser ${denoiser::class.simpleName} failed", e)
            // continue with the partially processed window
        }
    }

    emit(processed)

//    Logger.d(
//        "DenoisePipeline: emitted window size=${processed.size}, " +
//                "first_ts=${processed.firstOrNull()?.first ?: -1}"
//    )
}
