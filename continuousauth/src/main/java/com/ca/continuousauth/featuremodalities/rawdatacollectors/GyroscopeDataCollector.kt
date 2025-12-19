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
 * Gyroscope data collector implementing RawDataCollector interface.
 * Emits each reading as a List<Float> along with its timestamp (in milliseconds),
 * at a specified frequency.
 *
 * If gyroscope sensor is NOT available, emits (0f, 0f, 0f) continuously
 * instead of crashing the data collection pipeline.
 */
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

        val minIntervalMs = (1000 / frequencyHz).toLong()

        // ------------------------------------------------
        // CASE 1: Gyroscope NOT available → emit zeros
        // ------------------------------------------------
        if (gyroscope == null) {
            Logger.e("Gyroscope not available. Emitting zero values.")

            val zeroEmissionJob = this@callbackFlow.launch {
                while (true) {
                    val now = System.currentTimeMillis()
                    trySend(now to listOf(0f, 0f, 0f))
                        .onFailure { err ->
                            Logger.e("Failed to emit zero gyroscope data", err)
                        }
                    delay(minIntervalMs)
                }
            }

            awaitClose {
                Logger.d("Stopping zero gyroscope emission")
                zeroEmissionJob.cancel()
            }

            return@callbackFlow
        }

        // ------------------------------------------------
        // CASE 2: Gyroscope available
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
                            Logger.e("Failed to emit gyroscope data", err)
                        }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Logger.d("Gyroscope accuracy changed: $accuracy")
            }
        }

        Logger.d("Registering gyroscope listener at ${frequencyHz}Hz")

        try {
            sensorManager.registerListener(
                listener,
                gyroscope,
                SensorManager.SENSOR_DELAY_FASTEST
            )
        } catch (ex: Exception) {
            Logger.e("Failed to register gyroscope listener", ex)
        }

        awaitClose {
            try {
                Logger.d("Unregistering gyroscope listener")
                sensorManager.unregisterListener(listener)
            } catch (ex: Exception) {
                Logger.e("Error while unregistering gyroscope listener", ex)
            }
        }
    }
        .catch { ex ->
            // Defensive: never crash upstream collectors
            Logger.e("Gyroscope flow error", ex)
        }
        .flowOn(dispatcher)
}
