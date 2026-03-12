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

class MagnetometerDataCollector(
    context: Context,
    private val frequencyHz: Int = AuthConfigManager.config.sampleCollectionFrequencyHz,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : RawDataCollector<List<Float>> {

    override val modalityName: String = "MAGNETOMETER"

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val magnetometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    override fun start(): Flow<Pair<Long, List<Float>>> = callbackFlow {

        // Convert Hz → microseconds (Hint for the OS)
        val samplingPeriodUs = (1_000_000 / frequencyHz)

        // ------------------------------------------------
        // CASE 1: Magnetometer NOT available → Fallback
        // ------------------------------------------------
        if (magnetometer == null) {
            Logger.e("Magnetometer not available. Emitting zero values.")
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
        // CASE 2: Magnetometer available → Real Data
        // ------------------------------------------------

        // Calculate the minimum period in nanoseconds to enforce the frequency
        val minPeriodNs = 1_000_000_000L / frequencyHz
        var lastTimestampNs = 0L

        // OPTIMIZATION: Background HandlerThread
        val sensorThread = HandlerThread("MagnetometerWorkerThread")
        sensorThread.start()
        val sensorHandler = Handler(sensorThread.looper)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val timestamp = event.timestamp

                // Enforce exact requested frequency (drop events arriving too early)
                if (timestamp - lastTimestampNs < minPeriodNs) return
                lastTimestampNs = timestamp

                // Copy values immediately
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val rawData = listOf(x, y, z)

                val result = trySend(timestamp to rawData)
                if (result.isFailure) {
                    Logger.e("Magnetometer buffer overflow: Packet dropped")
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // No-op
            }
        }

        Logger.d("Registering magnetometer listener at ${frequencyHz}Hz on background thread")

        try {
            sensorManager.registerListener(
                listener,
                magnetometer,
                samplingPeriodUs,
                sensorHandler
            )
        } catch (ex: Exception) {
            Logger.e("Failed to register magnetometer listener", ex)
            close(ex)
        }

        // Cleanup when collection stops
        awaitClose {
            try {
                Logger.d("Unregistering magnetometer listener")
                sensorManager.unregisterListener(listener)
                sensorThread.quitSafely()
            } catch (ex: Exception) {
                Logger.e("Error while unregistering magnetometer listener", ex)
            }
        }
    }
        .buffer(
            capacity = 50,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        .catch { ex ->
            Logger.e("Magnetometer flow error", ex)
        }
        .flowOn(dispatcher)
}