package com.yourname.expensetracker.domain.notification.capture

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * RP-10 10c (P1-008): collision-safe length-prefixed framing for the
 * transient notification payload, with legacy NUL-join fallback for
 * in-flight rows encrypted before the framing change.
 *
 * Uses the REAL [NotificationTransientPayloadCrypto] with an in-memory
 * [NotificationTransientKeyProvider] — the same constructor path as the
 * intake tests (which use a relaxed mock). Robolectric supplies a working
 * android.util.Base64 and AES/GCM JCE so the round-trips are real crypto,
 * not stubs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NotificationTransientPayloadCryptoTest {

    /** Same-shape provider as the relaxed mocks use, but with a real key. */
    private class InMemoryKeyProvider : NotificationTransientKeyProvider {
        private val random = SecureRandom()
        private val keys = mutableMapOf<Int, SecretKey>()
        override fun getOrCreateSecretKey(version: Int): SecretKey =
            keys.getOrPut(version) {
                KeyGenerator.getInstance("AES").apply { init(256, random) }.generateKey()
            }
    }

    private val keyProvider = InMemoryKeyProvider()
    private val crypto = NotificationTransientPayloadCrypto(keyProvider)

    /**
     * Encrypts hand-built PLAINTEXT bytes through the real crypto path so
     * [decrypt] receives a validly encrypted payload (e.g. legacy-framed
     * content produced by the pre-P1-008 NUL-join framing, or garbage that
     * must fail both parses). Uses the SAME provider instance as [crypto]
     * so decrypt can recover the key.
     */
    private fun encryptPlaintext(plaintext: ByteArray): EncryptedPayload {
        val key = keyProvider.getOrCreateSecretKey(
            NotificationTransientKeyProvider.CURRENT_VERSION
        )
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return EncryptedPayload(
            ciphertext = Base64.encodeToString(
                cipher.doFinal(plaintext), Base64.NO_WRAP
            ),
            nonce = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            version = NotificationTransientKeyProvider.CURRENT_VERSION
        )
    }

    private fun decrypt(payload: EncryptedPayload) = crypto.decrypt(
        ciphertext = payload.ciphertext,
        nonce = payload.nonce,
        version = payload.version
    )

    // --- New-framing round-trips ---

    @Test
    fun `all fields non-null round-trips exactly`() {
        val payload = NotificationTransientPayload(
            title = "Payment received",
            text = "€42,50 from ACME",
            bigText = "Long body line 1\nLong body line 2",
            subText = "Sub text",
            extrasJson = "{\"key\":\"value\"}"
        )

        val decrypted = decrypt(crypto.encrypt(payload))

        assertEquals(payload, decrypted)
    }

    @Test
    fun `all fields null round-trips`() {
        val payload = NotificationTransientPayload(null, null, null, null, null)

        assertEquals(payload, decrypt(crypto.encrypt(payload)))
    }

    @Test
    fun `mixed null and empty stays distinct after round-trip`() {
        // The legacy NUL-join framing conflated null with empty; the new
        // presence-bit framing must preserve the difference exactly.
        val payload = NotificationTransientPayload(
            title = "",
            text = null,
            bigText = "present",
            subText = null,
            extrasJson = ""
        )

        val decrypted = decrypt(crypto.encrypt(payload))

        assertEquals("", decrypted.title)
        assertNull(decrypted.text)
        assertEquals("present", decrypted.bigText)
        assertNull(decrypted.subText)
        assertEquals("", decrypted.extrasJson)
    }

    @Test
    fun `embedded NUL inside a field value round-trips exactly`() {
        // Collision bug: a NUL inside content used to shift the legacy split.
        val payload = NotificationTransientPayload(
            title = "before\u0000after",
            text = "a\u0000\u0000b",
            bigText = null,
            subText = "\u0000",
            extrasJson = null
        )

        assertEquals(payload, decrypt(crypto.encrypt(payload)))
    }

    @Test
    fun `multi-byte UTF-8 content round-trips`() {
        // Frame length must be BYTE length, not char count.
        val payload = NotificationTransientPayload(
            title = "Πληρωμή",
            text = "Ελήφθη πληρωμή 42,50 €",
            bigText = null,
            subText = "Τράπεζα",
            extrasJson = null
        )

        assertEquals(payload, decrypt(crypto.encrypt(payload)))
    }

    // --- Legacy fallback (upgrade period, D-B) ---

    @Test
    fun `legacy NUL-joined plaintext falls back with documented null-empty conflation`() {
        // Plaintext built the OLD way (pre-P1-008 NUL-join framing).
        val legacy = buildString {
            append("Title")
            append("\u0000")
            append("Body")
            append("\u0000")
            append("") // bigText: legacy framing conflated empty with null
            append("\u0000")
            append("") // subText
            append("\u0000")
            append("json")
        }

        val decrypted = decrypt(encryptPlaintext(legacy.toByteArray(Charsets.UTF_8)))

        // Documented LEGACY semantics on the fallback path: empty → null.
        assertEquals("Title", decrypted.title)
        assertEquals("Body", decrypted.text)
        assertNull(decrypted.bigText)
        assertNull(decrypted.subText)
        assertEquals("json", decrypted.extrasJson)
    }

    // --- Both parses fail → existing decrypt-failure contract ---

    @Test
    fun `garbage plaintext that fails both parses surfaces the decrypt-failure contract`() {
        // A validly encrypted plaintext that is neither new-framed nor
        // legacy NUL-joined must fail the same way a failed keystore decrypt
        // does today: GeneralSecurityException (fixed, content-free message).
        val encrypted = encryptPlaintext(byteArrayOf(0x01, 0x02, 0x03))

        assertThrows(java.security.GeneralSecurityException::class.java) {
            decrypt(encrypted)
        }
    }

    @Test
    fun `truncated frame plaintext declares a length exceeding remaining bytes and fails both parses`() {
        // Hand-built NEW-framed plaintext whose first present field declares
        // a BYTE length far larger than the bytes actually remaining after
        // the length header (P1-005 overflow bound check): strict parseFrame
        // must reject it. The declared length deliberately uses no zero
        // bytes and the plaintext ends after the header, so the raw bytes
        // contain no NUL and the legacy NUL-split fallback must reject it
        // too → both parses fail → GeneralSecurityException.
        val out = java.io.ByteArrayOutputStream()
        out.write(0x01) // field 0 present
        out.write(0x01) // length byte 3 (MSB)
        out.write(0x02) // length byte 2
        out.write(0x03) // length byte 1
        out.write(0x04) // length byte 0 (LSB): declared length 0x01020304 > remaining 0 bytes
        // no field bytes follow — truncated on purpose
        val encrypted = encryptPlaintext(out.toByteArray())

        assertThrows(java.security.GeneralSecurityException::class.java) {
            decrypt(encrypted)
        }
    }
}
