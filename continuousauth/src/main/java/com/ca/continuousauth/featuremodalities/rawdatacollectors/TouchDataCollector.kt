package com.ca.continuousauth.featuremodalities.rawdatacollectors

import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.sqrt

class TouchDataCollector(
    private val touchEventFlow: Flow<TouchEventData>?,
    private val frequencyHz: Int = AuthConfigManager.config.sampleCollectionFrequencyHz,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : RawDataCollector<List<Float>> {

    override val modalityName: String = "TOUCH"

    override fun start(): Flow<Pair<Long, List<Float>>> = callbackFlow {
        val minIntervalMs = 1000L / frequencyHz
        val lastVector = AtomicReference(zeroVector())

        // Gesture tracking
        var startX = 0f
        var startY = 0f
        var lastX = 0f
        var lastY = 0f
        var lastTimestamp = 0L

        var totalDistance = 0f
        var pressureList = mutableListOf<Float>()
        var sizeList = mutableListOf<Float>()
        var orientationList = mutableListOf<Float>()
        var speedList = mutableListOf<Float>()
        var pointerList = mutableListOf<Int>()
        var majorMinorRatioList = mutableListOf<Float>()

        trySend(System.currentTimeMillis() to lastVector.get()).isSuccess

        val job = touchEventFlow
            ?.onEach { event ->
                when (event.action) {
                    0 -> { // ACTION_DOWN
                        startX = event.x
                        startY = event.y
                        lastX = event.x
                        lastY = event.y
                        lastTimestamp = event.timestamp

                        totalDistance = 0f
                        pressureList.clear()
                        sizeList.clear()
                        orientationList.clear()
                        speedList.clear()
                        pointerList.clear()
                        majorMinorRatioList.clear()

                        pressureList.add(event.pressure)
                        sizeList.add(event.size)
                        orientationList.add(event.orientation)
                        pointerList.add(event.pointerCount)
                        majorMinorRatioList.add(
                            if (event.touchMinor != 0f) event.touchMajor / event.touchMinor else 0f
                        )
                    }

                    2 -> { // ACTION_MOVE
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        val dt = (event.timestamp - lastTimestamp).coerceAtLeast(1L)

                        val distance = sqrt(dx * dx + dy * dy)
                        val speed = distance / dt.toFloat()

                        totalDistance += distance
                        speedList.add(speed)
                        lastX = event.x
                        lastY = event.y
                        lastTimestamp = event.timestamp

                        pressureList.add(event.pressure)
                        sizeList.add(event.size)
                        orientationList.add(event.orientation)
                        pointerList.add(event.pointerCount)
                        majorMinorRatioList.add(
                            if (event.touchMinor != 0f) event.touchMajor / event.touchMinor else 0f
                        )
                    }

                    1 -> { // ACTION_UP
                        val durationMs = (event.timestamp - event.downTime).coerceAtLeast(1L)
                        val dx = event.x - startX
                        val dy = event.y - startY
                        val speed = totalDistance / durationMs.toFloat()

                        // Aggregate features
                        val avgPressure = pressureList.average().toFloat()
                        val maxPressure = pressureList.maxOrNull() ?: 0f
                        val minPressure = pressureList.minOrNull() ?: 0f
                        val pressureVar = variance(pressureList)

                        val avgSize = sizeList.average().toFloat()
                        val maxSize = sizeList.maxOrNull() ?: 0f
                        val minSize = sizeList.minOrNull() ?: 0f

                        val avgOrientation = orientationList.average().toFloat()

                        val avgSpeed = speedList.average().toFloat()
                        val maxSpeed = speedList.maxOrNull() ?: 0f
                        val acceleration = if (speedList.size > 1) {
                            (speedList.last() - speedList.first()) / durationMs.toFloat()
                        } else 0f

                        val avgPointers = pointerList.average().toFloat()
                        val maxPointers = pointerList.maxOrNull() ?: 0

                        val avgMajorMinorRatio = majorMinorRatioList.average().toFloat()

                        lastVector.set(
                            listOf(
                                abs(dx),                // total x displacement
                                abs(dy),                // total y displacement
                                speed,                  // average speed
                                durationMs / 1000f,     // duration in seconds
                                avgPressure,
                                maxPressure,
                                minPressure,
                                pressureVar,
                                avgSize,
                                maxSize,
                                minSize,
                                avgOrientation,
                                maxSpeed,
                                acceleration,
                            ) as List<Float>?
                        )
                    }
                }
            }
            ?.launchIn(this)

        while (isActive) {
            trySend(System.currentTimeMillis() to lastVector.get()).isSuccess
            delay(minIntervalMs)
        }

        awaitClose { job?.cancel() }
    }
        .catch { ex ->
            Logger.e("TouchDataCollector error", ex)
            throw ex
        }
        .flowOn(dispatcher)

    private fun zeroVector(): List<Float> = List(14) { 0f }

    private fun variance(list: List<Float>): Float {
        if (list.isEmpty()) return 0f
        val mean = list.average()
        return list.map { (it - mean).toFloat() * (it - mean).toFloat() }.average().toFloat()
    }
}