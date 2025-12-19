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
 * Magnetometer data collector implementing RawDataCollector interface.
 * Emits each reading as a List<Float> along with its timestamp (in milliseconds),
 * at a specified frequency.
 *
 * If magnetometer sensor is NOT available, emits (0f, 0f, 0f)
 * instead of crashing the data collection system.
 */
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

        val minIntervalMs = (1000 / frequencyHz).toLong()

        // ------------------------------------------------
        // CASE 1: Magnetometer NOT available → emit zeros
        // ------------------------------------------------
        if (magnetometer == null) {
            Logger.e("Magnetometer not available. Emitting zero values.")

            val zeroEmissionJob = this@callbackFlow.launch {
                while (true) {
                    val now = System.currentTimeMillis()
                    trySend(now to listOf(0f, 0f, 0f))
                        .onFailure { err ->
                            Logger.e("Failed to emit zero magnetometer data", err)
                        }
                    delay(minIntervalMs)
                }
            }

            awaitClose {
                Logger.d("Stopping zero magnetometer emission")
                zeroEmissionJob.cancel()
            }

            return@callbackFlow
        }

        // ------------------------------------------------
        // CASE 2: Magnetometer available
        // ------------------------------------------------
        var lastEmissionTime = 0L

        val listener = object : SensorEventListener {

            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null) return

                val currentTimeMs = System.currentTimeMillis()
                if (currentTimeMs - lastEmissionTime >= minIntervalMs) {
                    lastEmissionTime = currentTimeMs

                    val rawData = listOf(
                        event.values[0],
                        event.values[1],
                        event.values[2]
                    )

                    trySend(currentTimeMs to rawData)
                        .onFailure { err ->
                            Logger.e("Failed to emit magnetometer data", err)
                        }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Logger.d("Magnetometer accuracy changed: $accuracy")
            }
        }

        Logger.d("Registering magnetometer listener at ${frequencyHz}Hz")

        try {
            sensorManager.registerListener(
                listener,
                magnetometer,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        } catch (ex: Exception) {
            Logger.e("Failed to register magnetometer listener", ex)
        }

        awaitClose {
            try {
                Logger.d("Unregistering magnetometer listener")
                sensorManager.unregisterListener(listener)
            } catch (ex: Exception) {
                Logger.e("Error while unregistering magnetometer listener", ex)
            }
        }
    }
        .catch { ex ->
            // Defensive: do NOT crash upstream collectors
            Logger.e("Magnetometer flow error", ex)
        }
        .flowOn(dispatcher)
}
