package com.ca.continuousauth.authengine

import android.os.Debug
import android.os.SystemClock
import com.ca.continuousauth.authmodel.AuthModel
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.states.EnrollmentResult
import com.ca.continuousauth.utils.Logger
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class EnrollmentManager(
        private val authModel: AuthModel,
        private val checkpointFile: File,
        private val thresholdFile: File,
        private val metadataFile: File,
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
        ephocs: Int,
    ): EnrollmentResult {

        // ✅ Start CPU time measurement
        val startTimeNs = SystemClock.elapsedRealtimeNanos()

        // ✅ Start memory measurement
        val runtime = Runtime.getRuntime()
        val startUsedMemory = runtime.totalMemory() - runtime.freeMemory()

        try {
            if (dataSet.size < 10) {
                return EnrollmentResult(
                    success = false,
                    message = "Not enough samples for enrollment"
                )
            }

            Logger.d("Enrollment started with ${dataSet.size} samples")

            // 0️⃣ Shuffle dataset
            val shuffledDataSet = dataSet.shuffled()

            // 1️⃣ Train model
            authModel.runTrainingSession(shuffledDataSet, ephocs)

            // 2️⃣ Calculate threshold
            val threshold = calculateThreshold(shuffledDataSet) ?: return EnrollmentResult(
                success = false,
                message = "Threshold calculation failed"
            )

            // 3️⃣ Save model
            if (!authModel.saveCheckpoint(checkpointFile)) {
                return EnrollmentResult(
                    success = false,
                    message = "Failed to save model checkpoint"
                )
            }

            // 4️⃣ Save threshold
            if (!saveThreshold(threshold)) {
                return EnrollmentResult(
                    success = false,
                    message = "Failed to save threshold"
                )
            }

            // 5️⃣ Save metadata
            val sampleCount = shuffledDataSet.size
            saveMetadata(sampleCount)

            // ✅ End CPU time
            val endTimeNs = SystemClock.elapsedRealtimeNanos()
            val durationMs = (endTimeNs - startTimeNs) / 1_000_000

            // ✅ End memory usage
            val endUsedMemory = runtime.totalMemory() - runtime.freeMemory()
            val usedMemoryKB = (endUsedMemory - startUsedMemory) / 1024

            // Optional: Native heap (more accurate for ML models)
            val nativeHeapKB = Debug.getNativeHeapAllocatedSize() / 1024

            Logger.d("""
            Enrollment successful:
            Threshold = $threshold
            Samples = $sampleCount
            CPU Time = ${durationMs} ms
            RAM Usage (Java Heap Delta) = ${usedMemoryKB} KB
            Native Heap Usage = ${nativeHeapKB} KB
        """.trimIndent())

            return EnrollmentResult(
                success = true,
                threshold = threshold,
                trainedSampleCount = sampleCount
            )

        } catch (e: Exception) {

            val endTimeNs = SystemClock.elapsedRealtimeNanos()
            val durationMs = (endTimeNs) / 1_000_000

            Logger.e("Enrollment failed after ${durationMs} ms: ${e.message}", e)

            return EnrollmentResult(
                success = false,
                message = "Enrollment exception: ${e.message}"
            )
        }
    }

    // --------------------------------------------------
    // Threshold Calculation
    // -------------------------------------------------

    // Optimization: Use FloatArray instead of List<Float> to save boxing overhead
    fun calculateThreshold(
            validationSet: List<List<Float>>,
            percentile: Float = 85f,
            strictness: Float = 1.5f // Standard IQR multiplier (1.5 is standard, 3.0 is loose)
    ): Float? {
        // 1. Gather scores (reuse ArrayList to avoid resizing overhead)
        val scores = ArrayList<Float>(validationSet.size)
        var nullCount = 0
        var nanInfCount = 0
        for (vector in validationSet) {
            val score = authModel.inferScore(vector)
            if (score == null) {
                nullCount++
                continue
            }
            if (score.isNaN() || score.isInfinite()) {
                nanInfCount++
                continue
            }
            scores.add(score)
        }

        Logger.d(
                "Threshold calc: validationSet=${validationSet.size}, validScores=${scores.size}, " +
                        "nullInferences=$nullCount, nanOrInf=$nanInfCount"
        )

        if (scores.size < 20) {
            Logger.e(
                    "Threshold calculation failed: only ${scores.size} valid scores (need 20). " +
                            "null=$nullCount, nanOrInf=$nanInfCount"
            )
            return null
        }

        // 2. Sort In-Place (Required for both IQR and Percentile)
        scores.sort()

        Logger.d(
                "Threshold scores: min=${scores.first()}, max=${scores.last()}, " +
                        "median=${scores[scores.size / 2]}"
        )

        // 3. Calculate IQR (Interquartile Range)
        // We treat the sorted list as our distribution
        val q1Index = (scores.size * 0.25).toInt()
        val q3Index = (scores.size * 0.75).toInt()

        val q1 = scores[q1Index]
        val q3 = scores[q3Index]
        val iqr = q3 - q1

        val lowerFence = q1 - (strictness * iqr)
        val upperFence = q3 + (strictness * iqr)

        // 4. Find valid range indices (Zero-Copy Optimization)
        // We only need to find where valid data starts and ends in the sorted list
        var validStartIndex = 0
        var validEndIndex = scores.lastIndex

        // Move start index forward until >= lowerFence
        while (validStartIndex <= validEndIndex && scores[validStartIndex] < lowerFence) {
            validStartIndex++
        }

        // Move end index backward until <= upperFence
        while (validEndIndex >= validStartIndex && scores[validEndIndex] > upperFence) {
            validEndIndex--
        }

        val validCount = (validEndIndex - validStartIndex) + 1
        if (validCount < 10) {
            Logger.e(
                    "Threshold calculation failed: only $validCount valid scores after IQR filtering (need 10). " +
                            "IQR=$iqr, lowerFence=$lowerFence, upperFence=$upperFence"
            )
            return null
        }

        // 5. Calculate Threshold on the "Virtual" Filtered Set
        // We calculate which index in the FULL sorted list corresponds to the percentile
        // of the VALID subset.
        val percentileRank = (percentile / 100f) * (validCount - 1)
        val targetIndex = validStartIndex + percentileRank.toInt()

        // Clamp to ensure we stay within valid bounds
        val safeIndex = targetIndex.coerceIn(validStartIndex, validEndIndex)

        return scores[safeIndex]
    }

    // --------------------------------------------------
    // Persistent Storage
    // --------------------------------------------------
    private fun saveThreshold(threshold: Float): Boolean {
        return try {
            FileOutputStream(thresholdFile).use { fos ->
                DataOutputStream(fos).use { dos -> dos.writeFloat(threshold) }
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

    // --------------------------------------------------
    // Metadata (Sample Count)
    // --------------------------------------------------

    private fun saveMetadata(sampleCount: Int) {
        try {
            val json = org.json.JSONObject()
            json.put("trained_samples", sampleCount)
            json.put("timestamp", System.currentTimeMillis())
            metadataFile.writeText(json.toString())
        } catch (e: Exception) {
            Logger.e("Failed to save enrollment metadata", e)
        }
    }

    fun loadMetadata(): Int? {
        return try {
            if (!metadataFile.exists()) return null
            val json = org.json.JSONObject(metadataFile.readText())
            if (json.has("trained_samples")) {
                json.getInt("trained_samples")
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e("Failed to load enrollment metadata", e)
            null
        }
    }
}
