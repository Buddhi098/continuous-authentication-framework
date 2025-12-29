package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import kotlin.math.*

class MicroOrientationFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        if (window.isEmpty()) return emptyList()

        val rolls = mutableListOf<Float>()
        val pitches = mutableListOf<Float>()
        val magnitudes = mutableListOf<Float>()

        // -------------------------------
        // 1️⃣ Compute roll & pitch angles
        // -------------------------------
        for ((_, values) in window) {
            if (values.size < 3) continue

            val ax = values[0]
            val ay = values[1]
            val az = values[2]

            val roll = atan2(ay, az)
            val pitch = atan2(-ax, sqrt(ay * ay + az * az))

            rolls.add(roll)
            pitches.add(pitch)
            magnitudes.add(sqrt(ax * ax + ay * ay + az * az))
        }

        if (rolls.size < 2) return emptyList()

        // -------------------------------
        // 2️⃣ Statistical helpers
        // -------------------------------
        fun mean(x: List<Float>) = x.sum() / x.size

        fun std(x: List<Float>): Double {
            val m = mean(x)
            return sqrt(x.sumOf { ((it - m).toDouble()).pow(2) } / x.size)
        }

        fun meanAbsDiff(x: List<Float>): Float {
            var sum = 0f
            for (i in 1 until x.size) {
                sum += abs(x[i] - x[i - 1])
            }
            return sum / (x.size - 1)
        }

        fun correlation(x: List<Float>, y: List<Float>): Float {
            val mx = mean(x)
            val my = mean(y)
            var num = 0f
            var dx = 0f
            var dy = 0f
            for (i in x.indices) {
                val a = x[i] - mx
                val b = y[i] - my
                num += a * b
                dx += a * a
                dy += b * b
            }
            return if (dx == 0f || dy == 0f) 0f else num / sqrt(dx * dy)
        }

        // -------------------------------
        // 3️⃣ Feature computation
        // -------------------------------
        val meanRoll = mean(rolls)
        val stdRoll = std(rolls)
        val meanPitch = mean(pitches)
        val stdPitch = std(pitches)

        val rollDiffMean = meanAbsDiff(rolls)
        val pitchDiffMean = meanAbsDiff(pitches)

        val rollPitchCorr = correlation(rolls, pitches)

        val orientationEnergy =
            rolls.zip(pitches).sumOf { (r, p) -> (r * r + p * p).toDouble() }
                .toFloat() / rolls.size

        val meanG = mean(magnitudes)
        val stdG = std(magnitudes)

        // -------------------------------
        // 4️⃣ Final feature vector
        // -------------------------------
        return listOf(
            meanRoll,
            stdRoll,
            meanPitch,
            stdPitch,
            rollDiffMean,
            pitchDiffMean,
            rollPitchCorr,
            orientationEnergy,
            meanG,
            stdG
        ) as List<Float>
    }
}
