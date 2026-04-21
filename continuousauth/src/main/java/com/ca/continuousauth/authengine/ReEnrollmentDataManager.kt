package com.ca.continuousauth.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.util.concurrent.atomic.AtomicInteger
import com.ca.continuousauth.security.SecureModelStorage

class ReEnrollmentDataManager(
    private val storedVectorsFile: File,
    private val requiredVectors: Int,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    companion object {
        private const val DISK_SAVE_INTERVAL = 50
    }

    private val vectorLock = Mutex()
    private val vectors = mutableListOf<List<Float>>()

    private val unsavedCount = AtomicInteger(0)

    private val _vectorCount = MutableStateFlow(0)
    val vectorCount: StateFlow<Int> = _vectorCount.asStateFlow()

    // ----------------------------
    // Load / Init
    // ----------------------------
    suspend fun load() {
        if (!storedVectorsFile.exists()) return

        vectorLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val loaded = SecureModelStorage.decryptVectors(storedVectorsFile)

                    if (!loaded.isNullOrEmpty()) {
                        vectors.clear()
                        vectors.addAll(loaded)
                        _vectorCount.value = vectors.size
                    }
                } catch (e: Exception) {
                    storedVectorsFile.delete()
                }
            }
        }
    }

    // ----------------------------
    // Add Vector
    // ----------------------------
    suspend fun addVector(vector: List<Float>) {
        vectorLock.withLock {
            vectors.add(vector)

            if (vectors.size > requiredVectors) {
                val removeCount = vectors.size - requiredVectors
                repeat(removeCount) { vectors.removeAt(0) }
            }

            _vectorCount.value = vectors.size

            val pending = unsavedCount.incrementAndGet()
            if (pending >= DISK_SAVE_INTERVAL) {
                unsavedCount.set(0)
                withContext(Dispatchers.IO) { saveInternal() }
            }
        }
    }

    // ----------------------------
    // Save / Load internal
    // ----------------------------
    private fun saveInternal() {
        try {
            SecureModelStorage.encryptVectors(vectors, storedVectorsFile)
        } catch (_: Exception) {
        }
    }

    suspend fun flush() {
        if (unsavedCount.get() > 0) {
            vectorLock.withLock {
                withContext(Dispatchers.IO) {
                    saveInternal()
                }
                unsavedCount.set(0)
            }
        }
    }

    // ----------------------------
    // Queries
    // ----------------------------
    suspend fun getVectors(): List<List<Float>> {
        return vectorLock.withLock { vectors.toList() }
    }

    // ----------------------------
    // Clear
    // ----------------------------
    suspend fun clear() {
        vectorLock.withLock {
            vectors.clear()
            unsavedCount.set(0)
            _vectorCount.value = 0

            withContext(Dispatchers.IO) {
                if (storedVectorsFile.exists()) {
                    storedVectorsFile.delete()
                }
            }
        }
    }

    fun isReadyForReEnrollment(): Boolean {
        return vectors.size >= requiredVectors
    }
}