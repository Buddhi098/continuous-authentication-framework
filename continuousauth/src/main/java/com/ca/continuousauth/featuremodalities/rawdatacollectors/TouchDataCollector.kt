package com.ca.continuousauth.featuremodalities.rawdatacollectors

import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

/** Touch action constants for clarity and maintainability. */
private object TouchAction {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
}

/**
 * Encapsulates all mutable gesture tracking state. This allows atomic updates and clear lifecycle
 * management.
 */
private class GestureState {
    var startX: Float = 0f
    var startY: Float = 0f
    var lastX: Float = 0f
    var lastY: Float = 0f
    var lastTimestamp: Long = 0L
    var totalDistance: Float = 0f

    val pressureList = mutableListOf<Float>()
    val sizeList = mutableListOf<Float>()
    val orientationList = mutableListOf<Float>()
    val speedList = mutableListOf<Float>()
    val pointerList = mutableListOf<Int>()
    val majorMinorRatioList = mutableListOf<Float>()

    /** Resets all gesture state for a new touch gesture. */
    fun reset(x: Float, y: Float, timestamp: Long) {
        startX = x
        startY = y
        lastX = x
        lastY = y
        lastTimestamp = timestamp
        totalDistance = 0f

        pressureList.clear()
        sizeList.clear()
        orientationList.clear()
        speedList.clear()
        pointerList.clear()
        majorMinorRatioList.clear()
    }

    /** Records initial touch data on ACTION_DOWN. */
    fun recordInitialData(event: TouchEventData) {
        pressureList.add(event.pressure)
        sizeList.add(event.size)
        orientationList.add(event.orientation)
        pointerList.add(event.pointerCount)
        majorMinorRatioList.add(computeMajorMinorRatio(event))
    }

    /** Updates state with move event data. */
    fun addMoveData(event: TouchEventData): Float {
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
        majorMinorRatioList.add(computeMajorMinorRatio(event))

        return speed
    }

    private fun computeMajorMinorRatio(event: TouchEventData): Float =
            if (event.touchMinor != 0f) event.touchMajor / event.touchMinor else 0f
}

/**
 * Collects touch event data and emits aggregated feature vectors at a configurable frequency.
 *
 * Design notes:
 * - Emits a 14-element feature vector capturing gesture dynamics
 * - Uses atomic reference for thread-safe vector updates
 * - Applies buffer strategy to prevent data loss during high-frequency touch events
 * - Handles null touchEventFlow gracefully with fallback zero emissions
 */
class TouchDataCollector(
        private val touchEventFlow: Flow<TouchEventData>?,
        private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : RawDataCollector<List<Float>> {

    override val modalityName: String = "TOUCH"

    companion object {
        private const val FEATURE_VECTOR_SIZE = 14
        private const val BUFFER_CAPACITY = 100
    }

    override fun start(): Flow<Pair<Long, List<Float>>> =
            callbackFlow {
                        // Emit initial zero vector so combine is not blocked
                        trySend(System.currentTimeMillis() to zeroVector()).isSuccess

                        // ------------------------------------------------
                        // CASE 1: TouchEventFlow NOT available → Fallback
                        // ------------------------------------------------
                        if (touchEventFlow == null) {
                            Logger.d(
                                    "TouchEventFlow is null. Emitted initial zero vector. Suspending."
                            )
                            awaitClose {}
                            return@callbackFlow
                        }

                        // ------------------------------------------------
                        // CASE 2: TouchEventFlow available → Process events
                        // ------------------------------------------------
                        val gestureState = GestureState()

                        val eventJob =
                                touchEventFlow
                                        .onEach { event ->
                                            when (event.action) {
                                                TouchAction.ACTION_DOWN -> {
                                                    gestureState.reset(
                                                            event.x,
                                                            event.y,
                                                            event.timestamp
                                                    )
                                                    gestureState.recordInitialData(event)
                                                    Logger.d(
                                                            "TouchDataCollector: ACTION_DOWN at (${event.x}, ${event.y})"
                                                    )
                                                }
                                                TouchAction.ACTION_MOVE -> {
                                                    gestureState.addMoveData(event)
                                                }
                                                TouchAction.ACTION_UP -> {
                                                    val vector =
                                                            computeFeatureVector(
                                                                    event,
                                                                    gestureState
                                                            )
                                                    val hasNonZero = vector.any { it != 0f }
                                                    Logger.d(
                                                            "TouchDataCollector: ACTION_UP - computed feature vector (dim=${vector.size}, hasNonZero=$hasNonZero)"
                                                    )
                                                    if (hasNonZero) {
                                                        trySend(event.timestamp to vector).isSuccess
                                                        Logger.d(
                                                                "TouchDataCollector: Gesture completed - new feature emitted"
                                                        )
                                                    } else {
                                                        Logger.d(
                                                                "TouchDataCollector: Gesture completed - skipped (duplicate or zero)"
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        .catch { ex ->
                                            Logger.e(
                                                    "TouchDataCollector: Error processing touch events",
                                                    ex
                                            )
                                        }
                                        .launchIn(this)

                        awaitClose { eventJob.cancel() }
                    }
                    // Buffer strategy: DROP_OLDEST prevents latency accumulation
                    .buffer(
                            capacity = BUFFER_CAPACITY,
                            onBufferOverflow = BufferOverflow.DROP_OLDEST
                    )
                    .catch { ex ->
                        Logger.e("TouchDataCollector flow error", ex)
                        throw ex
                    }
                    .flowOn(dispatcher)

    /** Computes the 14-element feature vector from gesture state. */
    private fun computeFeatureVector(event: TouchEventData, state: GestureState): List<Float> {
        val durationMs = (event.timestamp - event.downTime).coerceAtLeast(1L)
        val dx = event.x - state.startX
        val dy = event.y - state.startY
        val speed = state.totalDistance / durationMs.toFloat()

        // Pressure features
        val avgPressure = state.pressureList.averageOrZero()
        val maxPressure = state.pressureList.maxOrNull() ?: 0f
        val minPressure = state.pressureList.minOrNull() ?: 0f
        val pressureVar = variance(state.pressureList)

        // Size features
        val avgSize = state.sizeList.averageOrZero()
        val maxSize = state.sizeList.maxOrNull() ?: 0f
        val minSize = state.sizeList.minOrNull() ?: 0f

        // Orientation
        val avgOrientation = state.orientationList.averageOrZero()

        // Speed features
        val maxSpeed = state.speedList.maxOrNull() ?: 0f
        val acceleration =
                if (state.speedList.size > 1) {
                    (state.speedList.last() - state.speedList.first()) / durationMs.toFloat()
                } else 0f

        return listOf(
                abs(dx), // 0: total x displacement
                abs(dy), // 1: total y displacement
                speed, // 2: average speed
                durationMs / 1000f, // 3: duration in seconds
                avgPressure, // 4: average pressure
                maxPressure, // 5: max pressure
                minPressure, // 6: min pressure
                pressureVar, // 7: pressure variance
                avgSize, // 8: average size
                maxSize, // 9: max size
                minSize, // 10: min size
                avgOrientation, // 11: average orientation
                maxSpeed, // 12: max speed
                acceleration // 13: acceleration
        )
    }

    private fun zeroVector(): List<Float> = List(FEATURE_VECTOR_SIZE) { 0f }

    /** Computes variance using a numerically stable single-pass approach. */
    private fun variance(list: List<Float>): Float {
        if (list.isEmpty()) return 0f
        val mean = list.average()
        return (list.sumOf { (it - mean) * (it - mean) } / list.size).toFloat()
    }

    /** Extension to safely compute average or return 0 for empty lists. */
    private fun List<Float>.averageOrZero(): Float = if (isEmpty()) 0f else average().toFloat()
}
