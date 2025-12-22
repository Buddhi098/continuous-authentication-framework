package com.ca.continuousauth.featuremodalities.rawdatacollectors

import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlin.math.abs

/**
 * Touch Data Collector using gesture-level features (DOWN → UP)
 *
 * Emits a fixed-size 5D feature vector:
 * [ dx, dy, gestureSpeed, gestureDuration, avgPressure ]
 */
class TouchDataCollector(
    private val touchEventFlow: Flow<TouchEventData>?,
    private val frequencyHz: Int = AuthConfigManager.config.sampleCollectionFrequencyHz,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : RawDataCollector<List<Float>> {

    override val modalityName: String = "TOUCH"

    override fun start(): Flow<Pair<Long, List<Float>>> = callbackFlow {
        val minIntervalMs = (1000 / frequencyHz.toLong())
        var lastVector = zeroVector()

        // Gesture state
        var startX = 0f
        var startY = 0f
        var lastX = 0f
        var lastY = 0f
        var startTime = 0L
        var totalDistance = 0f
        var pressureSum = 0f
        var pressureCount = 0

        // Emit initial zero vector
        trySend(System.currentTimeMillis() to lastVector).isSuccess

        val job = touchEventFlow?.onEach { event ->
            when (event.action) {
                0 -> { // ACTION_DOWN
                    startX = event.x
                    startY = event.y
                    lastX = event.x
                    lastY = event.y
                    startTime = event.timestamp
                    totalDistance = 0f
                    pressureSum = event.pressure
                    pressureCount = 1
                }

                2 -> { // ACTION_MOVE
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    totalDistance += kotlin.math.sqrt(dx * dx + dy * dy)
                    lastX = event.x
                    lastY = event.y
                    pressureSum += event.pressure
                    pressureCount++
                }

                1 -> { // ACTION_UP
                    val durationMs = (event.timestamp - startTime).coerceAtLeast(1L)
                    val dx = event.x - startX
                    val dy = event.y - startY
                    val speed = totalDistance / durationMs
                    val avgPressure =
                        if (pressureCount > 0) pressureSum / pressureCount else 0f
                    val dxAbs = abs(dx)
                    val dyAbs = abs(dy)
                    val speedAbs = abs(speed)
                    val durationAbs = abs(durationMs.toFloat())

                    lastVector = listOf(
                        dxAbs,
                        dyAbs,
                        speedAbs,
                        durationAbs,
                    )
                }
            }
        }?.launchIn(this)

        while (isActive) {
            trySend(System.currentTimeMillis() to lastVector).isSuccess
            delay(minIntervalMs)
        }

        awaitClose { job?.cancel() }
    }
        .catch { ex ->
            Logger.e("TouchDataCollector error", ex)
            throw ex
        }
        .flowOn(dispatcher)

    private fun zeroVector(): List<Float> = List(4) { 0f }
}
