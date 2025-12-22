package com.ca.continuousauth.authengine

class AdaptiveScoreDenoiser(
    private val alpha: Float = 0.8f,
    private val bootstrapCount: Int = 20,
    private val historySize: Int = 15,
    private val madMultiplier: Float = 3.0f,
    private val safeMinThreshold: Float = 0.05f,
    private val minThresholdWindowSize: Int = 10
) {

    private val bootstrapBuffer = mutableListOf<Float>()
    private val recentScores = ArrayDeque<Float>()

    private var lastScore: Float? = null
    private var bootstrapped = false

    fun denoise(rawScore: Float): Float {

        // -----------------------------
        // Phase 1: Bootstrap (Median)
        // -----------------------------
        if (!bootstrapped) {
            bootstrapBuffer.add(rawScore)

            if (bootstrapBuffer.size < bootstrapCount) {
                return rawScore
            }

            bootstrapBuffer.sort()
            val median = bootstrapBuffer[bootstrapBuffer.size / 2]

            lastScore = median
            bootstrapped = true
            bootstrapBuffer.clear()

            recentScores.add(median)
            return median
        }

        val prev = lastScore ?: rawScore

        // -----------------------------
        // Phase 2.1: Dynamic Spike Threshold
        // -----------------------------
        val threshold = computeDynamicThreshold()

        val delta = rawScore - prev
        val spikeFree = when {
            delta > threshold  -> prev + threshold
            delta < -threshold -> prev - threshold
            else               -> rawScore
        }

        // -----------------------------
        // Phase 2.2: EMA smoothing
        // -----------------------------
        val denoised = alpha * spikeFree + (1f - alpha) * prev

        // Update state
        lastScore = denoised
        updateHistory(denoised)

        return denoised
    }

    // -----------------------------
    // Robust dynamic threshold (MAD)
    // -----------------------------
    private fun computeDynamicThreshold(): Float {
        if (recentScores.size < minThresholdWindowSize) {
            return safeMinThreshold // Safe minimum threshold
        }

        val median = recentScores.sorted().let { it[it.size / 2] }
        val mad = recentScores
            .map { kotlin.math.abs(it - median) }
            .sorted()
            .let { it[it.size / 2] }

        // Small epsilon prevents zero threshold
        return madMultiplier * (mad + 1e-6f)
    }

    private fun updateHistory(value: Float) {
        if (recentScores.size >= historySize) {
            recentScores.removeFirst()
        }
        recentScores.addLast(value)
    }

    fun reset() {
        bootstrapBuffer.clear()
        recentScores.clear()
        lastScore = null
        bootstrapped = false
    }
}
