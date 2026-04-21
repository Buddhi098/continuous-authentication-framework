package com.ca.continuousauth.security

import com.ca.continuousauth.utils.Logger
import javax.crypto.Mac

/**
 * Provides HMAC-SHA256 integrity verification for encrypted model artifacts.
 *
 * Uses the Android Keystore-backed HMAC key from [SecureKeyManager] to compute
 * and verify message authentication codes. This prevents:
 * - Tampering with encrypted files on disk
 * - Bit-flip attacks on ciphertext
 * - Ciphertext substitution attacks
 *
 * The HMAC is computed over the entire encrypted blob (version + IV + ciphertext + GCM tag),
 * providing a second layer of integrity beyond GCM's built-in authentication tag.
 */
object IntegrityVerifier {

    const val HMAC_LENGTH = 32 // SHA-256 output = 32 bytes
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * Computes HMAC-SHA256 over the given data using the Keystore-backed HMAC key.
     *
     * @param data The data to compute the HMAC over (typically the encrypted blob without footer)
     * @return 32-byte HMAC-SHA256 value
     * @throws SecurityException if HMAC computation fails
     */
    fun computeHmac(data: ByteArray): ByteArray {
        return try {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecureKeyManager.getOrCreateHmacKey())
            mac.doFinal(data)
        } catch (e: Exception) {
            Logger.e("HMAC computation failed: ${e.message}", e)
            throw SecurityException("Failed to compute integrity HMAC", e)
        }
    }

    /**
     * Verifies the HMAC of an encrypted blob.
     *
     * @param data The data portion of the blob (everything except the HMAC footer)
     * @param expectedHmac The HMAC footer to verify against (32 bytes)
     * @return true if the HMAC matches, false if the file has been tampered with
     */
    fun verifyHmac(data: ByteArray, expectedHmac: ByteArray): Boolean {
        return try {
            val computedHmac = computeHmac(data)

            // Constant-time comparison to prevent timing attacks
            constantTimeEquals(computedHmac, expectedHmac)
        } catch (e: Exception) {
            Logger.e("HMAC verification failed: ${e.message}", e)
            false
        }
    }

    /**
     * Parses an encrypted blob file and verifies its integrity before returning
     * the data portion for decryption.
     *
     * Blob format: [data...][HMAC-SHA256 (32 bytes)]
     *
     * @param blobBytes The complete encrypted blob including HMAC footer
     * @return The data portion (without HMAC) if verification passes
     * @throws SecurityException if the blob is too small or integrity check fails
     */
    fun verifyAndExtractData(blobBytes: ByteArray): ByteArray {
        if (blobBytes.size <= HMAC_LENGTH) {
            throw SecurityException(
                "Encrypted blob too small: ${blobBytes.size} bytes (minimum ${HMAC_LENGTH + 1})"
            )
        }

        val dataLength = blobBytes.size - HMAC_LENGTH
        val data = blobBytes.copyOfRange(0, dataLength)
        val hmac = blobBytes.copyOfRange(dataLength, blobBytes.size)

        if (!verifyHmac(data, hmac)) {
            Logger.e("INTEGRITY VIOLATION: File HMAC verification failed — possible tampering detected!")
            throw SecurityException(
                "Integrity check failed: encrypted model artifact has been tampered with"
            )
        }

        Logger.d("Integrity verification passed")
        return data
    }

    /**
     * Constant-time byte array comparison to prevent timing side-channel attacks.
     * Returns true only if both arrays are the same length and every byte matches.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false

        var result = 0
        for (i in a.indices) {
            result = result or (a[i].toInt() xor b[i].toInt())
        }
        return result == 0
    }
}
