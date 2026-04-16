package com.ca.continuousauth.authengine

import android.content.Context
import android.os.Debug
import com.ca.continuousauth.authmodel.AuthModel
import com.ca.continuousauth.data.ReEnrollmentDataManager
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger

class AuthenticationManager(
    private val context: Context,
    private val authModel: AuthModel,
    private val checkpointFile: File,
    private val thresholdFile: File,
    private val reEnrollmentManager: ReEnrollmentDataManager,
    private val coroutineScope: CoroutineScope =
        CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    // ----------------------------
    // STATE
    // ----------------------------
    @Volatile private var isModelLoaded = false
    private var cachedThreshold: Float? = null

    private val totalAuthentications = AtomicInteger(0)
    private val successfulAuthentications = AtomicInteger(0)

    // ----------------------------
    // INIT
    // ----------------------------
    init {
        Logger.d("AuthenticationManager initializing")

        coroutineScope.launch {
            try {
                reEnrollmentManager.load()
                loadModel()
                loadThresholdOnce()
                Logger.d("AuthenticationManager ready")
            } catch (e: Exception) {
                Logger.e("Initialization failed: ${e.message}", e)
            }
        }
    }

    // ----------------------------
    // AUTHENTICATION
    // ----------------------------

    fun authenticateFeatureVector(
        featureVector: List<Float>,
        rawVector: List<Float> = featureVector
    ): Map<String, Any> {

        if (!isModelLoaded || cachedThreshold == null) {
            return failure("Model or threshold not ready")
        }

        val startWallTime = System.nanoTime()
        val startCpuTime = Debug.threadCpuTimeNanos()

        // Memory before inference
        val runtime = Runtime.getRuntime()
        val usedMemBefore = runtime.totalMemory() - runtime.freeMemory()

        val score = authModel.inferScore(featureVector)
            ?: return failure("Inference failed")

        // Memory after inference
        val usedMemAfter = runtime.totalMemory() - runtime.freeMemory()

        val endCpuTime = Debug.threadCpuTimeNanos()
        val endWallTime = System.nanoTime()

        val threshold = cachedThreshold!!
        val isAuthenticated = score <= threshold

        totalAuthentications.incrementAndGet()

        if (isAuthenticated) {
            successfulAuthentications.incrementAndGet()

            coroutineScope.launch {
                try {
                    reEnrollmentManager.addVector(rawVector)
                    Logger.d("Vector stored for re-enrollment")
                } catch (e: Exception) {
                    Logger.e("Failed to store vector: ${e.message}", e)
                }
            }
        }

        val latencyMs = (endWallTime - startWallTime) / 1_000_000.0
        val cpuTimeMs = (endCpuTime - startCpuTime) / 1_000_000.0

        val memoryUsedKB = (usedMemAfter - usedMemBefore) / 1024.0

        Logger.d(
            "Auth result -> score=$score, threshold=$threshold, authenticated=$isAuthenticated\n" +
                    "Latency=${"%.3f".format(latencyMs)} ms, CPU=${"%.3f".format(cpuTimeMs)} ms, " +
                    "RAM delta=${"%.3f".format(memoryUsedKB)} KB"
        )

        return mapOf(
            "isAuthenticated" to isAuthenticated,
            "score" to score,
            "threshold" to threshold,
            "latencyMs" to latencyMs,
            "cpuTimeMs" to cpuTimeMs,
            "memoryDeltaKB" to memoryUsedKB
        )
    }

    private fun failure(message: String): Map<String, Any> {
        Logger.e(message)
        return mapOf(
            "isAuthenticated" to false,
            "score" to 0f,
            "threshold" to (cachedThreshold ?: 0f)
        )
    }

    // ----------------------------
    // MODEL / THRESHOLD
    // ----------------------------
    fun loadModel() {
        if (isModelLoaded) return

        if (!checkpointFile.exists()) {
            Logger.e("Checkpoint file not found: ${checkpointFile.absolutePath}")
            return
        }

        isModelLoaded = authModel.loadCheckpoint(checkpointFile)

        if (isModelLoaded) {
            Logger.d("Model loaded successfully")
        } else {
            Logger.e("Model loading failed")
        }
    }

    fun loadThresholdOnce() {
        if (cachedThreshold != null) return

        cachedThreshold = try {
            if (!thresholdFile.exists()) {
                Logger.e("Threshold file not found: ${thresholdFile.absolutePath}")
                null
            } else {
                DataInputStream(FileInputStream(thresholdFile)).use { it.readFloat() }
            }
        } catch (e: Exception) {
            Logger.e("Failed to load threshold: ${e.message}", e)
            null
        }

        Logger.d("Threshold loaded: $cachedThreshold")
    }

    fun invalidateCache() {
        isModelLoaded = false
        cachedThreshold = null
        Logger.d("Cache invalidated")
    }

    // ----------------------------
    // SESSION CONTROL
    // ----------------------------
    fun stopAuthentication(resetCounters: Boolean = true) {
        Logger.d("Stopping authentication session")

        if (resetCounters) {
            resetAuthenticationCounters()
        }

        // Flush stored vectors safely
        coroutineScope.launch {
            flushToDisk()
        }

        Logger.d("Authentication stopped")
    }

    fun resetAuthenticationCounters() {
        Logger.d("Resetting authentication counters")
        totalAuthentications.set(0)
        successfulAuthentications.set(0)
    }

    fun getAuthenticationSuccessRate(): Float? {
        val total = totalAuthentications.get()
        return if (total == 0) null
        else (successfulAuthentications.get().toFloat() / total) * 100f
    }

    // ----------------------------
    // RE-ENROLLMENT ACCESS
    // ----------------------------
    suspend fun getStoredVectors(): List<List<Float>> =
        reEnrollmentManager.getVectors()

    suspend fun clearStoredVectors() =
        reEnrollmentManager.clear()

    suspend fun flushVectors() =
        reEnrollmentManager.flush()

    suspend fun flushToDisk() {
        try {
            reEnrollmentManager.flush()
            Logger.d("Re-enrollment data flushed to disk")
        } catch (e: Exception) {
            Logger.e("Flush failed: ${e.message}", e)
        }
    }

    fun invalidateCachedState() {
        Logger.d("Invalidating cached authentication state")

        isModelLoaded = false
        cachedThreshold = null

        Logger.d("Model and threshold cache cleared")
    }
}