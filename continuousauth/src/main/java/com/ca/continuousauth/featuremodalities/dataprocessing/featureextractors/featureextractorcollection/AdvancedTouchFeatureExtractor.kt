package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection

import com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.FeatureExtractor
import kotlin.math.sqrt

class AdvancedTouchFeatureExtractor : FeatureExtractor {

    override fun extract(window: List<Pair<Long, List<Float>>>): List<Float> {
        if (window.isEmpty()) return List(10) { 0f }

        val n = window.size
        val dxList = mutableListOf<Float>()
        val dyList = mutableListOf<Float>()
        val speedList = mutableListOf<Float>()
        val pressureList = mutableListOf<Float>()
        val dtList = mutableListOf<Float>()

        var lastX = window.first().second.getOrElse(0) { 0f }
        var lastY = window.first().second.getOrElse(1) { 0f }
        var lastTime: Float = window.first().first.toFloat()
        var lastSpeed = 0f

        for ((timestamp, vector) in window) {
            val x = vector.getOrElse(0) { 0f }
            val y = vector.getOrElse(1) { 0f }
            val pressure = vector.getOrElse(4) { 0f } // avgPressure

            val dt = (timestamp - lastTime).coerceAtLeast(1F).toFloat() / 1000f
            dtList.add(dt)

            val dx = x - lastX
            val dy = y - lastY
            dxList.add(dx)
            dyList.add(dy)

            val speed = sqrt(dx * dx + dy * dy) / dt
            speedList.add(speed)

            lastX = x
            lastY = y
            lastTime = timestamp.toFloat()
            lastSpeed = speed

            pressureList.add(pressure)
        }

        // Feature calculations
        fun mean(list: List<Float>) = if (list.isEmpty()) 0f else list.sum() / list.size
        fun std(list: List<Float>, mean: Float) =
            if (list.isEmpty()) 0f else sqrt(list.map { (it - mean) * (it - mean) }.sum() / list.size)
        fun max(list: List<Float>) = list.maxOrNull() ?: 0f
        fun min(list: List<Float>) = list.minOrNull() ?: 0f

        val meanDx = mean(dxList)
        val stdDx = std(dxList, meanDx)
        val meanDy = mean(dyList)
        val stdDy = std(dyList, meanDy)

        val meanSpeed = mean(speedList)
        val stdSpeed = std(speedList, meanSpeed)
        val maxSpeed = max(speedList)
        val minSpeed = min(speedList)

        val meanPressure = mean(pressureList)
        val stdPressure = std(pressureList, meanPressure)

        val totalDuration = dtList.sum()

        // Optional: gesture compactness (how straight the path is)
        val totalDistance = dxList.zip(dyList)
            .sumOf { (dx, dy) -> sqrt((dx * dx + dy * dy).toDouble()) }  // sumOf knows this is Double
            .toFloat()
        val netDx = dxList.sum()
        val netDy = dyList.sum()
        val straightness = sqrt(netDx * netDx + netDy * netDy) / totalDistance.coerceAtLeast(1e-3f)

        return listOf(
            meanDx, stdDx,
            meanDy, stdDy,
            meanSpeed, stdSpeed,
            maxSpeed, minSpeed,
            meanPressure, stdPressure,
            totalDuration,
            straightness
        )
    }
}
