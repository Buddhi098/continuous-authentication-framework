package com.ca.continuousauth.featuremodalities.dataprocessing.scalers

import android.content.Context
import com.ca.continuousauth.utils.Logger
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * StandardScaler for Z-score normalization with persistent storage in SharedPreferences.
 * Scales features to have mean = 0 and std = 1.
 * Mean and std are loaded from SharedPreferences if available and updated on fitTransform.
 */
class StandardScaler(private val context: Context) : Scaler {

    private var mean: FloatArray? = null
    private var std: FloatArray? = null

    private val PREFS_NAME = "standard_scaler_prefs"
    private val KEY_MEAN = "mean"
    private val KEY_STD = "std"

    init {
        loadFromPrefs()
    }

    /**
     * Fit the scaler on the data and return the transformed features.
     * Updates SharedPreferences with new mean and std.
     */
    override fun fitTransform(features: List<List<Float>>): List<List<Float>> {
        if (features.isEmpty()) return emptyList()

        val featureCount = features[0].size
        mean = FloatArray(featureCount)
        std = FloatArray(featureCount)

        // Compute mean per feature
        for (i in 0 until featureCount) {
            mean!![i] = features.map { it[i] }.average().toFloat()
        }

        // Compute standard deviation per feature
        for (i in 0 until featureCount) {
            val m = mean!![i]
            std!![i] = sqrt(features.map { (it[i] - m).pow(2) }.average()).toFloat()
        }

        saveToPrefs()

        return transform(features)
    }

    /**
     * Transform new data using previously computed mean and std.
     */
    override fun transform(features: List<List<Float>>): List<List<Float>> {
        val mean = this.mean ?: throw IllegalStateException("Scaler has not been fitted yet.")
        val std = this.std ?: throw IllegalStateException("Scaler has not been fitted yet.")

        return features.map { vec ->
            vec.mapIndexed { i, value ->
                if (std[i] == 0f) 0f else (value - mean[i]) / std[i]
            }
        }
    }

    /**
     * Get the fitted mean and std for inspection.
     */
    fun getMean(): FloatArray = mean ?: throw IllegalStateException("Scaler has not been fitted yet.")
    fun getStd(): FloatArray = std ?: throw IllegalStateException("Scaler has not been fitted yet.")

    /**
     * Load mean and std from SharedPreferences if they exist.
     */
    private fun loadFromPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val meanStr = prefs.getString(KEY_MEAN, null)
        val stdStr = prefs.getString(KEY_STD, null)
        Logger.d("Loaded mean: $meanStr, std: $stdStr")
        if (!meanStr.isNullOrEmpty() && !stdStr.isNullOrEmpty()) {
            mean = meanStr.split(",").map { it.toFloat() }.toFloatArray()
            std = stdStr.split(",").map { it.toFloat() }.toFloatArray()
        }
    }
    override fun save() {
        saveToPrefs()
    }

    override fun load() {
        loadFromPrefs()
    }
    /**
     * Save mean and std to SharedPreferences.
     */
    private fun saveToPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_MEAN, mean?.joinToString(","))
        editor.putString(KEY_STD, std?.joinToString(","))
        editor.apply()
    }
}
