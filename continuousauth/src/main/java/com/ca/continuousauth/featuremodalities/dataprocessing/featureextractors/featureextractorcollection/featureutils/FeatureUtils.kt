package com.ca.continuousauth.featuremodalities.dataprocessing.featureextractors.featureextractorcollection.featureutils

import kotlin.math.*

object FeatureUtils {

    fun mean(values: List<Float>): Float =
        if (values.isEmpty()) 0f else values.sum() / values.size

    fun std(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val m = mean(values)
        return sqrt(values.map { (it - m).pow(2) }.sum() / values.size)
    }

    fun rms(values: List<Float>): Float =
        if (values.isEmpty()) 0f else sqrt(values.map { it * it }.sum() / values.size)

    fun energy(values: List<Float>): Float =
        values.map { it * it }.sum()

    fun correlation(a: List<Float>, b: List<Float>): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        val ma = mean(a)
        val mb = mean(b)
        val num = a.zip(b).sumOf { ((it.first - ma) * (it.second - mb)).toDouble() }
        val den = sqrt(
            a.sumOf { (it - ma).pow(2).toDouble() } *
                    b.sumOf { (it - mb).pow(2).toDouble() }
        )
        return if (den == 0.0) 0f else (num / den).toFloat()
    }

    fun magnitude(x: List<Float>, y: List<Float>, z: List<Float>): List<Float> =
        x.indices.map { i -> sqrt(x[i]*x[i] + y[i]*y[i] + z[i]*z[i]) }
}
