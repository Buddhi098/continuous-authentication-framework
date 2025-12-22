package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import com.ca.continuousauth.utils.Logger
import kotlin.math.*

class MicroMovementFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        return try {
            if (window.isEmpty()) return emptyList()

            // 1️⃣ Convert to magnitude signal (FIXED)
            val rawMag: List<Double> = window.map { (_, values) ->
                sqrt(values.sumOf { (it * it).toDouble() })
            }

            // 2️⃣ Remove gravity
            val mag = removeGravity(rawMag)

            // 3️⃣ Derivatives
            val velocity = firstDerivative(mag)
            val acceleration = firstDerivative(velocity)
            val jerk = firstDerivative(acceleration)

            // 4️⃣ Motion-only features
            listOf(
                meanAbs(velocity),
                rms(velocity),
                std(velocity),

                meanAbs(acceleration),
                rms(acceleration),

                meanAbs(jerk),
                rms(jerk),

                signalEnergy(mag),
                zeroCrossingRate(mag)
            )

        } catch (ex: Exception) {
            Logger.e("MotionDynamicsFeatureExtractor error", ex)
            emptyList()
        }
    }

    // ------------------------
    // Gravity removal
    // ------------------------

    private fun removeGravity(values: List<Double>, alpha: Float = 0.9f): List<Float> {
        if (values.isEmpty()) return emptyList()

        val gravity = MutableList(values.size) { 0f }
        gravity[0] = values[0].toFloat()

        for (i in 1 until values.size) {
            gravity[i] =
                (alpha * gravity[i - 1] + (1 - alpha) * values[i]).toFloat()
        }

        return values.mapIndexed { i, v -> (v - gravity[i]).toFloat() }
    }

    // ------------------------
    // Derivatives
    // ------------------------

    private fun firstDerivative(values: List<Float>): List<Float> {
        if (values.size < 2) return emptyList()
        return values.zipWithNext { a, b -> b - a }
    }

    // ------------------------
    // Core metrics
    // ------------------------

    private fun meanAbs(values: List<Float>): Float =
        if (values.isEmpty()) 0f else values.map { abs(it) }.average().toFloat()

    private fun rms(values: List<Float>): Float =
        if (values.isEmpty()) 0f else sqrt(values.map { it * it }.average()).toFloat()

    private fun std(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average()
        return sqrt(values.map { (it - mean).pow(2) }.average()).toFloat()
    }

    private fun signalEnergy(values: List<Float>): Float =
        if (values.isEmpty()) 0f
        else values.sumOf { (it * it).toDouble() }.toFloat()   // ✅ FIXED

    private fun zeroCrossingRate(values: List<Float>): Float {
        if (values.size < 2) return 0f
        var count = 0
        for (i in 1 until values.size) {
            if (values[i - 1] * values[i] < 0) count++
        }
        return count.toFloat() / (values.size - 1)
    }
}
