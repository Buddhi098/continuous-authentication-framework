package com.ca.continuousauth.featuremodalities.dataprocessing.scalers

import android.content.Context
import com.ca.continuousauth.security.SecureModelStorage
import com.ca.continuousauth.utils.Logger
import java.io.File

/**
 * MinMaxScaler scales features to a fixed range [0, 1] with persistent encrypted storage.
 * Min and max are loaded from an encrypted file if available and updated on fitTransform.
 *
 * Storage: Parameters are encrypted via [SecureModelStorage] using AES-256-GCM with
 * HMAC-SHA256 integrity verification. Plaintext never touches disk.
 *
 * Thread-safe: All public methods are synchronized.
 */
class MinMaxScaler(private val context: Context, private val prefsName: String) : Scaler {

    private var min: FloatArray? = null
    private var max: FloatArray? = null

    /** Encrypted file for scaler parameters — replaces legacy SharedPreferences */
    private val encryptedFile: File
        get() = File(context.filesDir, "${prefsName}.enc")

    /** Legacy SharedPreferences name for one-time migration */
    private val KEY_MIN = "min"
    private val KEY_MAX = "max"

    init {
        migrateFromPrefsIfNeeded()
        loadFromEncrypted()
    }

    /**
     * Fit the scaler on the data and return the transformed features. Saves encrypted
     * parameters to disk.
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

        // Compute min/max in a single pass — avoids creating featureCount temp lists
        val localMin = FloatArray(featureCount) { Float.MAX_VALUE }
        val localMax = FloatArray(featureCount) { -Float.MAX_VALUE }

        for (sample in features) {
            for (i in 0 until featureCount) {
                val v = sample[i]
                if (v < localMin[i]) localMin[i] = v
                if (v > localMax[i]) localMax[i] = v
            }
        }

        min = localMin
        max = localMax

        saveToEncrypted()
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
        val featureCount = min.size

        // Pre-compute ranges once to avoid repeated subtraction
        val ranges = FloatArray(featureCount) { i -> max[i] - min[i] }

        return features.map { vec ->
            // Use FloatArray internally to avoid boxing overhead
            val scaled = FloatArray(vec.size) { i ->
                if (ranges[i] == 0f) 0.5f
                else (vec[i] - min[i]) / ranges[i]
            }
            scaled.asList()
        }
    }

    @Synchronized
    override fun save() {
        saveToEncrypted()
    }

    @Synchronized
    override fun load() {
        loadFromEncrypted()
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
     * Load min and max from encrypted file. Handles missing or corrupted data
     * gracefully by logging a warning and resetting state.
     */
    private fun loadFromEncrypted() {
        val file = encryptedFile
        if (!file.exists()) {
            Logger.d("No encrypted scaler file found: ${file.name}")
            return
        }

        try {
            val params = SecureModelStorage.decryptScalerParams(file)
            if (params != null) {
                min = params.first
                max = params.second
                Logger.d("Scaler loaded from encrypted file: ${min?.size} features")
            } else {
                Logger.d("[WARN] Failed to decrypt scaler params, resetting")
                min = null
                max = null
            }
        } catch (e: Exception) {
            Logger.e("Error loading encrypted scaler: ${e.message}", e)
            min = null
            max = null
        }
    }

    /** Save min and max to encrypted file via SecureModelStorage. */
    private fun saveToEncrypted() {
        val localMin = min ?: return
        val localMax = max ?: return

        try {
            SecureModelStorage.encryptScalerParams(localMin, localMax, encryptedFile)
            Logger.d("Scaler saved to encrypted file: ${localMin.size} features")
        } catch (e: Exception) {
            Logger.e("Error saving encrypted scaler: ${e.message}", e)
        }
    }

    /**
     * One-time migration: If legacy SharedPreferences exist but no encrypted file,
     * read the plaintext values, encrypt them, and delete the SharedPreferences.
     */
    private fun migrateFromPrefsIfNeeded() {
        val file = encryptedFile
        if (file.exists()) return // Already migrated

        try {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val minStr = prefs.getString(KEY_MIN, null)
            val maxStr = prefs.getString(KEY_MAX, null)

            if (!minStr.isNullOrEmpty() && !maxStr.isNullOrEmpty()) {
                val minParts = minStr.split(",").filter { it.isNotBlank() }
                val maxParts = maxStr.split(",").filter { it.isNotBlank() }

                if (minParts.isNotEmpty() && maxParts.isNotEmpty() && minParts.size == maxParts.size) {
                    val migratedMin = minParts.map { it.toFloat() }.toFloatArray()
                    val migratedMax = maxParts.map { it.toFloat() }.toFloatArray()

                    // Encrypt and write
                    SecureModelStorage.encryptScalerParams(migratedMin, migratedMax, file)

                    // Clear the plaintext SharedPreferences
                    prefs.edit().clear().apply()

                    Logger.d("Migrated MinMaxScaler from SharedPreferences → encrypted file (${minParts.size} features)")
                }
            }
        } catch (e: Exception) {
            Logger.e("MinMaxScaler migration failed (non-fatal): ${e.message}", e)
        }
    }
}
