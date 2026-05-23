package com.yourname.expensetracker.domain.notification.capture

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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

@Singleton
class NotificationTransientPayloadCrypto @Inject constructor() {
    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val CURRENT_VERSION = 1
        // NOTE: In production, derive this key from Android Keystore or use EncryptedSharedPreferences.
        // This static key is a placeholder for the encryption infrastructure.
        // Replace with KeyStore-based key before production release.
        private val STATIC_KEY = byteArrayOf(
            0x01, 0x23, 0x45, 0x67, (-119).toByte(), (-85).toByte(), (-51).toByte(), (-17).toByte(),
            (-2).toByte(), (-36).toByte(), (-70).toByte(), (-104).toByte(), 0x76, 0x54, 0x32, 0x10
        )
    }

    fun encrypt(payload: NotificationTransientPayload): EncryptedPayload {
        val nonce = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(nonce)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        val key = SecretKeySpec(STATIC_KEY, KEY_ALGORITHM)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
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
            version = CURRENT_VERSION
        )
    }

    fun decrypt(ciphertext: String, nonce: String, version: Int): NotificationTransientPayload {
        val key = SecretKeySpec(STATIC_KEY, KEY_ALGORITHM)
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
