package com.ca.continuousauth.featuremodalities.rawdatacollectors

import com.ca.continuousauth.states.TouchEventData
import com.ca.continuousauth.utils.Logger
import kotlin.math.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

private object TouchAction {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
}

/**
 * Immutable snapshot of a gesture.
 * Lists are copied to prevent concurrent modification exceptions during Flow processing.
 */
private data class GestureSnapshot(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val startTime: Long,
    val endTime: Long,
    val pressure: List<Float>,
    val size: List<Float>,
    val speeds: List<Float>,
    val accelerations: List<Float>,
    val pathLength: Float
)

/**
 * High-performance gesture tracker.
 * Focuses on point-to-point kinematics rather than overarching spatial tracking.
 */
private class GestureState {

    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastTime = 0L
    private var pathLength = 0f

    val pressure = ArrayList<Float>(32)
    val size = ArrayList<Float>(32)
    val speed = ArrayList<Float>(32)
    val acceleration = ArrayList<Float>(32)

    fun reset(x: Float, y: Float, t: Long) {
        startX = x
        startY = y
        lastX = x
        lastY = y
        lastTime = t
        pathLength = 0f

        pressure.clear()
        size.clear()
        speed.clear()
        acceleration.clear()
    }

    fun add(event: TouchEventData) {
        val dt = max(1L, event.timestamp - lastTime).toFloat()
        val dx = event.x - lastX
        val dy = event.y - lastY

        val dist = sqrt(dx * dx + dy * dy)
        val vel = dist / dt // Instantaneous velocity (pixels/ms)

        // Calculate point-to-point acceleration if we have a previous speed
        if (speed.isNotEmpty()) {
            val accel = (vel - speed.last()) / dt
            acceleration.add(accel)
        }

        pathLength += dist

        pressure.add(event.pressure)
        size.add(event.size)
        speed.add(vel)

        lastX = event.x
        lastY = event.y
        lastTime = event.timestamp
    }

    fun buildSnapshot(endEvent: TouchEventData): GestureSnapshot {
        return GestureSnapshot(
            startX = startX,
            startY = startY,
            endX = endEvent.x,
            endY = endEvent.y,
            startTime = endEvent.downTime,
            endTime = endEvent.timestamp,
            // .toList() prevents downstream mutation bugs
            pressure = pressure.toList(),
            size = size.toList(),
            speeds = speed.toList(),
            accelerations = acceleration.toList(),
            pathLength = pathLength
        )
    }
}

/**
 * TOUCH FEATURE COLLECTOR
 * Optimized for Anomaly Detection (Continuous Authentication).
 * Extracts 11 high-signal, UI-agnostic behavioral features.
 */
class TouchDataCollector(
    private val touchEventFlow: Flow<TouchEventData>?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    companion object {
        private const val BUFFER_CAPACITY = 128
        private const val FEATURE_SIZE = 11 // Refined down to the most discriminative 11
    }

    fun start(): Flow<Pair<Long, List<Float>>> =
        callbackFlow {

            trySend(System.currentTimeMillis() to zeroVector())

            if (touchEventFlow == null) {
                Logger.d("Touch flow null → fallback mode")
                awaitClose {}
                return@callbackFlow
            }

            val state = GestureState()

            val job = touchEventFlow.onEach { event ->

                when (event.action) {
                    TouchAction.ACTION_DOWN -> {
                        state.reset(event.x, event.y, event.timestamp)
                    }
                    TouchAction.ACTION_MOVE -> {
                        state.add(event)
                    }
                    TouchAction.ACTION_UP -> {
                        state.add(event)
                        val snap = state.buildSnapshot(event)

                        // Ignore micro-touches (accidental bumps)
                        if (snap.speeds.size > 3) {
                            val features = computeFeatures(snap)
                            trySend(event.timestamp to features)
                        }
                    }
                }

            }.catch {
                Logger.e("TouchDataCollector error", it)
            }.launchIn(this)

            awaitClose { job.cancel() }
        }
            .buffer(BUFFER_CAPACITY, BufferOverflow.DROP_OLDEST)
            .flowOn(dispatcher)

    /**
     * Extracts pure behavioral and physiological features.
     * UI-agnostic: Does not care where the swipe happened or how long it was.
     */
    private fun computeFeatures(s: GestureSnapshot): List<Float> {

        val dx = s.endX - s.startX
        val dy = s.endY - s.startY
        val straightLineDist = sqrt(dx * dx + dy * dy)
        val duration = max(1L, s.endTime - s.startTime).toFloat()

        // 1. Timing
        val durationSec = duration / 1000f

        // 2. Trajectory Dynamics (Curvature of the swipe)
        // Values close to 1.0 = straight line. Lower values = curved/wobbly swiper.
        val efficiency = if (s.pathLength > 0f) straightLineDist / s.pathLength else 0f

        // 3. Kinematics (Speed & Acceleration Profiles)
        val meanSpeed = s.speeds.average().toFloat()
        val stdSpeed = std(s.speeds)
        val maxSpeed = s.speeds.maxOrNull() ?: 0f

        val meanAccel = if (s.accelerations.isNotEmpty()) s.accelerations.average().toFloat() else 0f
        val stdAccel = std(s.accelerations)

        // 4. Physiological Traits (Biometric interaction forces)
        val meanPressure = s.pressure.average().toFloat()
        val stdPressure = std(s.pressure) // Represents finger "roll" and grip change
        val meanSize = s.size.average().toFloat() // Represents physical finger surface area
        val stdSize = std(s.size) // Represents physical deformation during movement

        return listOf(
            durationSec,      // 0
            efficiency,       // 1
            meanSpeed,        // 2
            stdSpeed,         // 3
            maxSpeed,         // 4
            meanAccel,        // 5
            stdAccel,         // 6
            meanPressure,     // 7
            stdPressure,      // 8
            meanSize,         // 9
            stdSize           // 10
        )
    }

    private fun zeroVector() = List(FEATURE_SIZE) { 0f }

    private fun std(list: List<Float>): Float {
        if (list.size <= 1) return 0f
        val mean = list.average()
        val variance = list.sumOf { (it - mean).pow(2) } / list.size
        return sqrt(variance).toFloat()
    }
}