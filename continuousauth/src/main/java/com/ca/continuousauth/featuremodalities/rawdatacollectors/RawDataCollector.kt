package com.ca.continuousauth.featuremodalities.rawdatacollectors

import kotlinx.coroutines.flow.Flow

/**
 * Base contract for all raw data collectors in the ContinuousAuth framework.
 *
 * Modalities must implement this interface to provide raw sensor or event data.
 *
 * T = the raw data type emitted by this collector (e.g. Triple<Float, Float, Float>, TouchEventData)
 */
interface RawDataCollector<T> {

    /**
     * Begins producing raw data as a cold Flow.
     *
     * Each emitted value is paired with a timestamp (in milliseconds) representing
     * when the sensor/event reading occurred.
     *
     * - Collection should start sensor/event listeners.
     * - Flow cancellation must clean up listeners automatically.
     *
     * @return Flow<Pair<Long, T>> emitting timestamped raw data objects.
     */
    fun start(): Flow<Pair<Long, T>>

    /**
     * Indicates what modality this collector belongs to (e.g. "ACCELEROMETER", "TOUCH", "IMU").
     */
    val modalityName: String
}
