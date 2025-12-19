package com.ca.continuousauth.featuremodalities.rawdatacollectors

import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

/**
 * Touch data collector with fixed frequency emission.
 *
 * Emits:
 *   - initial zero vector immediately,
 *   - updated touch data whenever a new touch occurs,
 *   - repeated last value at fixed intervals according to frequency.
 *
 * Output: Pair<timestamp, rawVector>
 */
class TouchDataCollector(
    private val touchEventFlow: Flow<TouchEventData>?,
    private val frequencyHz: Int = AuthConfigManager.config.sampleCollectionFrequencyHz,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : RawDataCollector<List<Float>> {

    override val modalityName: String = "TOUCH"

    override fun start(): Flow<Pair<Long, List<Float>>> = callbackFlow {
        val minIntervalMs = (1000 / frequencyHz.toLong())
        var lastTouchData: List<Float> = zeroVector()

        // Emit initial zero vector immediately
        trySend(System.currentTimeMillis() to lastTouchData).isSuccess

        // Collect touch events asynchronously
        val touchCollectorJob = touchEventFlow?.onEach { event ->
            lastTouchData = listOf(
                event.x,
                event.y,
                event.pressure,
                event.size,
                event.orientation,
                event.touchMajor,
                event.touchMinor
            )
        }?.launchIn(this) // 'this' is CoroutineScope of callbackFlow

        // Emit at fixed frequency
        while (isActive) {
            trySend(System.currentTimeMillis() to lastTouchData).isSuccess
            delay(minIntervalMs)
        }

        // Cancel the collector when flow is closed
        awaitClose { touchCollectorJob?.cancel() }
    }
        .catch { ex ->
            Logger.e("TouchDataCollector error", ex)
            throw ex
        }
        .flowOn(dispatcher)

    private fun zeroVector(): List<Float> = listOf(0f, 0f, 0f, 0f, 0f , 0f , 0f)
}
