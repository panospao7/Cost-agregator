package com.yourname.expensetracker.data.privacy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * At-rest encryption service using Android Keystore (AES-256-GCM).
 *
 * Encrypts sensitive ML model data before writing to disk and decrypts on read.
 * The encryption key is stored in the hardware-backed Android Keystore and is
 * not extractable. Each encryption generates a fresh 12-byte IV which is
 * prepended to the ciphertext.
 *
 * ## Legacy fallback
 * If Keystore operations fail (unsupported device, key corruption, etc.), the
 * service throws — callers should handle gracefully and fall back to plaintext
 * I/O for legacy files.
 *
 * Security assumptions:
 * - AES-256-GCM provides authenticated encryption (confidentiality + integrity).
 * - Key is bound to the Android Keystore, scoped to this app's UID.
 * - IV is randomly generated per encryption call and stored alongside ciphertext.
 */
@Singleton
class AtRestEncryptionService @Inject constructor() {
    companion object {
        private const val KEY_ALIAS = "expensetracker_at_rest_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val IV_LENGTH = 12
    }

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts [plaintext] bytes and returns IV + ciphertext.
     *
     * @throws Exception if encryption fails (keystore error, etc.).
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext  // prepend 12-byte IV
    }

    /**
     * Decrypts [encrypted] bytes (IV + ciphertext) and returns plaintext.
     *
     * @throws Exception if decryption fails (wrong key, tampered data, etc.).
     */
    fun decrypt(encrypted: ByteArray): ByteArray {
        val key = getOrCreateKey()
        val iv = encrypted.copyOfRange(0, IV_LENGTH)
        val ciphertext = encrypted.copyOfRange(IV_LENGTH, encrypted.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }
}
