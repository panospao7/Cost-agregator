package com.yourname.expensetracker.domain.notification.capture

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class NotificationTransientPayload(
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val extrasJson: String?
)

data class EncryptedPayload(
    val ciphertext: String,
    val nonce: String,
    val version: Int
)

/**
 * AES-256/GCM/NoPadding encryption for transient notification payloads.
 * Uses Android Keystore-backed keys (hardware-backed when available).
 * Random nonce per encryption ensures semantic security.
 */
@Singleton
class NotificationTransientPayloadCrypto @Inject constructor(
    private val keyProvider: NotificationTransientKeyProvider
) {
    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }

    fun encrypt(payload: NotificationTransientPayload): EncryptedPayload {
        val key = keyProvider.getOrCreateSecretKey(NotificationTransientKeyProvider.CURRENT_VERSION)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv

        val plaintext = buildString {
            append(payload.title ?: "")
            append("\u0000")
            append(payload.text ?: "")
            append("\u0000")
            append(payload.bigText ?: "")
            append("\u0000")
            append(payload.subText ?: "")
            append("\u0000")
            append(payload.extrasJson ?: "")
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            version = NotificationTransientKeyProvider.CURRENT_VERSION
        )
    }

    fun decrypt(ciphertext: String, nonce: String, version: Int): NotificationTransientPayload {
        val key = keyProvider.getOrCreateSecretKey(version)
        val cipher = Cipher.getInstance(ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, Base64.decode(nonce, Base64.NO_WRAP))
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val decrypted = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
        val parts = String(decrypted, Charsets.UTF_8).split("\u0000")
        return NotificationTransientPayload(
            title = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
            text = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
            bigText = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
            subText = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
            extrasJson = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }
        )
    }
}
