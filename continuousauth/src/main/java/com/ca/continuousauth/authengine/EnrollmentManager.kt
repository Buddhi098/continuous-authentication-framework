package com.ca.continuousauth.authengine

import com.ca.continuousauth.authmodel.AuthModel
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.states.EnrollmentResult
import com.ca.continuousauth.utils.Logger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.sqrt

class EnrollmentManager(
    private val authModel: AuthModel,
    private val checkpointFile: File,
    private val thresholdFile: File,
) {
    private val trainValidationRatio = AuthConfigManager.config.trainValidationRatio
    private val enrollmentDataFilterRatio = AuthConfigManager.config.enrollmentDataFilterRatio
    // --------------------------------------------------
    // Enrollment (Train + Threshold + Persist)
    // --------------------------------------------------

    /**
     * Filters out unstable legitimate samples using reconstruction error.
     *
     * @param data Legitimate enrollment samples
     * @param dropRatio Fraction of worst samples to remove (e.g. 0.15 = remove top 15%)
     */
    private fun filterTightLegitSamples(
        data: List<List<Float>>,
        dropRatio: Double = 0.1
    ): List<List<Float>> {

        if (data.size < 100) {
            // Too small → do NOT filter
            Logger.d("Skipping legit filtering (dataset too small)")
            return data
        }

        // Compute reconstruction error for each sample
        val scored = data.mapNotNull { sample ->
            authModel.inferScore(sample)?.let { score ->
                sample to score
            }
        }

        if (scored.isEmpty()) return data

        // Sort by error (ascending = best legit)
        val sorted = scored.sortedBy { it.second }

        val keepCount = (sorted.size * (1f - dropRatio))
            .toInt()
            .coerceAtLeast(10)   // always keep minimum core

        val filtered = sorted
            .take(keepCount)
            .map { it.first }

        Logger.d(
            "Legit filtering: original=${data.size}, kept=${filtered.size}, removed=${data.size - filtered.size}"
        )

        return filtered
    }

    /**
     * Enrolls the model using the provided dataset.
     * @param dataSet List of feature vectors for enrollment
     * @param thresholdFactor Factor to multiply standard deviation for threshold
     */
    fun enroll(
        dataSet: List<List<Float>>,
    ): EnrollmentResult {
        try {
            if (dataSet.size < 10) {
                return EnrollmentResult(
                    success = false,
                    message = "Not enough samples for enrollment"
                )
            }

            // Shuffle to avoid ordering bias
            val shuffled = dataSet.shuffled()
            Logger.d("Original Enrollment Sample Count ${shuffled.size}")

            authModel.runTrainingSession(shuffled)
            val filteredDataset = filterTightLegitSamples(shuffled, dropRatio = enrollmentDataFilterRatio)
            Logger.d("Filtered Enrollment Sample Count ${filteredDataset.size}")

            val splitIndex = (filteredDataset.size * trainValidationRatio).toInt()
            val trainingSet = filteredDataset.subList(0, splitIndex)
            val validationSet = filteredDataset.subList(splitIndex, filteredDataset.size)

            Logger.d("Enrollment started. Train=${trainingSet.size}, Validation=${validationSet.size}")

            // 1️⃣ Train model
            authModel.runTrainingSession(trainingSet)

            // 2️⃣ Calculate threshold
            val threshold = calculateThreshold(validationSet)
                ?: return EnrollmentResult(
                    success = false,
                    message = "Threshold calculation failed"
                )

            // 3️⃣ Persist model weights
            if (!authModel.saveCheckpoint(checkpointFile)) {
                return EnrollmentResult(
                    success = false,
                    message = "Failed to save model checkpoint"
                )
            }

            // 4️⃣ Persist threshold
            if (!saveThreshold(threshold)) {
                return EnrollmentResult(
                    success = false,
                    message = "Failed to save threshold"
                )
            }

            Logger.d("Enrollment successful. Threshold=$threshold")

            return EnrollmentResult(
                success = true,
                threshold = threshold
            )

        } catch (e: Exception) {
            Logger.e("Enrollment failed: ${e.message}", e)
            return EnrollmentResult(
                success = false,
                message = "Enrollment exception: ${e.message}"
            )
        }
    }

    // --------------------------------------------------
    // Threshold Calculation
    // --------------------------------------------------
    private fun calculateThreshold(
        validationSet: List<List<Float>>,
        factor: Float = 8.0f,              // equivalent to k in PyTorch
        lowerPercentile: Float = 0f,  // lower percentile for outlier removal
        upperPercentile: Float = 0.9f   // upper percentile for outlier removal
    ): Float? {
        return try {
            val scores = validationSet.mapNotNull { authModel.inferScore(it) }
            if (scores.isEmpty()) return null

            // Sort scores
            val sortedScores = scores.sorted()
            val n = sortedScores.size

            // Compute bounds for outlier removal
            val lowIndex = ((n - 1) * lowerPercentile).toInt().coerceIn(0, n - 1)
            val highIndex = ((n - 1) * upperPercentile).toInt().coerceIn(0, n - 1)
            val low = sortedScores[lowIndex]
            val high = sortedScores[highIndex]

            // Keep only scores within percentile bounds
            val cleanScores = sortedScores.filter { it in low..high }
            if (cleanScores.isEmpty()) return null

            // Compute median
            val median = cleanScores.sorted().let { cs ->
                val mid = cs.size / 2
                if (cs.size % 2 == 0) (cs[mid - 1] + cs[mid]) / 2f else cs[mid]
            }

            // Compute MAD (Median Absolute Deviation)
            val mad = cleanScores.map { abs(it - median) }.sorted().let { absSorted ->
                val mid = absSorted.size / 2
                if (absSorted.size % 2 == 0) (absSorted[mid - 1] + absSorted[mid]) / 2f else absSorted[mid]
            } + 1e-12f  // to avoid division by zero

            median + factor * mad
        } catch (e: Exception) {
            Logger.e("Threshold calculation error: ${e.message}", e)
            null
        }
    }

    // --------------------------------------------------
    // Persistent Storage
    // --------------------------------------------------

    private fun saveThreshold(threshold: Float): Boolean {
        return try {
            FileOutputStream(thresholdFile).use { fos ->
                DataOutputStream(fos).use { dos ->
                    dos.writeFloat(threshold)
                }
            }
            true
        } catch (e: Exception) {
            Logger.e("Failed to write threshold file: ${e.message}", e)
            false
        }
    }

    /**
     * Loads the persisted threshold from file.
     * @return the threshold value, or null if file doesn't exist or failed
     */
    fun loadThreshold(): Float? {
        return try {
            if (!thresholdFile.exists()) {
                Logger.e("Threshold file does not exist: ${thresholdFile.absolutePath}")
                return null
            }

            FileInputStream(thresholdFile).use { fis ->
                DataInputStream(fis).use { dis ->
                    val threshold = dis.readFloat()
                    Logger.d("Threshold loaded: $threshold")
                    threshold
                }
            }
        } catch (e: Exception) {
            Logger.e("Failed to load threshold: ${e.message}", e)
            null
        }
    }
}
