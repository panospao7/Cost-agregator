package com.yourname.expensetracker.data.privacy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
}
