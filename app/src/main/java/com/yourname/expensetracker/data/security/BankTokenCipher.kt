package com.yourname.expensetracker.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts/decrypts bank API tokens using Android Keystore-backed AES/GCM.
 *
 * Stored payload format:
 *   enc:v1:<base64-iv>:<base64-ciphertext>
 */
object BankTokenCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "expense_tracker_bank_tokens_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PREFIX = "enc:v1:"

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    fun encryptIfNeeded(value: String?): String? {
        if (value == null) return null
        if (isEncrypted(value)) return value
        return encrypt(value)
    }

    /**
     * Result of a token decryption attempt.
     * P10-PR1 (NEW-P10-002): Callers can distinguish key invalidation from other failures.
     */
    sealed interface DecryptResult {
        data class Success(val plaintext: String) : DecryptResult
        data object KeyInvalidated : DecryptResult
        data class Failed(val reason: String) : DecryptResult
    }

    fun decryptIfNeeded(value: String?): String? {
        if (value == null) return null
        if (!isEncrypted(value)) return value
        return when (val result = decryptWithResult(value)) {
            is DecryptResult.Success -> result.plaintext
            is DecryptResult.KeyInvalidated -> null
            is DecryptResult.Failed -> null
        }
    }

    /**
     * P10-PR1 (NEW-P10-002): Typed decryption that surfaces key invalidation.
     * Callers should check for [DecryptResult.KeyInvalidated] and prompt re-authentication.
     */
    fun decryptWithResult(value: String?): DecryptResult {
        if (value == null) return DecryptResult.Failed("null input")
        if (!isEncrypted(value)) return DecryptResult.Success(value)

        val parts = value.removePrefix(PREFIX).split(':')
        if (parts.size != 2) return DecryptResult.Failed("invalid format")

        return try {
            val iv = Base64.getDecoder().decode(parts[0])
            val ciphertext = Base64.getDecoder().decode(parts[1])

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )

            val plaintext = cipher.doFinal(ciphertext)
            DecryptResult.Success(String(plaintext, Charsets.UTF_8))
        } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
            DecryptResult.KeyInvalidated
        } catch (e: Exception) {
            DecryptResult.Failed(e.javaClass.simpleName)
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())

        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))

        val ivB64 = Base64.getEncoder().encodeToString(iv)
        val cipherB64 = Base64.getEncoder().encodeToString(ciphertext)
        return "$PREFIX$ivB64:$cipherB64"
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
