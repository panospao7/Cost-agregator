package com.yourname.expensetracker.domain.notification.capture

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
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
 *
 * Plaintext framing (RP-10 10c, P1-008): the five payload fields are
 * framed BEFORE encryption and parsed AFTER decryption using a presence
 * byte per field (0 = null, 1 = present) followed, when present, by a
 * 4-byte big-endian BYTE length and the UTF-8 bytes. This keeps null vs.
 * empty distinct and makes content containing NUL characters round-trip
 * exactly (the old NUL-join framing collided with such content).
 * Decryption falls back to the legacy NUL-split parse for in-flight
 * old-framed rows during the upgrade window (see [parseLegacyFrame]).
 */
@Singleton
class NotificationTransientPayloadCrypto @Inject constructor(
    private val keyProvider: NotificationTransientKeyProvider
) {
    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val FIELD_COUNT = 5
        private const val PRESENT = 1
        private const val ABSENT = 0
    }

    fun encrypt(payload: NotificationTransientPayload): EncryptedPayload {
        val key = keyProvider.getOrCreateSecretKey(NotificationTransientKeyProvider.CURRENT_VERSION)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv

        val plaintext = frame(payload)
        val ciphertext = cipher.doFinal(plaintext)
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
        // Ordering subtlety: a SUCCESSFUL keystore decrypt whose plaintext
        // fails strict new-frame parsing is NOT a decrypt failure — only the
        // parse falls back to the legacy framing (in-flight old rows).
        // If both parses fail, preserve the existing decrypt-failure contract:
        // a GeneralSecurityException with a fixed, content-free message.
        return parseFrame(decrypted)
            ?: parseLegacyFrame(decrypted)
            ?: throw GeneralSecurityException("Transient payload framing parse failed")
    }

    // ── New length-prefixed framing (P1-008) ────────────────────────────

    private fun frame(payload: NotificationTransientPayload): ByteArray {
        val bytes = ByteArrayOutputStream(64)
        val out = DataOutputStream(bytes)
        listOf(payload.title, payload.text, payload.bigText, payload.subText, payload.extrasJson)
            .forEach { field ->
                if (field == null) {
                    out.writeByte(ABSENT)
                } else {
                    val encoded = field.toByteArray(StandardCharsets.UTF_8)
                    out.writeByte(PRESENT)
                    out.writeInt(encoded.size)
                    out.write(encoded)
                }
            }
        return bytes.toByteArray()
    }

    /** Strict new-framing parse; returns null when the plaintext is not a valid frame. */
    private fun parseFrame(plaintext: ByteArray): NotificationTransientPayload? {
        var offset = 0
        val fields = arrayOfNulls<String>(FIELD_COUNT)
        for (i in 0 until FIELD_COUNT) {
            if (offset >= plaintext.size) return null
            val presence = plaintext[offset++].toInt()
            if (presence == ABSENT) continue
            if (presence != PRESENT || offset + Int.SIZE_BYTES > plaintext.size) return null
            val length = ((plaintext[offset].toInt() and 0xFF) shl 24) or
                ((plaintext[offset + 1].toInt() and 0xFF) shl 16) or
                ((plaintext[offset + 2].toInt() and 0xFF) shl 8) or
                (plaintext[offset + 3].toInt() and 0xFF)
            offset += Int.SIZE_BYTES
            if (length < 0 || length > plaintext.size - offset) return null
            fields[i] = String(plaintext, offset, length, StandardCharsets.UTF_8)
            offset += length
        }
        // Frame must be consumed exactly; a trailing byte means not our framing.
        if (offset != plaintext.size) return null
        return NotificationTransientPayload(fields[0], fields[1], fields[2], fields[3], fields[4])
    }

    /**
     * UPGRADE-PERIOD FALLBACK (RP-10 10c, D-B): legacy NUL-split framing used
     * before the length-prefixed rewrite. Conflates null with empty and
     * misparses content containing NUL — kept only so old-framed rows remain
     * readable during the upgrade; a candidate for deletion once no
     * old-framed rows remain. No version flag by decision (out of scope).
     */
    private fun parseLegacyFrame(plaintext: ByteArray): NotificationTransientPayload? {
        val text = String(plaintext, Charsets.UTF_8)
        if ("\u0000" !in text) return null
        val parts = text.split("\u0000")
        return NotificationTransientPayload(
            title = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
            text = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
            bigText = parts.getOrNull(2)?.takeIf { it.isNotEmpty() },
            subText = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
            extrasJson = parts.getOrNull(4)?.takeIf { it.isNotEmpty() }
        )
    }
}
