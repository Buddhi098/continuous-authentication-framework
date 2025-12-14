package com.ca.continuousauth.authengine

import com.ca.continuousauth.authmodel.AuthModel
import com.ca.continuousauth.states.EnrollmentResult
import com.ca.continuousauth.utils.Logger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.sqrt

class EnrollmentManager(
    private val authModel: AuthModel,
    private val checkpointFile: File,
    private val thresholdFile: File
) {

    // --------------------------------------------------
    // Enrollment (Train + Threshold + Persist)
    // --------------------------------------------------

    /**
     * Enrolls the model using the provided dataset.
     * @param dataSet List of feature vectors for enrollment
     * @param thresholdFactor Factor to multiply standard deviation for threshold
     */
    fun enroll(
        dataSet: List<List<Float>>,
        thresholdFactor: Float = 2.0f
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
            val splitIndex = (shuffled.size * 0.8f).toInt()

            val trainingSet = shuffled.subList(0, splitIndex)
            val validationSet = shuffled.subList(splitIndex, shuffled.size)

            Logger.d("Enrollment started. Train=${trainingSet.size}, Validation=${validationSet.size}")

            // 1️⃣ Train model
            authModel.runTrainingSession(trainingSet)

            // 2️⃣ Calculate threshold
            val threshold = calculateThreshold(validationSet, thresholdFactor)
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
        factor: Float
    ): Float? {
        return try {
            val scores = validationSet.mapNotNull {
                authModel.inferScore(it)
            }

            if (scores.isEmpty()) return null

            val mean = scores.average().toFloat()
            val variance = scores
                .map { (it - mean) * (it - mean) }
                .average()
                .toFloat()

            val stdDev = sqrt(variance)
            mean + factor * stdDev

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
