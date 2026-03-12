package com.ca.continuousauth.featuremodalities.synchronization

import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Aligns asynchronous motion-sensor streams (gyroscope, accelerometer)
 * onto a uniform time grid using linear interpolation.
 *
 * @param sampleRateHz The target output frequency (e.g. 100 → 10 ms grid).
 */
class SensorTimeSynchronizer(
    private val sampleRateHz: Int
) {
    private val gridIntervalNs: Long = 1_000_000_000L / sampleRateHz

    private data class TimestampedValue(val timestamp: Long, val values: List<Float>)

    private val gyroBuffer   = arrayOfNulls<TimestampedValue>(2)
    private val accelBuffer  = arrayOfNulls<TimestampedValue>(2)

    private var gyroCount  = 0
    private var accelCount = 0

    private var nextGridTimestamp: Long = Long.MIN_VALUE
    private val mutex = Mutex()

    fun synchronize(
        gyroFlow:  Flow<Pair<Long, List<Float>>>,
        accelFlow: Flow<Pair<Long, List<Float>>>
    ): Flow<SynchronizedSample> = channelFlow {

        suspend fun onNewSample(
            timestamp: Long,
            values: List<Float>,
            buffer: Array<TimestampedValue?>,
            countGetter: () -> Int,
            countSetter: (Int) -> Unit
        ) {
            val samplesToEmit = mutableListOf<SynchronizedSample>()

            mutex.withLock {
                // Update buffer
                val count = countGetter()
                if (count == 0) {
                    buffer[0] = TimestampedValue(timestamp, values)
                    countSetter(1)
                } else {
                    buffer[0] = buffer[if (count >= 2) 1 else 0]
                    buffer[1] = TimestampedValue(timestamp, values)
                    countSetter((count + 1).coerceAtMost(2))
                }

                // Initialize grid on first received sample
                if (nextGridTimestamp == Long.MIN_VALUE) {
                    val firstGyroTs = gyroBuffer[0]?.timestamp ?: timestamp
                    val firstAccelTs = accelBuffer[0]?.timestamp ?: timestamp
                    nextGridTimestamp = maxOf(firstGyroTs, firstAccelTs)
                    Logger.d("SensorTimeSynchronizer: Grid initialized at t=$nextGridTimestamp (${sampleRateHz}Hz)")
                }

                // Emit all samples for ready grid timestamps
                while (canEmitAt(nextGridTimestamp)) {
                    val sample = SynchronizedSample(
                        timestamp = nextGridTimestamp,
                        gyro  = interpolate(gyroBuffer,  gyroCount,  nextGridTimestamp),
                        accel = interpolate(accelBuffer, accelCount, nextGridTimestamp)
                    )
                    samplesToEmit.add(sample)
                    nextGridTimestamp += gridIntervalNs
                }
            }

            // Emit outside mutex
            for (sample in samplesToEmit) send(sample)
        }

        launch {
            gyroFlow.collect { (ts, vals) ->
                onNewSample(ts, vals, gyroBuffer, { gyroCount }, { gyroCount = it })
            }
        }

        launch {
            accelFlow.collect { (ts, vals) ->
                onNewSample(ts, vals, accelBuffer, { accelCount }, { accelCount = it })
            }
        }

    }.buffer(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private fun canEmitAt(gridTs: Long): Boolean {
        return latestTimestamp(gyroBuffer,  gyroCount)  >= gridTs &&
                latestTimestamp(accelBuffer, accelCount) >= gridTs
    }

    private fun latestTimestamp(buf: Array<TimestampedValue?>, count: Int): Long {
        return when {
            count >= 2 -> buf[1]!!.timestamp
            count == 1 -> buf[0]!!.timestamp
            else       -> Long.MIN_VALUE
        }
    }

    private fun interpolate(
        buf: Array<TimestampedValue?>,
        count: Int,
        gridTs: Long
    ): List<Float> {
        if (count == 0) return listOf(0f, 0f, 0f)
        if (count == 1) return buf[0]!!.values

        val older = buf[0]!!
        val newer = buf[1]!!

        val dt = newer.timestamp - older.timestamp
        if (dt == 0L) return newer.values

        val t = ((gridTs - older.timestamp).toFloat() / dt).coerceIn(0f, 1f)

        return List(older.values.size.coerceAtMost(newer.values.size)) { i ->
            older.values[i] + t * (newer.values[i] - older.values[i])
        }
    }
}