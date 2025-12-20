package com.ca.continuousauth.featuremodalities.dataprocessing.scalers

import android.content.Context
import com.ca.continuousauth.utils.Logger

/**
 * MinMaxScaler scales features to a fixed range [0, 1] with persistent storage.
 * Min and max are loaded from SharedPreferences if available and updated on fitTransform.
 */
class MinMaxScaler(private val context: Context) : Scaler {

    private var min: FloatArray? = null
    private var max: FloatArray? = null

    private val PREFS_NAME = "minmax_scaler_prefs"
    private val KEY_MIN = "min"
    private val KEY_MAX = "max"

    init {
        loadFromPrefs()
    }

    /**
     * Fit the scaler on the data and return the transformed features.
     * Updates SharedPreferences with new min and max.
     */
    override fun fitTransform(features: List<List<Float>>): List<List<Float>> {
        if (features.isEmpty()) return emptyList()

        val featureCount = features[0].size
        min = FloatArray(featureCount)
        max = FloatArray(featureCount)

        for (i in 0 until featureCount) {
            val values = features.map { it[i] }
            min!![i] = values.minOrNull() ?: 0f
            max!![i] = values.maxOrNull() ?: 1f
        }

        saveToPrefs()
        return transform(features)
    }

    /**
     * Transform new data using previously computed min and max.
     */
    override fun transform(features: List<List<Float>>): List<List<Float>> {
        val min = this.min ?: throw IllegalStateException("Scaler has not been fitted yet.")
        val max = this.max ?: throw IllegalStateException("Scaler has not been fitted yet.")

        return features.map { vec ->
            vec.mapIndexed { i, value ->
                if (max[i] - min[i] == 0f) 0f else (value - min[i]) / (max[i] - min[i])
            }
        }
    }

    override fun save() {
        saveToPrefs()
    }

    override fun load() {
        loadFromPrefs()
    }


    /**
     * Get the fitted min and max for inspection.
     */
    fun getMin(): FloatArray = min ?: throw IllegalStateException("Scaler has not been fitted yet.")
    fun getMax(): FloatArray = max ?: throw IllegalStateException("Scaler has not been fitted yet.")

    /**
     * Load min and max from SharedPreferences if they exist.
     */
    private fun loadFromPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val minStr = prefs.getString(KEY_MIN, null)
        val maxStr = prefs.getString(KEY_MAX, null)

        Logger.d("Loaded min: $minStr, max: $maxStr")
        if (!minStr.isNullOrEmpty() && !maxStr.isNullOrEmpty()) {
            min = minStr.split(",").map { it.toFloat() }.toFloatArray()
            max = maxStr.split(",").map { it.toFloat() }.toFloatArray()
        }
    }

    /**
     * Save min and max to SharedPreferences.
     */
    private fun saveToPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_MIN, min?.joinToString(","))
        editor.putString(KEY_MAX, max?.joinToString(","))
        editor.apply()
        Logger.d("Saved min: ${min?.joinToString(",")}, max: ${max?.joinToString(",")}")
    }
}
