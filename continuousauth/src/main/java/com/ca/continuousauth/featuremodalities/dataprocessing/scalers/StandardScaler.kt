package com.ca.continuousauth.featuremodalities.dataprocessing.scalers

import android.content.Context
import com.ca.continuousauth.utils.Logger
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * StandardScaler for Z-score normalization with persistent storage in SharedPreferences. Scales
 * features to have mean = 0 and std = 1. Mean and std are loaded from SharedPreferences if
 * available and updated on fitTransform.
 *
 * Thread-safe: All public methods are synchronized.
 */
class StandardScaler(private val context: Context, private val prefsName: String) : Scaler {

    private var mean: FloatArray? = null
    private var std: FloatArray? = null

    private val KEY_MEAN = "mean"
    private val KEY_STD = "std"

    init {
        loadFromPrefs()
    }

    /**
     * Fit the scaler on the data and return the transformed features. Updates SharedPreferences
     * with new mean and std.
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

        mean = FloatArray(featureCount)
        std = FloatArray(featureCount)

        // Compute mean per feature
        for (i in 0 until featureCount) {
            mean!![i] = features.map { it[i] }.average().toFloat()
        }

        // Compute population standard deviation per feature
        for (i in 0 until featureCount) {
            val m = mean!![i]
            std!![i] = sqrt(features.map { (it[i] - m).pow(2) }.average()).toFloat()
        }

        saveToPrefs()

        return transform(features)
    }

    /**
     * Transform new data using previously computed mean and std.
     *
     * When a feature has zero standard deviation, returns 0 (the mean-centered value).
     *
     * @throws IllegalStateException if the scaler has not been fitted yet
     */
    @Synchronized
    override fun transform(features: List<List<Float>>): List<List<Float>> {
        val mean = this.mean ?: throw IllegalStateException("Scaler has not been fitted yet.")
        val std = this.std ?: throw IllegalStateException("Scaler has not been fitted yet.")

        return features.map { vec ->
            vec.mapIndexed { i, value ->
                if (std[i] == 0f) {
                    // Feature has no variance; return 0 (already at mean)
                    0f
                } else {
                    (value - mean[i]) / std[i]
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
    override fun isFitted(): Boolean = mean != null && std != null

    /**
     * Get the fitted mean for inspection.
     * @throws IllegalStateException if the scaler has not been fitted yet
     */
    fun getMean(): FloatArray =
            mean ?: throw IllegalStateException("Scaler has not been fitted yet.")

    /**
     * Get the fitted standard deviation for inspection.
     * @throws IllegalStateException if the scaler has not been fitted yet
     */
    fun getStd(): FloatArray = std ?: throw IllegalStateException("Scaler has not been fitted yet.")

    /**
     * Load mean and std from SharedPreferences if they exist. Handles corrupted data gracefully by
     * logging a warning and resetting state.
     */
    private fun loadFromPrefs() {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val meanStr = prefs.getString(KEY_MEAN, null)
        val stdStr = prefs.getString(KEY_STD, null)

        Logger.d("Loading scaler: mean=$meanStr, std=$stdStr")

        if (!meanStr.isNullOrEmpty() && !stdStr.isNullOrEmpty()) {
            try {
                val meanParts = meanStr.split(",").filter { it.isNotBlank() }
                val stdParts = stdStr.split(",").filter { it.isNotBlank() }

                if (meanParts.isNotEmpty() &&
                                stdParts.isNotEmpty() &&
                                meanParts.size == stdParts.size
                ) {
                    mean = meanParts.map { it.toFloat() }.toFloatArray()
                    std = stdParts.map { it.toFloat() }.toFloatArray()
                } else {
                    Logger.d(
                            "[WARN] Scaler prefs have mismatched sizes: mean=${meanParts.size}, std=${stdParts.size}"
                    )
                    mean = null
                    std = null
                }
            } catch (e: NumberFormatException) {
                Logger.d("[WARN] Failed to parse scaler prefs, resetting: ${e.message}")
                mean = null
                std = null
            }
        }
    }

    /** Save mean and std to SharedPreferences. */
    private fun saveToPrefs() {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.putString(KEY_MEAN, mean?.joinToString(","))
        editor.putString(KEY_STD, std?.joinToString(","))
        editor.apply()
        Logger.d("Saved scaler: mean=${mean?.joinToString(",")}, std=${std?.joinToString(",")}")
    }
}
