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

class TotalAccelerometerDataCollector(
    context: Context,
    // Default to config, but allow override for testing/flexibility
    private val frequencyHz: Int = AuthConfigManager.config.sampleCollectionFrequencyHz,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : RawDataCollector<List<Float>> {

    override val modalityName: String = "TOTAL_ACCELEROMETER"

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override fun start(): Flow<Pair<Long, List<Float>>> = callbackFlow {

        // 1. Calculate Sampling Period in Microseconds
        // Note: This is a hint to the OS. Actual frequency may vary.
        val samplingPeriodUs = (1_000_000 / frequencyHz)

        // ------------------------------------------------
        // CASE 1: Accelerometer NOT available (Fallback)
        // ------------------------------------------------
        if (accelerometer == null) {
            Logger.e("Accelerometer not available. Emitting zero values.")
            val intervalMs = 1000L / frequencyHz

            val zeroJob = launch {
                while (true) {
                    // Emit zeros to keep the pipeline alive if sensor is missing
                    trySend(System.currentTimeMillis() to listOf(0f, 0f, 0f))
                    delay(intervalMs)
                }
            }
            awaitClose { zeroJob.cancel() }
            return@callbackFlow
        }

        // ------------------------------------------------
        // CASE 2: Accelerometer available (Real Data)
        // ------------------------------------------------

        // OPTIMIZATION: Background HandlerThread
        // We move sensor event delivery off the Main UI thread to prevent UI jank.
        val sensorThread = HandlerThread("SensorWorkerThread")
        sensorThread.start()
        val sensorHandler = Handler(sensorThread.looper)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Read values immediately as 'event' object is reused by Android
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val timestamp = System.currentTimeMillis()

                // Create the data payload
                // (Note: 'listOf' creates objects. If performance is critical later,
                // consider changing interface to FloatArray to avoid GC overhead)
                val data = listOf(x, y, z)

                // Try to send to the flow
                val result = trySend(timestamp to data)

                // Debugging: Monitor if we are keeping up
                if (result.isFailure) {
                    // If this logs frequently, your ML model is slower than 100Hz
                    // Logger.w("AccBuffer overflow: Packet dropped.")
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // No-op
            }
        }

        Logger.d("Registering accelerometer listener at ${frequencyHz}Hz")

        try {
            sensorManager.registerListener(
                listener,
                accelerometer,
                samplingPeriodUs,
                sensorHandler
            )
        } catch (ex: Exception) {
            Logger.e("Failed to register accelerometer listener", ex)
            close(ex)
        }

        // Cleanup when the flow collection stops
        awaitClose {
            try {
                Logger.d("Unregistering accelerometer listener")
                sensorManager.unregisterListener(listener)
                sensorThread.quitSafely() // Important: Kill the background thread
            } catch (ex: Exception) {
                Logger.e("Error cleanup accelerometer listener", ex)
            }
        }
    }
        // ------------------------------------------------
        // CRITICAL FIX: Buffer Strategy
        // ------------------------------------------------
        // We limit the buffer to 50 items (0.5 seconds at 100Hz).
        // If the consumer (ML model) is slow, we DROP_OLDEST.
        // This ensures the system always processes "fresh" data and doesn't lag behind.
        .buffer(
            capacity = 50,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        // Handle unexpected errors in the flow pipeline
        .catch { ex ->
            Logger.e("TotalAccelerometerDataCollector flow error", ex)
        }
        // Ensure downstream operators run on the computation dispatcher
        .flowOn(dispatcher)
}