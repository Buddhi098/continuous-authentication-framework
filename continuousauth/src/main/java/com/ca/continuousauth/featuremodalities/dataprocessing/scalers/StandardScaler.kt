package com.ca.continuousauth.featuremodalities.dataprocessing.scalers

import android.content.Context
import com.ca.continuousauth.security.SecureModelStorage
import com.ca.continuousauth.utils.Logger
import java.io.File
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * StandardScaler for Z-score normalization with persistent encrypted storage.
 * Scales features to have mean = 0 and std = 1. Mean and std are loaded from
 * an encrypted file if available and updated on fitTransform.
 *
 * Storage: Parameters are encrypted via [SecureModelStorage] using AES-256-GCM with
 * HMAC-SHA256 integrity verification. Plaintext never touches disk.
 *
 * Thread-safe: All public methods are synchronized.
 */
class StandardScaler(private val context: Context, private val prefsName: String) : Scaler {

    private var mean: FloatArray? = null
    private var std: FloatArray? = null

    /** Encrypted file for scaler parameters — replaces legacy SharedPreferences */
    private val encryptedFile: File
        get() = File(context.filesDir, "${prefsName}.enc")

    /** Legacy SharedPreferences keys for one-time migration */
    private val KEY_MEAN = "mean"
    private val KEY_STD = "std"

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

        saveToEncrypted()

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
        saveToEncrypted()
    }

    @Synchronized
    override fun load() {
        loadFromEncrypted()
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
     * Load mean and std from encrypted file. Handles missing or corrupted data
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
                mean = params.first
                std = params.second
                Logger.d("StandardScaler loaded from encrypted file: ${mean?.size} features")
            } else {
                Logger.d("[WARN] Failed to decrypt standard scaler params, resetting")
                mean = null
                std = null
            }
        } catch (e: Exception) {
            Logger.e("Error loading encrypted standard scaler: ${e.message}", e)
            mean = null
            std = null
        }
    }

    /** Save mean and std to encrypted file via SecureModelStorage. */
    private fun saveToEncrypted() {
        val localMean = mean ?: return
        val localStd = std ?: return

        try {
            SecureModelStorage.encryptScalerParams(localMean, localStd, encryptedFile)
            Logger.d("StandardScaler saved to encrypted file: ${localMean.size} features")
        } catch (e: Exception) {
            Logger.e("Error saving encrypted standard scaler: ${e.message}", e)
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
            val meanStr = prefs.getString(KEY_MEAN, null)
            val stdStr = prefs.getString(KEY_STD, null)

            if (!meanStr.isNullOrEmpty() && !stdStr.isNullOrEmpty()) {
                val meanParts = meanStr.split(",").filter { it.isNotBlank() }
                val stdParts = stdStr.split(",").filter { it.isNotBlank() }

                if (meanParts.isNotEmpty() && stdParts.isNotEmpty() && meanParts.size == stdParts.size) {
                    val migratedMean = meanParts.map { it.toFloat() }.toFloatArray()
                    val migratedStd = stdParts.map { it.toFloat() }.toFloatArray()

                    // Encrypt and write
                    SecureModelStorage.encryptScalerParams(migratedMean, migratedStd, file)

                    // Clear the plaintext SharedPreferences
                    prefs.edit().clear().apply()

                    Logger.d("Migrated StandardScaler from SharedPreferences → encrypted file (${meanParts.size} features)")
                }
            }
        } catch (e: Exception) {
            Logger.e("StandardScaler migration failed (non-fatal): ${e.message}", e)
        }
    }
}
