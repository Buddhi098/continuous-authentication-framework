package com.ca.continuousauth.featuremodalities.rawdatacollectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Linear Accelerometer data collector implementing RawDataCollector interface.
 * Emits each reading as a List<Float> along with its timestamp (in milliseconds).
 */
class LinearAccelerometerDataCollector(
    context: Context,
    private val frequencyHz: Int = AuthConfigManager.config.sampleCollectionFrequencyHz,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : RawDataCollector<List<Float>> {

    override val modalityName: String = "LINEAR_ACCELEROMETER"

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

    override fun start(): Flow<Pair<Long, List<Float>>> = callbackFlow {

        // Convert Hz → microseconds (Hint for the OS)
        val samplingPeriodUs = (1_000_000 / frequencyHz)

        // ------------------------------------------------
        // CASE 1: Linear Accelerometer NOT available -> Fallback
        // ------------------------------------------------
        if (accelerometer == null) {
            Logger.e("Linear Accelerometer not available. Emitting zero values.")
            val intervalMs = 1000L / frequencyHz

            val zeroJob = launch {
                while (true) {
                    val now = System.currentTimeMillis()
                    // Emit zeros to keep the pipeline alive
                    trySend(now to listOf(0f, 0f, 0f))
                    delay(intervalMs)
                }
            }
            awaitClose { zeroJob.cancel() }
            return@callbackFlow
        }

        // ------------------------------------------------
        // CASE 2: Linear Accelerometer available -> Real Data
        // ------------------------------------------------

        // OPTIMIZATION: Linear Acceleration is often a "virtual" sensor computed
        // by the system using software fusion (Gyro + Accel). This computation
        // is CPU-intensive. Moving it to a handler thread is critical to avoid UI lag.
        val sensorThread = HandlerThread("LinearAccelWorker")
        sensorThread.start()
        val sensorHandler = Handler(sensorThread.looper)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Capture timestamp immediately
                val timestamp = System.currentTimeMillis()

                // Copy values immediately
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                // Create payload (boxing 3 floats into a list)
                val rawData = listOf(x, y, z)

                // Try to send to the flow
                val result = trySend(timestamp to rawData)

                // Debugging: If this fails, consumer is too slow
                if (result.isFailure) {
                    // Logger.w("Linear Accel buffer overflow: Packet dropped")
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // No-op
            }
        }

        Logger.d("Registering linear accelerometer listener at ${frequencyHz}Hz")

        try {
            sensorManager.registerListener(
                listener,
                accelerometer,
                samplingPeriodUs,
                sensorHandler // Execute on background thread
            )
        } catch (ex: Exception) {
            Logger.e("Failed to register linear accelerometer listener", ex)
            close(ex)
        }

        // Cleanup when the flow collection stops
        awaitClose {
            try {
                Logger.d("Unregistering linear accelerometer listener")
                sensorManager.unregisterListener(listener)
                sensorThread.quitSafely() // Important: Stop the background thread
            } catch (ex: Exception) {
                Logger.e("Error unregistering linear accelerometer listener", ex)
            }
        }
    }
        // ------------------------------------------------
        // CRITICAL FIX: Buffer Strategy
        // ------------------------------------------------
        // Replaced Channel.UNLIMITED with a fixed capacity + DROP_OLDEST.
        // This ensures the system always processes fresh data and prevents latency accumulation.
        .buffer(
            capacity = 50,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        // Handle unexpected errors in the flow pipeline
        .catch { ex ->
            Logger.e("Linear Accelerometer flow error", ex)
        }
        .flowOn(dispatcher)
}