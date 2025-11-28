package com.ca.continuousauth.core.featureextractor.sensor

import android.content.Context
import android.hardware.*
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlin.math.roundToInt

class SensorDataCollector(
    private val context: Context,
    private val enableLogging: Boolean = false
) : SensorEventListener {

    private val batchSize = AuthConfigManager.config.windowSize
    private val overlapRatio = AuthConfigManager.config.windowOverlapRatio
    private val sampleFrequencyHz = AuthConfigManager.config.sampleCollectionFrequencyHz

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var accelData: FloatArray? = null
    private var gyroData: FloatArray? = null
    private var gravityData: FloatArray? = null

    private val buffer = mutableListOf<FloatArray>() // 9D vector buffer
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val sampleIntervalNs = (1_000_000_000L / sampleFrequencyHz)
    private var lastSampleTime = 0L

    private val overlapCount = (batchSize * overlapRatio).roundToInt()

    private val internalFlow = MutableSharedFlow<List<FloatArray>>(extraBufferCapacity = 10)

    /**
     * Public batch Flow
     */
    val batchFlow: Flow<List<FloatArray>> = callbackFlow {

        if (enableLogging) Logger.d("SensorDataCollector → Registering sensors")

        sensorManager.registerListener(
            this@SensorDataCollector,
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
            SensorManager.SENSOR_DELAY_FASTEST
        )
        sensorManager.registerListener(
            this@SensorDataCollector,
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
            SensorManager.SENSOR_DELAY_FASTEST
        )
        sensorManager.registerListener(
            this@SensorDataCollector,
            sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY),
            SensorManager.SENSOR_DELAY_FASTEST
        )

        internalFlow.collect { batch ->
            send(batch)
        }

        awaitClose {
            if (enableLogging) Logger.d("SensorDataCollector → Unregistering sensors")
            sensorManager.unregisterListener(this@SensorDataCollector)
        }
    }.shareIn(scope, SharingStarted.Lazily, 0)

    fun stop() {
        if (enableLogging) Logger.d("SensorDataCollector → stop() called")
        sensorManager.unregisterListener(this)
        scope.cancel()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> accelData = event.values.clone()
            Sensor.TYPE_GYROSCOPE -> gyroData = event.values.clone()
            Sensor.TYPE_GRAVITY -> gravityData = event.values.clone()
        }

        val now = event.timestamp
        if (now - lastSampleTime < sampleIntervalNs) return
        lastSampleTime = now

        val sample = FloatArray(9)
        accelData?.let { sample[0] = it[0]; sample[1] = it[1]; sample[2] = it[2] }
        gyroData?.let { sample[3] = it[0]; sample[4] = it[1]; sample[5] = it[2] }
        gravityData?.let { sample[6] = it[0]; sample[7] = it[1]; sample[8] = it[2] }

        buffer.add(sample)

        if (enableLogging) {
            Logger.d("SensorDataCollector → Sample collected (${buffer.size}/$batchSize)")
        }

        // batch ready?
        if (buffer.size >= batchSize) {
            val batch = buffer.take(batchSize)

            if (enableLogging) {
                Logger.d("────────────────────────────────────────────")
                Logger.d("📦 SensorDataCollector → Batch Ready (size=$batchSize)")
                batch.forEachIndexed { index, s ->
                    Logger.d("  #$index → ${s.joinToString(prefix = "[", postfix = "]")}")
                }
                Logger.d("────────────────────────────────────────────")
            }

            scope.launch { internalFlow.emit(batch) }

            val keep = overlapCount
            val removeCount = batchSize - keep

            repeat(removeCount) {
                if (buffer.isNotEmpty()) buffer.removeAt(0)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
