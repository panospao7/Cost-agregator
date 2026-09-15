package com.yourname.expensetracker.data.privacy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Tests for [BackupEncryptionService].
 *
 * Validates AES-256-GCM encrypt/decrypt roundtrip and integrity checks.
 */
class BackupEncryptionServiceTest {

    private val service = BackupEncryptionService()

    @Test
    fun `encrypt then decrypt returns original data`() {
        val original = "Hello, ExpenseTracker Backup!".toByteArray(StandardCharsets.UTF_8)
        val password = "my-secure-p@ssword-123"

        val encrypted = service.encrypt(original, password)
        val decrypted = service.decrypt(encrypted, password)

        assertArrayEquals("Roundtrip should restore original data", original, decrypted)
    }

    @Test
    fun `encrypt then decrypt with binary data`() {
        val original = ByteArray(256) { it.toByte() }
        val password = "binary-test-password"

        val encrypted = service.encrypt(original, password)
        val decrypted = service.decrypt(encrypted, password)

        assertArrayEquals("Binary roundtrip should restore original data", original, decrypted)
    }

    @Test
    fun `decrypt with wrong password throws`() {
        val original = "Secret data".toByteArray(StandardCharsets.UTF_8)

        val encrypted = service.encrypt(original, "correct-password")

        assertThrows("Wrong password should throw AEADBadTagException", javax.crypto.AEADBadTagException::class.java) {
            service.decrypt(encrypted, "wrong-password")
        }
    }

    @Test
    fun `encrypt produces different output each time due to random salt and iv`() {
        val data = "Deterministic input".toByteArray(StandardCharsets.UTF_8)
        val password = "same-password"

        val encrypted1 = service.encrypt(data, password)
        val encrypted2 = service.encrypt(data, password)

        // Salt (16) + IV (12) differ, so first 28 bytes must differ
        val saltIv1 = encrypted1.copyOfRange(0, 28)
        val saltIv2 = encrypted2.copyOfRange(0, 28)
        assertFalse("Salts+IVs should be different (randomised)", saltIv1 contentEquals saltIv2)
    }

    @Test
    fun `decrypt with corrupted ciphertext throws`() {
        val original = "Important data".toByteArray(StandardCharsets.UTF_8)
        val password = "strong-password"

        val encrypted = service.encrypt(original, password)
        // Corrupt the ciphertext (byte after salt+IV)
        encrypted[28] = (encrypted[28].toInt() xor 0xFF).toByte()

        assertThrows("Corrupted ciphertext should throw", javax.crypto.AEADBadTagException::class.java) {
            service.decrypt(encrypted, password)
        }
    }

    // ── RP-03A: legacy-envelope compatibility pins ────────────────
    // These tests pin the CURRENT (legacy) envelope — salt(16) + IV(12) +
    // AES-256-GCM ciphertext/tag, PBKDF2-HMAC-SHA256 600k iterations — so the
    // future versioned-KDF envelope batch cannot silently break legacy reads.

    @Test
    fun `legacy envelope layout is salt16 iv12 then ciphertext with embedded tag`() {
        val original = ByteArray(1000) { (it % 251).toByte() }
        val encrypted = service.encrypt(original, "layout-pin-password")

        // salt (16) + IV (12) + ciphertext (plaintext + 16-byte GCM tag)
        assertEquals(16 + 12 + original.size + 16, encrypted.size)
    }

    @Test
    fun `legacy envelope KDF parameters are pinned`() {
        // Direct constant pins so an accidental KDF change (iterations/salt/IV/
        // key length) fails loudly here before it ships.
        assertEquals(600_000, BackupEncryptionService.ITERATION_COUNT)
        assertEquals(16, BackupEncryptionService.SALT_LENGTH_BYTES)
        assertEquals(12, BackupEncryptionService.IV_LENGTH_BYTES)
        assertEquals(256, BackupEncryptionService.KEY_LENGTH_BITS)
    }

    @Test
    fun `legacy envelope vector roundtrips through the streaming decrypt path`() {
        // CostbackupBundle.extract reads bundles through decryptStream — a legacy
        // vector must survive that exact path, not just the byte-array decrypt.
        val original = ByteArray(4096) { (it % 199).toByte() }
        val password = "legacy-vector-password"

        val encrypted = service.encrypt(original, password)
        val decrypted = service.decryptStream(
            java.io.ByteArrayInputStream(encrypted),
            password
        ).use { it.readBytes() }

        assertArrayEquals("legacy vector must roundtrip via decryptStream", original, decrypted)
    }

    @Test
    fun `legacy envelope rejects input shorter than salt plus iv`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.decrypt(ByteArray(27), "any-password")
        }
    }

    @Test
    fun `legacy envelope decryptStream rejects truncated salt`() {
        assertThrows(IllegalStateException::class.java) {
            service.decryptStream(java.io.ByteArrayInputStream(ByteArray(15)), "any-password")
        }
    }

    @Test
    fun `legacy envelope decryptStream rejects truncated iv`() {
        // Salt present (16 bytes) but fewer than 12 IV bytes.
        assertThrows(IllegalStateException::class.java) {
            service.decryptStream(java.io.ByteArrayInputStream(ByteArray(20)), "any-password")
        }
    }

    @Test
    fun `legacy envelope rejects salt iv only with no ciphertext or tag`() {
        val encrypted = service.encrypt("payload".toByteArray(), "pin-password")
        val headOnly = encrypted.copyOfRange(0, 28)

        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            service.decrypt(headOnly, "pin-password")
        }
    }

    @Test
    fun `legacy envelope rejects a truncated ciphertext tail`() {
        val encrypted = service.encrypt("truncation payload".toByteArray(), "pin-password")
        val truncated = encrypted.copyOfRange(0, encrypted.size - 5)

        assertThrows(javax.crypto.AEADBadTagException::class.java) {
            service.decrypt(truncated, "pin-password")
        }
    }

    @Test
    fun `legacy envelope streaming path fails on wrong password`() {
        val encrypted = service.encrypt("wrong password payload".toByteArray(), "correct-password")
        val stream = service.decryptStream(java.io.ByteArrayInputStream(encrypted), "wrong-password")

        // The GCM tag is verified when the stream is consumed/finalised; the
        // failure surfaces either directly or wrapped in an IOException.
        val thrown = runCatching { stream.use { it.readBytes() } }.exceptionOrNull()
        val isBadTag = thrown is javax.crypto.AEADBadTagException ||
            (thrown as? java.io.IOException)?.cause is javax.crypto.AEADBadTagException
        assertTrue(
            "expected AEADBadTagException (possibly IOException-wrapped), got: $thrown",
            isBadTag
        )
    }
}
