package com.ca.continuousauth.security

import com.ca.continuousauth.utils.Logger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.KeyGenerator
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Secure encryption/decryption pipeline for ML model artifacts.
 *
 * Provides type-safe methods for encrypting and decrypting:
 * - Model weight checkpoints (HashMap<String, Any>)
 * - Authentication thresholds (Float)
 * - Scaler parameters (FloatArray pairs)
 * - Re-enrollment vectors (List<List<Float>>)
 * - Enrollment metadata (String/JSON)
 * - Collection state (ByteArray)
 *
 * Encrypted Blob Format:
 * ┌──────────┬──────────────┬────────────────────┬──────────────────┬──────────────────┐
 * │ VERSION  │  IV_LENGTH   │     IV (12B)       │  CIPHERTEXT+TAG  │  HMAC-SHA256     │
 * │  1 byte  │  4 bytes     │  12 bytes          │  variable        │  32 bytes        │
 * └──────────┴──────────────┴────────────────────┴──────────────────┴──────────────────┘
 *
 * All decrypted data exists ONLY in memory — never written back to disk as plaintext.
 *
 * Thread-Safety: Methods are stateless and safe for concurrent use.
 * The underlying Android Keystore handles its own synchronization.
 */
object SecureModelStorage {

    /** Current blob format version for forward compatibility */
    private const val BLOB_VERSION: Byte = 0x02
    private const val LEGACY_BLOB_VERSION: Byte = 0x01

    // ----------------------------------------------------------------
    // Core Encrypt / Decrypt
    // ----------------------------------------------------------------

    /**
     * Encrypts raw bytes and writes the encrypted blob to a file.
     *
     * @param plainBytes The plaintext bytes to encrypt
     * @param targetFile The file to write the encrypted blob to
     * @throws SecurityException if encryption fails
     */
    fun encryptToFile(plainBytes: ByteArray, targetFile: File) {
        try {
            // Envelope Encryption:
            // 1. Generate a random Data Encryption Key (DEK)
            val dekGenerator = KeyGenerator.getInstance("AES")
            dekGenerator.init(256, SecureRandom())
            val dek = dekGenerator.generateKey()

            // 2. Encrypt DEK with Android Keystore (Master Key)
            val kekCipher = SecureKeyManager.createEncryptCipher()
            val dekIv = kekCipher.iv
            val dekCiphertext = kekCipher.doFinal(dek.encoded)

            // 3. Encrypt the large payload with DEK (using software AES/GCM)
            val dataCipher = Cipher.getInstance("AES/GCM/NoPadding")
            val dataIv = ByteArray(SecureKeyManager.GCM_IV_LENGTH).apply { SecureRandom().nextBytes(this) }
            dataCipher.init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(SecureKeyManager.GCM_TAG_LENGTH, dataIv))
            val dataCiphertext = dataCipher.doFinal(plainBytes)

            // Build blob: VERSION(2) + DEK_IV_LEN(4) + DEK_IV + DEK_CT_LEN(4) + DEK_CT + DATA_IV_LEN(4) + DATA_IV + DATA_CT
            val blobWithoutHmac = ByteArrayOutputStream().use { baos ->
                baos.write(BLOB_VERSION.toInt())
                
                baos.write(intToBytes(dekIv.size))
                baos.write(dekIv)
                
                baos.write(intToBytes(dekCiphertext.size))
                baos.write(dekCiphertext)
                
                baos.write(intToBytes(dataIv.size))
                baos.write(dataIv)
                
                baos.write(dataCiphertext)
                baos.toByteArray()
            }

            // Append HMAC
            val hmac = IntegrityVerifier.computeHmac(blobWithoutHmac)

            // Atomic write: write to temp file then rename
            val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
            FileOutputStream(tempFile).use { fos ->
                fos.write(blobWithoutHmac)
                fos.write(hmac)
                fos.fd.sync() // Force flush to disk
            }

            // Atomic rename
            if (!tempFile.renameTo(targetFile)) {
                // Fallback: copy and delete
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Logger.d("Encrypted ${plainBytes.size} bytes → ${targetFile.name} (${targetFile.length()} bytes on disk)")
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Logger.e("Encryption failed for ${targetFile.name}: ${e.message}", e)
            throw SecurityException("Failed to encrypt data to ${targetFile.name}", e)
        }
    }

    /**
     * Reads an encrypted blob from file, verifies integrity, and decrypts.
     *
     * @param sourceFile The file containing the encrypted blob
     * @return The decrypted plaintext bytes
     * @throws SecurityException if integrity check fails or decryption fails
     */
    fun decryptFromFile(sourceFile: File): ByteArray {
        if (!sourceFile.exists()) {
            throw SecurityException("Encrypted file not found: ${sourceFile.name}")
        }

        try {
            val blobBytes = FileInputStream(sourceFile).use { it.readBytes() }

            // Verify HMAC and extract data portion
            val dataBytes = IntegrityVerifier.verifyAndExtractData(blobBytes)

            // Parse blob header
            val inputStream = ByteArrayInputStream(dataBytes)
            val version = inputStream.read().toByte()

            if (version == BLOB_VERSION) {
                // Envelope Encryption (V2)
                val dekIvLength = bytesToInt(readStreamBytes(inputStream, 4))
                val dekIv = readStreamBytes(inputStream, dekIvLength)
                
                val dekCtLength = bytesToInt(readStreamBytes(inputStream, 4))
                val dekCiphertext = readStreamBytes(inputStream, dekCtLength)
                
                val dataIvLength = bytesToInt(readStreamBytes(inputStream, 4))
                val dataIv = readStreamBytes(inputStream, dataIvLength)
                
                val dataCiphertext = inputStream.readBytes()
                
                // Decrypt DEK
                val kekCipher = SecureKeyManager.createDecryptCipher(dekIv)
                val dekBytes = kekCipher.doFinal(dekCiphertext)
                val dek = SecretKeySpec(dekBytes, "AES")
                
                // Decrypt DATA
                val dataCipher = Cipher.getInstance("AES/GCM/NoPadding")
                dataCipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(SecureKeyManager.GCM_TAG_LENGTH, dataIv))
                val plainBytes = dataCipher.doFinal(dataCiphertext)
                
                Logger.d("Decrypted (v2) ${sourceFile.name}: ${plainBytes.size} bytes")
                return plainBytes
                
            } else if (version == LEGACY_BLOB_VERSION) {
                // Legacy Direct Keystore Encryption (V1)
                val ivLengthBytes = readStreamBytes(inputStream, 4)
                val ivLength = bytesToInt(ivLengthBytes)

                if (ivLength != SecureKeyManager.GCM_IV_LENGTH) {
                    throw SecurityException("Invalid IV length: $ivLength")
                }

                val iv = readStreamBytes(inputStream, ivLength)
                val ciphertext = inputStream.readBytes()

                // Decrypt
                val cipher = SecureKeyManager.createDecryptCipher(iv)
                val plainBytes = cipher.doFinal(ciphertext)

                Logger.d("Decrypted (v1) ${sourceFile.name}: ${plainBytes.size} bytes")
                return plainBytes
            } else {
                throw SecurityException(
                    "Unsupported blob version: $version"
                )
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            Logger.e("Decryption failed for ${sourceFile.name}: ${e.message}", e)
            throw SecurityException("Failed to decrypt ${sourceFile.name}", e)
        }
    }

    // ----------------------------------------------------------------
    // Checkpoint (Model Weights)
    // ----------------------------------------------------------------

    /**
     * Encrypts a model weights map and writes to file.
     * The weights map is serialized via ObjectOutputStream before encryption.
     *
     * @param weightsMap The model weights from TFLite save signature
     * @param targetFile The encrypted checkpoint file
     */
    fun encryptCheckpoint(weightsMap: HashMap<String, Any>, targetFile: File) {
        val serialBytes = ByteArrayOutputStream().use { baos ->
            ObjectOutputStream(baos).use { oos ->
                oos.writeObject(weightsMap)
            }
            baos.toByteArray()
        }
        encryptToFile(serialBytes, targetFile)
        Logger.d("Checkpoint encrypted: ${weightsMap.size} weight tensors → ${targetFile.name}")
    }

    /**
     * Decrypts and deserializes a model weights map from an encrypted checkpoint file.
     *
     * @param sourceFile The encrypted checkpoint file
     * @return The deserialized weights map, or null if decryption/deserialization fails
     */
    fun decryptCheckpoint(sourceFile: File): HashMap<String, Any>? {
        return try {
            val plainBytes = decryptFromFile(sourceFile)

            ByteArrayInputStream(plainBytes).use { bais ->
                ObjectInputStream(bais).use { ois ->
                    @Suppress("UNCHECKED_CAST")
                    ois.readObject() as? HashMap<String, Any>
                }
            }
        } catch (e: SecurityException) {
            Logger.e("Checkpoint integrity/decryption failed: ${e.message}", e)
            null
        } catch (e: Exception) {
            Logger.e("Checkpoint deserialization failed: ${e.message}", e)
            null
        }
    }

    // ----------------------------------------------------------------
    // Threshold
    // ----------------------------------------------------------------

    /**
     * Encrypts a single Float threshold value and writes to file.
     */
    fun encryptThreshold(threshold: Float, targetFile: File) {
        val bytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putFloat(threshold).array()
        encryptToFile(bytes, targetFile)
        Logger.d("Threshold encrypted: $threshold → ${targetFile.name}")
    }

    /**
     * Decrypts a Float threshold from an encrypted file.
     *
     * @return The threshold value, or null if decryption fails
     */
    fun decryptThreshold(sourceFile: File): Float? {
        return try {
            val plainBytes = decryptFromFile(sourceFile)
            if (plainBytes.size < 4) {
                Logger.e("Decrypted threshold data too small: ${plainBytes.size} bytes")
                return null
            }
            ByteBuffer.wrap(plainBytes).order(ByteOrder.BIG_ENDIAN).float
        } catch (e: SecurityException) {
            Logger.e("Threshold integrity/decryption failed: ${e.message}", e)
            null
        } catch (e: Exception) {
            Logger.e("Threshold decryption failed: ${e.message}", e)
            null
        }
    }

    // ----------------------------------------------------------------
    // Scaler Parameters (min[], max[])
    // ----------------------------------------------------------------

    /**
     * Encrypts MinMaxScaler parameters (min and max arrays) and writes to file.
     *
     * Binary format before encryption:
     * [featureCount: Int][min0: Float]...[minN: Float][max0: Float]...[maxN: Float]
     */
    fun encryptScalerParams(min: FloatArray, max: FloatArray, targetFile: File) {
        require(min.size == max.size) { "Min and max arrays must have the same size" }

        val byteSize = 4 + (min.size * 4 * 2) // int header + 2 float arrays
        val buffer = ByteBuffer.allocate(byteSize).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(min.size)
        for (v in min) buffer.putFloat(v)
        for (v in max) buffer.putFloat(v)

        encryptToFile(buffer.array(), targetFile)
        Logger.d("Scaler params encrypted: ${min.size} features → ${targetFile.name}")
    }

    /**
     * Decrypts MinMaxScaler parameters from an encrypted file.
     *
     * @return Pair<min, max> FloatArrays, or null if decryption fails
     */
    fun decryptScalerParams(sourceFile: File): Pair<FloatArray, FloatArray>? {
        return try {
            val plainBytes = decryptFromFile(sourceFile)
            val buffer = ByteBuffer.wrap(plainBytes).order(ByteOrder.BIG_ENDIAN)

            val featureCount = buffer.int
            if (featureCount <= 0 || plainBytes.size < 4 + featureCount * 8) {
                Logger.e("Invalid scaler data: featureCount=$featureCount, bytes=${plainBytes.size}")
                return null
            }

            val min = FloatArray(featureCount) { buffer.float }
            val max = FloatArray(featureCount) { buffer.float }

            Pair(min, max)
        } catch (e: SecurityException) {
            Logger.e("Scaler integrity/decryption failed: ${e.message}", e)
            null
        } catch (e: Exception) {
            Logger.e("Scaler decryption failed: ${e.message}", e)
            null
        }
    }

    // ----------------------------------------------------------------
    // Re-enrollment Vectors (List<List<Float>>)
    // ----------------------------------------------------------------

    /**
     * Encrypts re-enrollment vectors and writes to file.
     */
    fun encryptVectors(vectors: List<List<Float>>, targetFile: File) {
        val serialBytes = ByteArrayOutputStream().use { baos ->
            ObjectOutputStream(baos).use { oos ->
                oos.writeObject(ArrayList(vectors))
            }
            baos.toByteArray()
        }
        encryptToFile(serialBytes, targetFile)
        Logger.d("Vectors encrypted: ${vectors.size} vectors → ${targetFile.name}")
    }

    /**
     * Decrypts re-enrollment vectors from an encrypted file.
     */
    fun decryptVectors(sourceFile: File): List<List<Float>>? {
        return try {
            val plainBytes = decryptFromFile(sourceFile)

            ByteArrayInputStream(plainBytes).use { bais ->
                ObjectInputStream(bais).use { ois ->
                    @Suppress("UNCHECKED_CAST")
                    ois.readObject() as? List<List<Float>>
                }
            }
        } catch (e: SecurityException) {
            Logger.e("Vectors integrity/decryption failed: ${e.message}", e)
            null
        } catch (e: Exception) {
            Logger.e("Vectors deserialization failed: ${e.message}", e)
            null
        }
    }

    // ----------------------------------------------------------------
    // Metadata (JSON String)
    // ----------------------------------------------------------------

    /**
     * Encrypts a metadata JSON string and writes to file.
     */
    fun encryptMetadata(jsonString: String, targetFile: File) {
        encryptToFile(jsonString.toByteArray(Charsets.UTF_8), targetFile)
        Logger.d("Metadata encrypted → ${targetFile.name}")
    }

    /**
     * Decrypts metadata JSON string from encrypted file.
     */
    fun decryptMetadata(sourceFile: File): String? {
        return try {
            val plainBytes = decryptFromFile(sourceFile)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: SecurityException) {
            Logger.e("Metadata integrity/decryption failed: ${e.message}", e)
            null
        } catch (e: Exception) {
            Logger.e("Metadata decryption failed: ${e.message}", e)
            null
        }
    }

    // ----------------------------------------------------------------
    // Collection State (raw binary)
    // ----------------------------------------------------------------

    /**
     * Encrypts raw binary collection state data and writes to file.
     */
    fun encryptCollectionState(stateBytes: ByteArray, targetFile: File) {
        encryptToFile(stateBytes, targetFile)
        Logger.d("Collection state encrypted: ${stateBytes.size} bytes → ${targetFile.name}")
    }

    /**
     * Decrypts collection state from encrypted file.
     */
    fun decryptCollectionState(sourceFile: File): ByteArray? {
        return try {
            decryptFromFile(sourceFile)
        } catch (e: SecurityException) {
            Logger.e("Collection state integrity/decryption failed: ${e.message}", e)
            null
        } catch (e: Exception) {
            Logger.e("Collection state decryption failed: ${e.message}", e)
            null
        }
    }

    // ----------------------------------------------------------------
    // Migration Helpers
    // ----------------------------------------------------------------

    /**
     * Checks if a file appears to be an unencrypted (legacy plaintext) file
     * by examining the first byte. Encrypted blobs always start with BLOB_VERSION (0x01).
     *
     * Heuristic: Java ObjectOutputStream magic bytes are 0xACED (short header).
     * DataOutputStream float starts with arbitrary bytes.
     * Our encrypted format starts with exactly 0x01.
     */
    fun isLegacyPlaintextFile(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false

        return try {
            FileInputStream(file).use { fis ->
                val firstByte = fis.read()
                // Our encrypted blob always starts with version byte 0x01 or 0x02
                // Java ObjectOutputStream starts with 0xAC (magic)
                // DataOutputStream float can start with various bytes
                // The only collision risk is if plaintext happens to start with 0x01 or 0x02,
                // but then the IV length field (bytes 2-5) would need to equal 12,
                // which is extremely unlikely for random data
                firstByte != BLOB_VERSION.toInt() && firstByte != LEGACY_BLOB_VERSION.toInt()
            }
        } catch (e: Exception) {
            Logger.e("Error checking file format: ${e.message}")
            true // Assume plaintext on error (safer to re-encrypt)
        }
    }

    // ----------------------------------------------------------------
    // Utility
    // ----------------------------------------------------------------

    private fun intToBytes(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array()
    }

    private fun bytesToInt(bytes: ByteArray): Int {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun readStreamBytes(inputStream: ByteArrayInputStream, length: Int): ByteArray {
        val buf = ByteArray(length)
        inputStream.read(buf)
        return buf
    }
}
