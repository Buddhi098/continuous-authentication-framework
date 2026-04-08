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

class GyroscopeDataCollector(
    context: Context,
    private val frequencyHz: Int = AuthConfigManager.config.sampleCollectionFrequencyHz,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : RawDataCollector<List<Float>> {

    override val modalityName: String = "GYROSCOPE"

    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gyroscope: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    override fun start(): Flow<Pair<Long, List<Float>>> = callbackFlow {

        // Convert Hz → microseconds (Hint for the OS)
        val samplingPeriodUs = (1_000_000 / frequencyHz)

        // CASE 1: Gyroscope NOT available → Fallback
        if (gyroscope == null) {
            Logger.e("Gyroscope not available. Emitting zero values.")
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

        // CASE 2: Gyroscope available → Real Data
        // Moves sensor event delivery off the Main UI thread.
        val sensorThread = HandlerThread("GyroscopeWorkerThread")
        sensorThread.start()
        val sensorHandler = Handler(sensorThread.looper)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val timestamp = event.timestamp

                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]

                val rawData = listOf(x, y, z)

                val result = trySend(timestamp to rawData)

                if (result.isFailure) {
                     Logger.e("Gyro buffer overflow: Packet dropped")
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            }
        }

        Logger.d("Registering gyroscope listener at ${frequencyHz}Hz on background thread")

        try {
            sensorManager.registerListener(
                listener,
                gyroscope,
                samplingPeriodUs,
                sensorHandler
            )
        } catch (ex: Exception) {
            Logger.e("Failed to register gyroscope listener", ex)
            close(ex)
        }

        // Cleanup when collection stops
        awaitClose {
            try {
                Logger.d("Unregistering gyroscope listener")
                sensorManager.unregisterListener(listener)
                sensorThread.quitSafely()
            } catch (ex: Exception) {
                Logger.e("Error while unregistering gyroscope listener", ex)
            }
        }
    }
        // This ensures the system always processes fresh data and prevents latency accumulation.
        .buffer(
            capacity = 50,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        .catch { ex ->
            Logger.e("Gyroscope flow error", ex)
        }
        .flowOn(dispatcher)
}