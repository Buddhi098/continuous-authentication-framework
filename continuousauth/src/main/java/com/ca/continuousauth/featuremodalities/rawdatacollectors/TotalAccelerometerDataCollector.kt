package com.ca.continuousauth.featuremodalities.rawdatacollectors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Accelerometer data collector implementing RawDataCollector interface.
 * Emits each reading as a List<Float> along with its timestamp (in milliseconds),
 * at a specified frequency.
 *
 * If accelerometer sensor is NOT available, emits (0f, 0f, 0f)
 * instead of crashing the data collection system.
 */
class TotalAccelerometerDataCollector(
    context: Context,
    private val frequencyHz: Int = AuthConfigManager.config.sampleCollectionFrequencyHz,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : RawDataCollector<List<Float>> {

    override val modalityName: String = "TOTAL_ACCELEROMETER"

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override fun start(): Flow<Pair<Long, List<Float>>> = callbackFlow {

        // Convert Hz → microseconds (SensorManager requirement)
        val samplingPeriodUs = (1_000_000 / frequencyHz)

        // ------------------------------------------------
        // CASE 1: Accelerometer NOT available
        // ------------------------------------------------
        if (accelerometer == null) {
            Logger.e("Accelerometer not available. Emitting zero values.")

            val zeroJob = launch {
                while (true) {
                    val now = System.currentTimeMillis()
                    trySend(now to listOf(0f, 0f, 0f))
                    delay(1000L / frequencyHz)
                }
            }

            awaitClose { zeroJob.cancel() }
            return@callbackFlow
        }

        // ------------------------------------------------
        // CASE 2: Accelerometer available
        // ------------------------------------------------
        val listener = object : SensorEventListener {

            override fun onSensorChanged(event: SensorEvent) {
                val timestamp = System.currentTimeMillis()

                val rawData = listOf(
                    event.values[0],
                    event.values[1],
                    event.values[2]
                )

                trySend(timestamp to rawData)
                    .onFailure { err ->
                        Logger.e("Failed to emit accelerometer data", err)
                    }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Logger.d("Accelerometer accuracy changed: $accuracy")
            }
        }

        Logger.d("Registering accelerometer listener at ${frequencyHz}Hz")

        try {
            sensorManager.registerListener(
                listener,
                accelerometer,
                samplingPeriodUs
            )
        } catch (ex: Exception) {
            Logger.e("Failed to register accelerometer listener", ex)
        }

        awaitClose {
            try {
                Logger.d("Unregistering accelerometer listener")
                sensorManager.unregisterListener(listener)
            } catch (ex: Exception) {
                Logger.e("Error unregistering accelerometer listener", ex)
            }
        }
    }
        .catch { ex ->
            Logger.e("Accelerometer flow error", ex)
        }
        .flowOn(dispatcher)
}
