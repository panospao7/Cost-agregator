package com.yourname.expensetracker.domain.notification.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Round-trip and wire-format tests for [NotificationTransientPayloadCrypto]
 * (RP-10c slice 3, P1-008).
 *
 * Covers:
 *  - framed (length-prefixed, presence-bit) format round trips, including
 *    embedded NULs and the null-vs-empty distinction the presence bit encodes;
 *  - the 0xFF first-byte format marker (pinned against accidental change);
 *  - legacy NUL-joined plaintext read compatibility, warts included (embedded
 *    NULs in legacy content shift fields and can drop extrasJson — this is the
 *    documented legacy behavior, not repaired on read);
 *  - corrupted-ciphertext failure (GCM auth) returning no partial payload;
 *  - key-version passthrough to the [NotificationTransientKeyProvider].
 *
 * Robolectric is required because production code uses android.util.Base64.
 * No MockK: the class under test is synchronous and the fake key provider is a
 * plain hand-rolled implementation.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationTransientPayloadCryptoTest {

    private val keyProvider = FakeNotificationTransientKeyProvider()
    private val crypto = NotificationTransientPayloadCrypto(keyProvider)

    /** Same deterministic bytes the fake provider returns (see file bottom). */
    private val testKey = SecretKeySpec(deterministicTestKeyBytes(), "AES")

    @Test
    fun `round trip preserves all present fields including long multiline text`() {
        val longBigText = buildString {
            append("First line\n")
            repeat(50) { append("line $it — long body text with unicode éü✓ and punctuation;.\n") }
            append("last line")
        }
        val payload = NotificationTransientPayload(
            title = "Salary alert",
            text = "Balance updated",
            bigText = longBigText,
            subText = "ACME Bank",
            extrasJson = "{\"amount\":12345,\"category\":\"income\"}"
        )

        val encrypted = crypto.encrypt(payload)
        val decrypted = crypto.decrypt(encrypted.ciphertext, encrypted.nonce, encrypted.version)

        assertEquals(payload, decrypted)
    }

    @Test
    fun `round trip with all fields null returns all null payload`() {
        val payload = NotificationTransientPayload(null, null, null, null, null)

        val encrypted = crypto.encrypt(payload)
        val decrypted = crypto.decrypt(encrypted.ciphertext, encrypted.nonce, encrypted.version)

        assertEquals(payload, decrypted)
        assertNull(decrypted.title)
        assertNull(decrypted.text)
        assertNull(decrypted.bigText)
        assertNull(decrypted.subText)
        assertNull(decrypted.extrasJson)
    }

    @Test
    fun `null and empty fields stay distinct across the round trip`() {
        // Pins the presence bit: null and "" must not collapse into each other.
        val payload = NotificationTransientPayload(
            title = null,
            text = "",
            bigText = "x",
            subText = null,
            extrasJson = ""
        )

        val encrypted = crypto.encrypt(payload)
        val decrypted = crypto.decrypt(encrypted.ciphertext, encrypted.nonce, encrypted.version)

        assertNull(decrypted.title)
        assertEquals("", decrypted.text)
        assertEquals("x", decrypted.bigText)
        assertNull(decrypted.subText)
        assertEquals("", decrypted.extrasJson)
    }

    @Test
    fun `embedded NULs in field content survive the round trip without field shifting`() {
        // THE regression test for P1-008(a): under the legacy NUL-joined
        // format an embedded NUL in any field shifted all subsequent fields
        // and silently dropped extrasJson. The framed format preserves NULs
        // as ordinary content bytes.
        val payload = NotificationTransientPayload(
            title = "Salary\u0000deposit",
            text = "line1\u0000line2",
            bigText = "big\u0000text\u0000with\u0000nuls",
            subText = "sub",
            extrasJson = "{\"k\":1}"
        )

        val encrypted = crypto.encrypt(payload)
        val decrypted = crypto.decrypt(encrypted.ciphertext, encrypted.nonce, encrypted.version)

        assertEquals(payload, decrypted)
        assertEquals("Salary\u0000deposit", decrypted.title)
        assertEquals("line1\u0000line2", decrypted.text)
        assertEquals("big\u0000text\u0000with\u0000nuls", decrypted.bigText)
        assertEquals("sub", decrypted.subText)
        assertEquals("{\"k\":1}", decrypted.extrasJson)
    }

    @Test
    fun `legacy NUL-joined plaintext still decrypts with null for empty segments`() {
        // Legacy in-memory format: fields NUL-joined, nulls collapsed to "".
        // title="t", text="body", bigText=null, subText=null, extrasJson={"k":1}
        val encrypted = encryptLegacyPlaintext("t\u0000body\u0000\u0000\u0000{\"k\":1}")

        val decrypted = crypto.decrypt(encrypted.ciphertext, encrypted.nonce, encrypted.version)

        assertEquals("t", decrypted.title)
        assertEquals("body", decrypted.text)
        assertNull(decrypted.bigText)
        assertNull(decrypted.subText)
        assertEquals("{\"k\":1}", decrypted.extrasJson)
    }

    @Test
    fun `legacy payload with embedded NUL in content parses exactly as the old code did`() {
        // Legacy serializer output for title="Pay\u0000check", text="body",
        // bigText=null, subText=null, extrasJson={"k":1} was the NUL-joined
        // string below: the embedded NUL pushed extrasJson to split index 5,
        // so the old parser (first 5 segments only) dropped it and mapped the
        // shifted segment "check" to text. This documents that legacy read
        // compatibility preserves the old (corrupt) behavior — no silent
        // repair on read.
        val encrypted = encryptLegacyPlaintext("Pay\u0000check\u0000body\u0000\u0000\u0000{\"k\":1}")

        val decrypted = crypto.decrypt(encrypted.ciphertext, encrypted.nonce, encrypted.version)

        assertEquals("Pay", decrypted.title)
        assertEquals("check", decrypted.text)
        assertEquals("body", decrypted.bigText)
        assertNull(decrypted.subText)
        assertNull(decrypted.extrasJson)
    }

    @Test
    fun `framed parser rejects an unknown presence byte`() {
        // Marker 0xFF followed by presence byte 0x02 (neither 0x00 nor 0x01):
        // must fail closed with the constant message — no content, offset, or
        // length detail.
        val encrypted = encryptRawPlaintext(byteArrayOf(0xFF.toByte(), 0x02))

        try {
            crypto.decrypt(encrypted.ciphertext, encrypted.nonce, encrypted.version)
            fail("decrypt must reject an unknown presence byte")
        } catch (expected: IllegalStateException) {
            assertEquals("Malformed framed transient payload", expected.message)
        }
    }

    @Test
    fun `framed parser rejects a declared length beyond the buffer`() {
        // Marker, title marked present, 4-byte length 0x00000064 (100), and
        // zero content bytes following: the declared length exceeds the
        // remaining buffer, so the parser must fail closed.
        val encrypted = encryptRawPlaintext(
            byteArrayOf(0xFF.toByte(), 0x01, 0x00, 0x00, 0x00, 0x64.toByte())
        )

        try {
            crypto.decrypt(encrypted.ciphertext, encrypted.nonce, encrypted.version)
            fail("decrypt must reject a length beyond the buffer")
        } catch (expected: IllegalStateException) {
            assertEquals("Malformed framed transient payload", expected.message)
        }
    }

    @Test
    fun `framed parser rejects trailing bytes after the fifth field`() {
        // A structurally valid framed payload (title="t", everything else
        // null) with one extra trailing byte appended: the full-consumption
        // check must treat trailing bytes as a structural violation. (Future
        // formats require a new format marker.)
        val encrypted = encryptRawPlaintext(
            byteArrayOf(
                0xFF.toByte(), // marker
                0x01, 0x00, 0x00, 0x00, 0x01, 't'.toByte(), // title "t" present
                0x00, // text null
                0x00, // bigText null
                0x00, // subText null
                0x00, // extrasJson null
                0x00 // trailing byte: structural violation
            )
        )

        try {
            crypto.decrypt(encrypted.ciphertext, encrypted.nonce, encrypted.version)
            fail("decrypt must reject trailing bytes after the fifth field")
        } catch (expected: IllegalStateException) {
            assertEquals("Malformed framed transient payload", expected.message)
        }
    }

    @Test
    fun `corrupted ciphertext fails authentication and yields no partial payload`() {
        val encrypted = crypto.encrypt(NotificationTransientPayload("t", "b", null, null, null))

        val bytes = Base64.getDecoder().decode(encrypted.ciphertext)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        val corrupted = Base64.getEncoder().encodeToString(bytes)

        try {
            crypto.decrypt(corrupted, encrypted.nonce, encrypted.version)
            fail("decrypt must fail on a corrupted ciphertext (GCM auth failure)")
        } catch (expected: GeneralSecurityException) {
            // Expected: GCM tag verification failure; no partial payload.
            // GCM tag failure surfaces as AEADBadTagException (a
            // GeneralSecurityException) on JVM providers; asserting the
            // supertype excludes unrelated RuntimeExceptions (e.g. Base64
            // decode failures) from passing trivially.
        }
    }

    @Test
    fun `decrypt passes requested version to key provider and encrypt requests current version`() {
        val encrypted = crypto.encrypt(NotificationTransientPayload("t", null, null, null, null))
        assertEquals(
            listOf(NotificationTransientKeyProvider.CURRENT_VERSION),
            keyProvider.requestedVersions
        )

        crypto.decrypt(encrypted.ciphertext, encrypted.nonce, 1)
        assertEquals(
            listOf(NotificationTransientKeyProvider.CURRENT_VERSION, 1),
            keyProvider.requestedVersions
        )
    }

    @Test
    fun `framed plaintext starts with the 0xFF format marker`() {
        // Pins the marker choice: decrypt() dispatches on the first plaintext
        // byte, so an accidental marker change would break format dispatch.
        val encrypted = crypto.encrypt(NotificationTransientPayload("t", "b", null, null, null))

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            testKey,
            GCMParameterSpec(128, Base64.getDecoder().decode(encrypted.nonce))
        )
        val plaintext = cipher.doFinal(Base64.getDecoder().decode(encrypted.ciphertext))

        assertEquals(0xFF.toByte(), plaintext[0])
    }

    /**
     * Builds a legacy-format [EncryptedPayload] the same way the pre-framing
     * implementation did (same algorithm, 128-bit tag, 12-byte nonce), using
     * the deterministic test key. java.util.Base64 is fine in test code.
     */
    private fun encryptLegacyPlaintext(plaintext: String): EncryptedPayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12) { (it + 1).toByte() }
        cipher.init(Cipher.ENCRYPT_MODE, testKey, GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedPayload(
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
            nonce = Base64.getEncoder().encodeToString(nonce),
            version = NotificationTransientKeyProvider.CURRENT_VERSION
        )
    }

    /** Encrypts arbitrary bytes as if they were the framed plaintext (same algorithm/params). */
    private fun encryptRawPlaintext(plaintext: ByteArray): EncryptedPayload {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val nonce = ByteArray(12) { (it + 1).toByte() }
        cipher.init(Cipher.ENCRYPT_MODE, testKey, GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedPayload(
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
            nonce = Base64.getEncoder().encodeToString(nonce),
            version = NotificationTransientKeyProvider.CURRENT_VERSION
        )
    }
}

/** AES-256 key material: 32 deterministic, distinct bytes. */
private fun deterministicTestKeyBytes(): ByteArray = ByteArray(32) { (it * 7 + 11).toByte() }

/**
 * Plain (non-mock) key provider: returns a deterministic JVM AES-256 key and
 * records every requested version for assertions. AES-256 works on the JVM.
 */
private class FakeNotificationTransientKeyProvider : NotificationTransientKeyProvider {
    val requestedVersions = mutableListOf<Int>()

    override fun getOrCreateSecretKey(version: Int): SecretKey {
        requestedVersions.add(version)
        return SecretKeySpec(deterministicTestKeyBytes(), "AES")
    }
}
