package com.ca.continuousauth.authengine

import android.content.Context
import com.ca.continuousauth.authmodel.AuthModel
import com.ca.continuousauth.config.AuthConfigManager
import com.ca.continuousauth.states.AuthVectorResult
import com.ca.continuousauth.utils.Logger
import java.io.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AuthenticationManager(
        private val context: Context,
        private val authModel: AuthModel,
        private val checkpointFile: File,
        private val thresholdFile: File,
        private val storedVectorsFile: File,
        private val maxStoredVectors: Int = AuthConfigManager.config.maxStoredAuthenticatedVectors,
        private val coroutineScope: CoroutineScope =
                CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    private val authenticatedVectors = mutableListOf<List<Float>>()
    // Mutex to protect authenticatedVectors access during async operations
    private val vectorLock = Mutex()

    private var cachedThreshold: Float? = null
    // Volatile for lightweight thread visibility, though mostly accessed via main flow
    @Volatile private var isModelLoaded: Boolean = false

    // Atomic counters for thread-safe updates without lock if needed,
    // though we often update them in the auth flow.
    private var totalAuthentications = AtomicInteger(0)
    private var successfulAuthentications = AtomicInteger(0)

    private val _storedVectorCount = MutableStateFlow(0)
    val storedVectorCount: StateFlow<Int> = _storedVectorCount.asStateFlow()

    init {
        Logger.d("AuthenticationManager initializing")

        // Load potentially slow resources asynchronously or blocking if critical for startup?
        // Usually, blocking init in constructor is bad, but for this manager
        // it might be expected to be ready.
        // Let's keep it synchronous but safe, or move to a `initialize()` suspend function.
        // For strict refactoring of existing logic, keeping it blocking but safe.
        runBlocking { loadStoredVectors() }
        loadModel()
        loadThresholdOnce()
        Logger.d("AuthenticationManager initialization completed")
    }

    // --------------------------------------------------
    // Authentication
    // --------------------------------------------------
    fun authenticateFeatureVector(featureVector: List<Float>): AuthVectorResult {

        if (!isModelLoaded) {
            Logger.e("Authentication failed: model not loaded")
            // Return failed result instead of crashing
            return AuthVectorResult(
                    isAuthenticated = false,
                    score = 0f,
                    threshold = 0f,
                    authPercentage = null,
                    totalAuthentications = totalAuthentications.get()
            )
        }

        val threshold =
                cachedThreshold
                        ?: run {
                            Logger.e("Authentication failed: threshold not available")
                            return AuthVectorResult(
                                    isAuthenticated = false,
                                    score = 0f,
                                    threshold = 0f,
                                    authPercentage = null,
                                    totalAuthentications = totalAuthentications.get()
                            )
                        }

        // Timer
        val startTime = System.nanoTime()

        val rawScore = authModel.inferScore(featureVector)

        // Optional: Denoise if needed. Uncomment if logic is restored.
        // val score = rawScore?.let { adaptiveScoreDenoiser.denoise(it) }
        val score = rawScore

        val durationMs = (System.nanoTime() - startTime) / 1_000_000.0

        if (score == null) {
            Logger.e("Authentication failed: inference returned null")
            return AuthVectorResult(
                    isAuthenticated = false,
                    score = 0f,
                    threshold = threshold,
                    authPercentage = null,
                    totalAuthentications = totalAuthentications.get()
            )
        }

        val isAuthenticated = score <= threshold

        // Update counters
        val total = totalAuthentications.incrementAndGet()
        val successful =
                if (isAuthenticated) {
                    successfulAuthentications.incrementAndGet()
                } else {
                    successfulAuthentications.get()
                }

        if (isAuthenticated) {
            // FIRE AND FORGET: Store vector asynchronously to avoid blocking auth stream
            coroutineScope.launch { storeAuthenticatedVector(featureVector) }
        }

        val authPercentage = if (total > 0) (successful.toFloat() / total) * 100f else null

        Logger.d(
                "Authentication result -> " +
                        "score=$score, threshold=$threshold, authenticated=$isAuthenticated, " +
                        "authPercentage=$authPercentage, infer execution time=${"%.3f".format(durationMs)}ms"
        )

        return AuthVectorResult(
                isAuthenticated = isAuthenticated,
                score = score,
                threshold = threshold,
                authPercentage = authPercentage,
                totalAuthentications = total
        )
    }

    fun stopAuthentication(resetCounters: Boolean = true) {
        Logger.d("Stopping authentication session")

        if (resetCounters) {
            totalAuthentications.set(0)
            successfulAuthentications.set(0)
            Logger.d("Authentication counters reset")
        }

        Logger.d("Authentication stopped successfully")
    }

    // --------------------------------------------------
    // Initialization helpers
    // --------------------------------------------------
    fun loadModel() {
        if (isModelLoaded) return
        Logger.d("Loading authentication model checkpoint")
        if (!checkpointFile.exists()) {
            Logger.e("Checkpoint file not found at ${checkpointFile.absolutePath}")
            return
        }
        isModelLoaded = authModel.loadCheckpoint(checkpointFile)

        if (isModelLoaded) {
            Logger.d("Authentication model loaded successfully")
        } else {
            Logger.e("Failed to load authentication model")
        }
    }

    fun loadThresholdOnce() {
        if (cachedThreshold != null) return
        Logger.d("Loading threshold value")

        cachedThreshold =
                try {
                    if (!thresholdFile.exists()) {
                        Logger.e("Threshold file not found at ${thresholdFile.absolutePath}")
                        null
                    } else {
                        DataInputStream(FileInputStream(thresholdFile))
                                .use { it.readFloat() }
                                .also { Logger.d("Threshold loaded successfully: $it") }
                    }
                } catch (e: Exception) {
                    Logger.e("Error loading threshold: ${e.message}", e)
                    null
                }
    }

    // --------------------------------------------------
    // Authenticated vector storage (Async)
    // --------------------------------------------------
    private suspend fun storeAuthenticatedVector(vector: List<Float>) {
        vectorLock.withLock {
            try {
                authenticatedVectors.add(vector)

                if (authenticatedVectors.size > maxStoredVectors) {
                    val removed = authenticatedVectors.size - maxStoredVectors
                    repeat(removed) { authenticatedVectors.removeAt(0) }
                    Logger.d("Trimmed authenticated vectors, removed=$removed")
                }
                _storedVectorCount.value = authenticatedVectors.size

                // Save to disk (on IO dispatcher)
                withContext(Dispatchers.IO) { saveStoredVectors() }
            } catch (e: Exception) {
                Logger.e("Failed to store authenticated vector: ${e.message}", e)
            }
        }
    }

    private fun saveStoredVectors() {
        // Must be called within IO context/background
        try {
            // Use atomic write? For now simple stream
            ObjectOutputStream(FileOutputStream(storedVectorsFile)).use {
                it.writeObject(authenticatedVectors)
            }
            // Logger.d("Authenticated vectors saved to disk") // Verbose logging removed
        } catch (e: Exception) {
            Logger.e("Failed to persist authenticated vectors: ${e.message}", e)
        }
    }

    private suspend fun loadStoredVectors() {
        if (!storedVectorsFile.exists()) return

        if (storedVectorsFile.length() == 0L) {
            storedVectorsFile.delete()
            return
        }

        withContext(Dispatchers.IO) {
            vectorLock.withLock {
                try {
                    ObjectInputStream(FileInputStream(storedVectorsFile)).use { ois ->
                        @Suppress("UNCHECKED_CAST")
                        val vectors = ois.readObject() as? List<List<Float>>

                        if (!vectors.isNullOrEmpty()) {
                            authenticatedVectors.clear()
                            authenticatedVectors.addAll(vectors)
                            _storedVectorCount.value = authenticatedVectors.size
                            Logger.d("Loaded ${vectors.size} authenticated vectors from disk")
                        }
                    }
                } catch (e: EOFException) {
                    Logger.e("Stored vectors corrupted (EOF). Deleting.", e)
                    storedVectorsFile.delete()
                } catch (e: InvalidClassException) {
                    Logger.e("Stored vectors incompatible. Deleting.", e)
                    storedVectorsFile.delete()
                } catch (e: Exception) {
                    Logger.e("Error loading stored vectors. Deleting.", e)
                    storedVectorsFile.delete()
                }
            }
        }
    }

    // --------------------------------------------------
    // Public helpers
    // --------------------------------------------------

    // Blocking getter for simplicity, or suspend?
    // Usually UI calls this. We'll make it use a safe copy under lock.
    fun getStoredVectors(): List<List<Float>> = runBlocking {
        vectorLock.withLock { authenticatedVectors.toList() }
    }

    fun getCachedThreshold(): Float? {
        return cachedThreshold
    }

    fun resetAuthenticationCounters() {
        Logger.d("Resetting authentication counters")
        totalAuthentications.set(0)
        successfulAuthentications.set(0)
    }

    fun getAuthenticationPercentage(): Float? {
        val total = totalAuthentications.get()
        return if (total > 0) (successfulAuthentications.get().toFloat() / total) * 100f else null
    }

    fun isReadyForReEnrollment(): Boolean = runBlocking {
        vectorLock.withLock { authenticatedVectors.size >= maxStoredVectors }
    }

    fun clearStoredVectors() = runBlocking {
        vectorLock.withLock {
            authenticatedVectors.clear()
            withContext(Dispatchers.IO) {
                if (storedVectorsFile.exists()) {
                    storedVectorsFile.delete()
                }
            }
            _storedVectorCount.value = 0
            Logger.d("Stored vectors cleared")
        }
    }
}
