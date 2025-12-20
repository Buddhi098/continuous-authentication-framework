package com.ca.continuousauth.authengine

import android.content.Context
import com.ca.continuousauth.authmodel.AuthModel
import com.ca.continuousauth.featuremodalities.dataprocessing.scalers.Scaler
import com.ca.continuousauth.states.AuthVectorResult
import com.ca.continuousauth.utils.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import java.io.*
class AuthenticationManager(
    private val context: Context,
    private val authModel: AuthModel,
    private val checkpointFile: File,
    private val thresholdFile: File,
    private val storedVectorsFile: File,
    private val maxStoredVectors: Int = 100
) {

    private val authenticatedVectors = mutableListOf<List<Float>>()

    private var cachedThreshold: Float? = null
    private var isModelLoaded: Boolean = false

    // Counters for percentage calculation
    private var totalAuthentications: Int = 0
    private var successfulAuthentications: Int = 0

    init {
        Logger.d("AuthenticationManager initializing")
        loadStoredVectors()
        loadModel()
        loadThresholdOnce()
        Logger.d("AuthenticationManager initialization completed")
    }

    // --------------------------------------------------
    // Authentication
    // --------------------------------------------------
    fun authenticateFeatureVector(featureVector: List<Float>): AuthVectorResult {

        Logger.d("Authenticating feature vector")

        if (!isModelLoaded) {
            Logger.e("Authentication failed: model not loaded")
            throw IllegalStateException("Authentication model not loaded")
        }

        val threshold = cachedThreshold
        if (threshold == null) {
            Logger.e("Authentication failed: threshold not available")
            throw IllegalStateException("Threshold not available")
        }

        val score = authModel.inferScore(featureVector)
        if (score == null) {
            Logger.e("Authentication failed: inference returned null")
            throw IllegalStateException("Inference failed")
        }

        val isAuthenticated = score <= threshold

        // Update counters
        totalAuthentications++
        if (isAuthenticated) {
            successfulAuthentications++
            storeAuthenticatedVector(featureVector)
        }

        val authPercentage =
            if (totalAuthentications > 0)
                (successfulAuthentications.toFloat() / totalAuthentications) * 100f
            else
                null

        Logger.d(
            "Authentication result -> " +
                    "score=$score, threshold=$threshold, authenticated=$isAuthenticated, " +
                    "authPercentage=$authPercentage"
        )

        return AuthVectorResult(
            isAuthenticated = isAuthenticated,
            score = score,
            threshold = threshold,
            authPercentage = authPercentage
        )
    }

    // --------------------------------------------------
    // Initialization helpers
    // --------------------------------------------------
    private fun loadModel() {
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

    private fun loadThresholdOnce() {
        Logger.d("Loading threshold value")

        cachedThreshold = try {
            if (!thresholdFile.exists()) {
                Logger.e("Threshold file not found at ${thresholdFile.absolutePath}")
                null
            } else {
                DataInputStream(FileInputStream(thresholdFile)).use {
                    it.readFloat()
                }.also {
                    Logger.d("Threshold loaded successfully: $it")
                }
            }
        } catch (e: Exception) {
            Logger.e("Error loading threshold: ${e.message}", e)
            null
        }
    }

    // --------------------------------------------------
    // Authenticated vector storage
    // --------------------------------------------------
    private fun storeAuthenticatedVector(vector: List<Float>) {
        try {
            authenticatedVectors.add(vector)

            if (authenticatedVectors.size > maxStoredVectors) {
                val removed = authenticatedVectors.size - maxStoredVectors
                repeat(removed) { authenticatedVectors.removeAt(0) }
                Logger.d("Trimmed authenticated vectors, removed=$removed")
            }

            saveStoredVectors()
            Logger.d("Authenticated vector stored successfully")

        } catch (e: Exception) {
            Logger.e("Failed to store authenticated vector: ${e.message}", e)
        }
    }

    private fun saveStoredVectors() {
        try {
            ObjectOutputStream(FileOutputStream(storedVectorsFile)).use {
                it.writeObject(authenticatedVectors)
            }
            Logger.d("Authenticated vectors saved to disk")
        } catch (e: Exception) {
            Logger.e("Failed to persist authenticated vectors: ${e.message}", e)
        }
    }

    private fun loadStoredVectors() {

        if (!storedVectorsFile.exists()) {
            Logger.d("No stored authenticated vectors file found")
            return
        }

        if (storedVectorsFile.length() == 0L) {
            Logger.d("Stored vectors file is empty. Deleting file.")
            storedVectorsFile.delete()
            return
        }

        try {
            ObjectInputStream(FileInputStream(storedVectorsFile)).use { ois ->

                @Suppress("UNCHECKED_CAST")
                val vectors = ois.readObject() as? List<List<Float>>

                if (vectors.isNullOrEmpty()) {
                    Logger.d("Stored vectors file contained no valid vectors")
                    return
                }

                authenticatedVectors.clear()
                authenticatedVectors.addAll(vectors)

                Logger.d("Loaded ${vectors.size} authenticated vectors from disk")
            }

        } catch (e: EOFException) {
            Logger.e("Stored vectors file corrupted or incomplete (EOF). Deleting file.", e)
            storedVectorsFile.delete()

        } catch (e: InvalidClassException) {
            Logger.e("Stored vectors incompatible with current app version. Deleting file.", e)
            storedVectorsFile.delete()

        } catch (e: Exception) {
            Logger.e("Unexpected error while loading stored vectors. Deleting file.", e)
            storedVectorsFile.delete()
        }
    }

    // --------------------------------------------------
    // Public helpers
    // --------------------------------------------------
    fun getStoredVectors(): List<List<Float>> {
        Logger.d("Returning ${authenticatedVectors.size} stored vectors")
        return authenticatedVectors.toList()
    }

    fun getCachedThreshold(): Float? {
        Logger.d("Returning cached threshold: $cachedThreshold")
        return cachedThreshold
    }

    fun resetAuthenticationCounters() {
        Logger.d("Resetting authentication counters")
        totalAuthentications = 0
        successfulAuthentications = 0
    }

    fun getAuthenticationPercentage(): Float? {
        return if (totalAuthentications > 0)
            (successfulAuthentications.toFloat() / totalAuthentications) * 100f
        else null
    }
}
