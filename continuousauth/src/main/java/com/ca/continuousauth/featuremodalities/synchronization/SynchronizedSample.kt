package com.ca.continuousauth.featuremodalities.synchronization

/**
 * A single time-aligned sample containing data from all three motion sensors.
 *
 * Each sample is aligned to a uniform time grid so that gyro, accelerometer,
 * and magnetometer readings share the same logical timestamp. Values are
 * obtained via linear interpolation between the two nearest raw readings.
 *
 * @property timestamp Grid-aligned timestamp in nanoseconds
 * @property gyro      Gyroscope [x, y, z] in rad/s
 * @property accel     Total accelerometer [x, y, z] in m/s²
 * @property magno     Magnetometer [x, y, z] in µT
 */
data class SynchronizedSample(
    val timestamp: Long,
    val gyro: List<Float>,
    val accel: List<Float>
)
