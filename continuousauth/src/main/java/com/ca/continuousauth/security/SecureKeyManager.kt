package com.ca.continuousauth.security

import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ca.continuousauth.utils.Logger
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

/**
 * SecureKeyManager (Fixed Production Version)
 *
 * - AES-256-GCM encryption using Android Keystore
 * - StrongBox used only when available
 * - Safe fallback to TEE/software-backed keystore
 * - Stable across emulator + real devices
 */
object SecureKeyManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val MASTER_KEY_ALIAS = "continuousauth_master_key"
    private const val HMAC_KEY_ALIAS = "continuousauth_hmac_key"

    const val GCM_IV_LENGTH = 12
    const val GCM_TAG_LENGTH = 128
    private const val AES_KEY_SIZE = 256

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    // ----------------------------------------------------------------
    // StrongBox Detection
    // ----------------------------------------------------------------

    private fun isStrongBoxAvailable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                val pm = android.app.Application().packageManager
                pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            } catch (e: Exception) {
                false
            }
        } else false
    }

    // ----------------------------------------------------------------
    // KeySpec Builder (Single Source of Truth)
    // ----------------------------------------------------------------

    private fun buildKeySpec(alias: String, useStrongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && useStrongBox) {
            builder.setIsStrongBoxBacked(true)
        }

        return builder.build()
    }

    // ----------------------------------------------------------------
    // Master Key
    // ----------------------------------------------------------------

    @Synchronized
    fun getOrCreateMasterKey(): SecretKey {
        return try {
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                val entry = keyStore.getEntry(MASTER_KEY_ALIAS, null)
                        as? KeyStore.SecretKeyEntry
                entry?.secretKey ?: generateMasterKey()
            } else {
                generateMasterKey()
            }
        } catch (e: Exception) {
            Logger.e("Master key retrieval failed, regenerating: ${e.message}", e)
            generateMasterKey()
        }
    }

    private fun generateMasterKey(): SecretKey {
        Logger.d("Generating AES-256-GCM master key")

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )

        val useStrongBox = isStrongBoxAvailable()

        try {
            keyGenerator.init(buildKeySpec(MASTER_KEY_ALIAS, useStrongBox))
        } catch (e: Exception) {
            Logger.d("StrongBox failed, retrying without StrongBox")

            keyGenerator.init(buildKeySpec(MASTER_KEY_ALIAS, false))
        }

        return keyGenerator.generateKey().also {
            Logger.d("Master key generated successfully")
        }
    }

    // ----------------------------------------------------------------
    // HMAC Key
    // ----------------------------------------------------------------

    @Synchronized
    fun getOrCreateHmacKey(): SecretKey {
        return try {
            if (keyStore.containsAlias(HMAC_KEY_ALIAS)) {
                val entry = keyStore.getEntry(HMAC_KEY_ALIAS, null)
                        as? KeyStore.SecretKeyEntry
                entry?.secretKey ?: generateHmacKey()
            } else {
                generateHmacKey()
            }
        } catch (e: Exception) {
            Logger.e("HMAC key error: ${e.message}", e)
            generateHmacKey()
        }
    }

    private fun generateHmacKey(): SecretKey {
        Logger.d("Generating HMAC-SHA256 key")

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
            KEYSTORE_PROVIDER
        )

        val useStrongBox = isStrongBoxAvailable()

        try {
            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    HMAC_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setKeySize(256)
                    .apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && useStrongBox) {
                            setIsStrongBoxBacked(true)
                        }
                    }
                    .build()
            )
        } catch (e: Exception) {
            Logger.d("HMAC StrongBox fallback triggered")

            keyGenerator.init(
                KeyGenParameterSpec.Builder(
                    HMAC_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setKeySize(256)
                    .build()
            )
        }

        return keyGenerator.generateKey()
    }

    // ----------------------------------------------------------------
    // Cipher
    // ----------------------------------------------------------------

    fun createEncryptCipher(): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateMasterKey())
        return cipher
    }

    fun createDecryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateMasterKey(), spec)
        return cipher
    }

    // ----------------------------------------------------------------
    // Key Management
    // ----------------------------------------------------------------

    @Synchronized
    fun deleteAllKeys() {
        try {
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
            if (keyStore.containsAlias(HMAC_KEY_ALIAS)) {
                keyStore.deleteEntry(HMAC_KEY_ALIAS)
            }
            Logger.d("All Keystore keys deleted")
        } catch (e: Exception) {
            Logger.e("Key deletion failed: ${e.message}", e)
        }
    }

    fun isMasterKeyAvailable(): Boolean {
        return try {
            keyStore.containsAlias(MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            false
        }
    }

    fun generateIv(): ByteArray {
        return ByteArray(GCM_IV_LENGTH).apply {
            SecureRandom().nextBytes(this)
        }
    }
}