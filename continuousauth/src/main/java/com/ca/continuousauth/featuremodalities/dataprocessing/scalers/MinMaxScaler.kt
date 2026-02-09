package com.ca.continuousauth.featuremodalities.dataprocessing.scalers

import android.content.Context
import com.ca.continuousauth.utils.Logger

/**
 * MinMaxScaler scales features to a fixed range [0, 1] with persistent storage. Min and max are
 * loaded from SharedPreferences if available and updated on fitTransform.
 *
 * Thread-safe: All public methods are synchronized.
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
     * Fit the scaler on the data and return the transformed features. Updates SharedPreferences
     * with new min and max.
     *
     * @throws IllegalArgumentException if features is empty, contains empty rows,
     * ```
     *         or has inconsistent feature counts
     * ```
     */
    @Synchronized
    override fun fitTransform(features: List<List<Float>>): List<List<Float>> {
        if (features.isEmpty()) return emptyList()

        val featureCount = features[0].size
        require(featureCount > 0) { "Feature vectors cannot be empty" }
        require(features.all { it.size == featureCount }) {
            "All feature vectors must have the same size ($featureCount)"
        }

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
     *
     * When a feature has zero range (min == max), returns 0.5 (midpoint of [0, 1]).
     *
     * @throws IllegalStateException if the scaler has not been fitted yet
     */
    @Synchronized
    override fun transform(features: List<List<Float>>): List<List<Float>> {
        val min = this.min ?: throw IllegalStateException("Scaler has not been fitted yet.")
        val max = this.max ?: throw IllegalStateException("Scaler has not been fitted yet.")

        return features.map { vec ->
            vec.mapIndexed { i, value ->
                val range = max[i] - min[i]
                if (range == 0f) {
                    // Feature has no variance; return midpoint of [0, 1]
                    0.5f
                } else {
                    (value - min[i]) / range
                }
            }
        }
    }

    @Synchronized
    override fun save() {
        saveToPrefs()
    }

    @Synchronized
    override fun load() {
        loadFromPrefs()
    }

    /** Check if the scaler has been fitted with training data. */
    override fun isFitted(): Boolean = min != null && max != null

    /**
     * Get the fitted min for inspection.
     * @throws IllegalStateException if the scaler has not been fitted yet
     */
    fun getMin(): FloatArray = min ?: throw IllegalStateException("Scaler has not been fitted yet.")

    /**
     * Get the fitted max for inspection.
     * @throws IllegalStateException if the scaler has not been fitted yet
     */
    fun getMax(): FloatArray = max ?: throw IllegalStateException("Scaler has not been fitted yet.")

    /**
     * Load min and max from SharedPreferences if they exist. Handles corrupted data gracefully by
     * logging a warning and resetting state.
     */
    private fun loadFromPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val minStr = prefs.getString(KEY_MIN, null)
        val maxStr = prefs.getString(KEY_MAX, null)

        Logger.d("Loading scaler: min=$minStr, max=$maxStr")

        if (!minStr.isNullOrEmpty() && !maxStr.isNullOrEmpty()) {
            try {
                val minParts = minStr.split(",").filter { it.isNotBlank() }
                val maxParts = maxStr.split(",").filter { it.isNotBlank() }

                if (minParts.isNotEmpty() && maxParts.isNotEmpty() && minParts.size == maxParts.size
                ) {
                    min = minParts.map { it.toFloat() }.toFloatArray()
                    max = maxParts.map { it.toFloat() }.toFloatArray()
                } else {
                    Logger.d(
                            "[WARN] Scaler prefs have mismatched sizes: min=${minParts.size}, max=${maxParts.size}"
                    )
                    min = null
                    max = null
                }
            } catch (e: NumberFormatException) {
                Logger.d("[WARN] Failed to parse scaler prefs, resetting: ${e.message}")
                min = null
                max = null
            }
        }
    }

    /** Save min and max to SharedPreferences. */
    private fun saveToPrefs() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_MIN, min?.joinToString(","))
        editor.putString(KEY_MAX, max?.joinToString(","))
        editor.apply()
        Logger.d("Saved scaler: min=${min?.joinToString(",")}, max=${max?.joinToString(",")}")
    }
}
