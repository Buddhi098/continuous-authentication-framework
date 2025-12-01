package com.ca.continuousauth.featuremodalities.rawdatacollectors

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.channels.onFailure

/**
 * Touch data collector implementing RawDataCollector interface.
 * Emits raw touch values along with timestamps at a specified frequency.
 * If no touch event occurred within the interval, emits zero values.
 */
class TouchDataCollector(
    private val view: View,  // The view to attach the touch listener to
    private val frequencyHz: Int = AuthConfigManager.config.sampleCollectionFrequencyHz, // desired frequency in Hz
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : RawDataCollector<List<Float>> {

    override val modalityName: String = "TOUCH"

    @SuppressLint("ClickableViewAccessibility")
    override fun start(): Flow<Pair<Long, List<Float>>> = callbackFlow {

        val minIntervalMs = (1000 / frequencyHz).toLong()
        var lastTouchTime = 0L
        var lastRawData: List<Float> = listOf(0f, 0f, 0f, 0f, 0f)

        val listener = View.OnTouchListener { _, event ->
            try {
                val currentTimeMs = System.currentTimeMillis()
                lastTouchTime = currentTimeMs
                lastRawData = listOf(
                    event.x,
                    event.y,
                    event.pressure,
                    event.size,
                    event.action.toFloat()
                )
            } catch (ex: Exception) {
                Logger.e("Unexpected error in onTouchListener", ex)
            }
            true
        }

        try {
            Logger.d("Registering touch listener on view: $view")
            view.setOnTouchListener(listener)
        } catch (ex: Exception) {
            Logger.e("Failed to register touch listener", ex)
            close(ex)
            return@callbackFlow
        }

        // Emit touch events at the specified frequency
        val tickerJob = CoroutineScope(dispatcher).launch {
            while (isActive) {
                val currentTimeMs = System.currentTimeMillis()
                val elapsed = currentTimeMs - lastTouchTime

                val dataToSend = if (elapsed <= minIntervalMs * 2) {
                    lastRawData
                } else {
                    // Last event too old → send zeros
                    listOf(0f, 0f, 0f, 0f, 0f)
                }

                trySend(currentTimeMs to dataToSend).onFailure { err ->
                    Logger.e("Failed to emit touch data", err)
                }

                delay(minIntervalMs)
            }
        }

        awaitClose {
            try {
                Logger.d("Unregistering touch listener from view: $view")
                view.setOnTouchListener(null)
                tickerJob.cancel()
            } catch (ex: Exception) {
                Logger.e("Error while unregistering touch listener", ex)
            }
        }
    }
        .catch { ex ->
            Logger.e("TouchDataCollector flow error", ex)
            throw ex
        }
        .flowOn(dispatcher)
}
