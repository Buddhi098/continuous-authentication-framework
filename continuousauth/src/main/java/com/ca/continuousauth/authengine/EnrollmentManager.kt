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
import kotlin.math.pow
import kotlin.math.sqrt

class EnrollmentManager(
    private val authModel: AuthModel,
    private val checkpointFile: File,
    private val thresholdFile: File,
) {
    private val trainValidationRatio = AuthConfigManager.config.trainValidationRatio
    private val enrollmentDataFilterRatio = AuthConfigManager.config.enrollmentDataFilterRatio

    // Step 2: Smooth the scores using AdaptiveScoreDenoiser
    private val denoiser = AdaptiveScoreDenoiser()
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

//            authModel.runTrainingSession(shuffled)
//            val filteredDataset = filterTightLegitSamples(shuffled, dropRatio = enrollmentDataFilterRatio)
//            Logger.d("Filtered Enrollment Sample Count ${filteredDataset.size}")

            val splitIndex = (shuffled.size * trainValidationRatio).toInt()
            val trainingSet = shuffled.subList(0, splitIndex)
            val validationSet = shuffled.subList(splitIndex, shuffled.size)

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
        sensitivity: Float = 1.5f // k-factor for margin
    ): Float? {
        return try {
            // 1️⃣ Extract Scores
            val rawScores = validationSet.mapNotNull { authModel.inferScore(it) }
            if (rawScores.size < 10) return null // Need a minimum sample size for stability

            // 2️⃣ Filter Outliers (using Interquartile Range)
            // This prevents one "weird" accidental swipe from ruining your threshold
            val sorted = rawScores.sorted()
            val q1 = sorted[(sorted.size * 0.25).toInt()]
            val q3 = sorted[(sorted.size * 0.75).toInt()]
            val iqr = q3 - q1

            val filteredScores = sorted.filter { it in (q1 - 1.5 * iqr)..(q3 + 1.5 * iqr) }
            if (filteredScores.isEmpty()) return null

            // 3️⃣ Statistical Calculation (Mean & Std Dev)
            val mean = filteredScores.average().toFloat()
            val stdDev = sqrt(
                filteredScores.map { (it - mean).toDouble().pow(2.0) }.average()
            ).toFloat()

            /**
             * 4️⃣ Apply Security Margin
             * For Genuine-only data, we want the threshold to be just outside
             * the "normal" range.
             * - Use (mean - 2 * stdDev) for high security (faster TDT).
             * - Use (mean - 3 * stdDev) for high usability (fewer false lockouts).
             */
            val threshold = mean - (sensitivity * stdDev)

            threshold
        } catch (e: Exception) {
            null
        }
    }


//    private fun calculateThreshold(
//        validationSet: List<List<Float>>,
//        factor: Float = 6.0f,              // k * std
//        lowerPercentile: Float = 0.0f,
//        upperPercentile: Float = 0.95f
//    ): Float? {
//        return try {
//
//            // --------------------------------------------------
//            // 1. Infer + denoise all scores
//            // --------------------------------------------------
//            val scoreDenoiser = AdaptiveScoreDenoiser()
//            scoreDenoiser.reset()
//
//            val denoisedScores = validationSet.mapNotNull { vector ->
//                authModel.inferScore(vector)?.let { raw ->
//                    scoreDenoiser.denoise(raw)
//                }
//            }
//
//            if (denoisedScores.isEmpty()) return null
//
//            // --------------------------------------------------
//            // 2. Percentile-based outlier removal
//            // --------------------------------------------------
//            val sorted = denoisedScores.sorted()
//            val n = sorted.size
//
//            val lowIndex = ((n - 1) * lowerPercentile).toInt().coerceIn(0, n - 1)
//            val highIndex = ((n - 1) * upperPercentile).toInt().coerceIn(0, n - 1)
//
//            val low = sorted[lowIndex]
//            val high = sorted[highIndex]
//
//            val cleanScores = sorted.filter { it in low..high }
//            if (cleanScores.isEmpty()) return null
//
//            // --------------------------------------------------
//            // 3. Mean + Standard Deviation
//            // --------------------------------------------------
//            val mean = cleanScores.average().toFloat()
//
//            val variance = cleanScores
//                .map { (it - mean) * (it - mean) }
//                .average()
//                .toFloat()
//
//            val std = kotlin.math.sqrt(variance)
//
//            // --------------------------------------------------
//            // 4. Final threshold
//            // --------------------------------------------------
//            mean + factor * std
//
//        } catch (e: Exception) {
//            Logger.e("Threshold calculation failed", e)
//            null
//        }
//    }

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
