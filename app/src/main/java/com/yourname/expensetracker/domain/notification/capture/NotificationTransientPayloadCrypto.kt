package com.yourname.expensetracker.domain.notification.capture

import android.util.Base64
import java.io.ByteArrayOutputStream
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
 *
 * ## Wire formats (P1-008)
 *
 * The plaintext handed to the cipher is one of two formats, dispatched on the
 * FIRST byte after [Cipher.doFinal] in [decrypt]:
 *
 *  - **Legacy (v1 serialization, read-only compatibility):** the five fields
 *    (title, text, bigText, subText, extrasJson) NUL-joined into a UTF-8
 *    string, with nulls collapsed to empty and empty indistinguishable from
 *    null. Its first byte is the first UTF-8 byte of the title (or 0x00 when
 *    the title was null), which is never 0xFF because 0xFF is not a valid
 *    UTF-8 byte anywhere — so first-byte dispatch is deterministic. Legacy
 *    payloads are parsed exactly as the old code parsed them (including the
 *    embedded-NUL field-shifting defect); no repair is attempted on read.
 *
 *  - **Framed (current, written by [encrypt]):**
 *    ```
 *    byte 0        format marker 0xFF
 *    per field, in fixed order title, text, bigText, subText, extrasJson:
 *      byte        presence: 0x00 = null (nothing follows), 0x01 = present
 *      if present:
 *        4 bytes   unsigned big-endian UTF-8 BYTE length (not char count)
 *        N bytes   UTF-8 content (length 0 = empty string, distinct from null)
 *    ```
 *    Embedded NULs inside field content are preserved exactly; null and empty
 *    remain distinct via the presence bit.
 *
 * No Room migration or key-version bump is needed for the framed format: the
 * framing lives entirely inside the ciphertext, so GCM integrity already
 * authenticates the marker and both formats decrypt with the same key.
 */
@Singleton
class NotificationTransientPayloadCrypto @Inject constructor(
    private val keyProvider: NotificationTransientKeyProvider
) {
    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12

        /** First byte of the framed plaintext format; see class KDoc. */
        private const val FRAMED_MARKER: Byte = 0xFF.toByte()

        private const val PRESENCE_NULL: Byte = 0x00
        private const val PRESENCE_PRESENT: Byte = 0x01
    }

    fun encrypt(payload: NotificationTransientPayload): EncryptedPayload {
        val key = keyProvider.getOrCreateSecretKey(NotificationTransientKeyProvider.CURRENT_VERSION)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val nonce = cipher.iv

        val ciphertext = cipher.doFinal(frame(payload))
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
        return if (decrypted.isNotEmpty() && decrypted[0] == FRAMED_MARKER) {
            parseFramed(decrypted)
        } else {
            parseLegacy(decrypted)
        }
    }

    /**
     * Serializes [payload] into the framed plaintext format (see class KDoc)
     * before encryption. Field order is fixed; presence bits keep null and
     * empty distinct; 4-byte unsigned big-endian lengths are UTF-8 BYTE
     * lengths, so embedded NULs inside content need no escaping.
     */
    private fun frame(payload: NotificationTransientPayload): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(FRAMED_MARKER.toInt())
        for (value in listOf(
            payload.title,
            payload.text,
            payload.bigText,
            payload.subText,
            payload.extrasJson
        )) {
            if (value == null) {
                out.write(PRESENCE_NULL.toInt())
            } else {
                out.write(PRESENCE_PRESENT.toInt())
                val bytes = value.toByteArray(Charsets.UTF_8)
                val length = bytes.size
                out.write((length ushr 24) and 0xFF)
                out.write((length ushr 16) and 0xFF)
                out.write((length ushr 8) and 0xFF)
                out.write(length and 0xFF)
                out.write(bytes)
            }
        }
        return out.toByteArray()
    }

    /**
     * Strict parser for the framed format. Any structural violation (unknown
     * presence byte, declared length beyond the remaining bytes, truncated
     * header) fails closed with a constant message carrying no content or
     * offset detail. Trailing bytes after the fifth field are also a
     * structural violation (future formats require a new format marker;
     * this parser fails closed).
     */
    private fun parseFramed(decrypted: ByteArray): NotificationTransientPayload {
        var offset = 1 // marker already verified by the caller
        fun readPresence(): Boolean {
            if (offset >= decrypted.size) {
                throw IllegalStateException("Malformed framed transient payload")
            }
            val presence = decrypted[offset]
            offset += 1
            return when (presence) {
                PRESENCE_NULL -> false
                PRESENCE_PRESENT -> true
                else -> throw IllegalStateException("Malformed framed transient payload")
            }
        }

        fun readString(): String? {
            if (!readPresence()) return null
            if (offset + 4 > decrypted.size) {
                throw IllegalStateException("Malformed framed transient payload")
            }
            var length = 0
            repeat(4) {
                length = (length shl 8) or (decrypted[offset].toInt() and 0xFF)
                offset += 1
            }
            if (length < 0 || offset + length > decrypted.size) {
                throw IllegalStateException("Malformed framed transient payload")
            }
            val value = String(decrypted, offset, length, Charsets.UTF_8)
            offset += length
            return value
        }

        val title = readString()
        val text = readString()
        val bigText = readString()
        val subText = readString()
        val extrasJson = readString()
        if (offset != decrypted.size) {
            throw IllegalStateException("Malformed framed transient payload")
        }
        return NotificationTransientPayload(title, text, bigText, subText, extrasJson)
    }

    /**
     * Legacy parser, preserved verbatim from the pre-framing implementation:
     * NUL-split with null/empty collapsed. Old rows keep decrypting exactly as
     * they did before (embedded NULs in content shift subsequent fields; no
     * repair is attempted on read).
     */
    private fun parseLegacy(decrypted: ByteArray): NotificationTransientPayload {
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
