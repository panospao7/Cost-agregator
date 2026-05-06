package com.yourname.expensetracker.data.privacy

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service that provides AES-256-GCM encryption and decryption of backup data.
 *
 * Uses PBKDF2 with HMAC-SHA256 for key derivation from a user-supplied password
 * and AES-256-GCM for authenticated symmetric encryption.
 *
 * ## Output format (for [encrypt])
 * The returned [ByteArray] contains:
 * 1. Salt (16 bytes) — used for key derivation
 * 2. IV / nonce (12 bytes) — GCM initialisation vector
 * 3. Ciphertext (variable) — the encrypted payload
 * 4. GCM authentication tag (16 bytes, appended automatically by the GCM cipher)
 *
 * ## Input format (for [decrypt])
 * Must be exactly as produced by [encrypt].
 */
@Singleton
class BackupEncryptionService @Inject constructor() {

    private val secureRandom = SecureRandom()

    companion object {
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val AES_ALGORITHM = "AES"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_LENGTH_BITS = 256
        private const val ITERATION_COUNT = 600_000
        private const val SALT_LENGTH_BYTES = 16
        private const val IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }

    /**
     * Encrypts [data] (plaintext) using AES-256-GCM with a key derived from
     * [password] via PBKDF2.
     *
     * @return concatenated salt + IV + ciphertext (with embedded GCM tag)
     */
    fun encrypt(data: ByteArray, password: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }

        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        val ciphertext = cipher.doFinal(data)

        // Concatenate: salt (16) + IV (12) + ciphertext+tag
        return salt + iv + ciphertext
    }

    /**
     * Encrypts the contents of [plaintextFile] using AES-256-GCM and writes
     * the salt + IV + ciphertext to [outputStream] via [CipherOutputStream],
     * streaming 8 KB chunks from disk without loading the entire file into
     * memory.
     *
     * The caller is responsible for closing [outputStream].
     */
    fun encrypt(plaintextFile: File, outputStream: OutputStream, password: String) {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        // Write salt + IV, then stream ciphertext through CipherOutputStream
        outputStream.write(salt)
        outputStream.write(iv)
        CipherOutputStream(outputStream, cipher).use { cos ->
            FileInputStream(plaintextFile).use { fis ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    cos.write(buffer, 0, bytesRead)
                }
            } // cos.close() is called by .use, which triggers Cipher.doFinal() → appends GCM tag
        }
    }

    /**
     * Creates a streaming [CipherInputStream] over [inputStream] that decrypts
     * AES-256-GCM ciphertext on the fly in 8 KB chunks, avoiding loading the
     * entire encrypted payload into memory.
     *
     * The first [SALT_LENGTH_BYTES] + [IV_LENGTH_BYTES] bytes read from
     * [inputStream] are the salt and IV — the remaining bytes are the ciphertext
     * (with embedded GCM authentication tag).
     *
     * @return a [CipherInputStream] whose [CipherInputStream.read] calls
     *         return decrypted plaintext. The caller **must close** the
     *         returned stream to release cipher resources.
     * @throws javax.crypto.AEADBadTagException if the password is wrong or
     *         the ciphertext has been tampered with (detected when the stream
     *         is fully consumed).
     */
    fun decryptStream(inputStream: InputStream, password: String): CipherInputStream {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        val iv = ByteArray(IV_LENGTH_BYTES)
        var offset = 0; while (offset < SALT_LENGTH_BYTES) {
            val n = inputStream.read(salt, offset, SALT_LENGTH_BYTES - offset)
            if (n == -1) throw IllegalStateException("Truncated input: missing salt")
            offset += n
        }
        offset = 0; while (offset < IV_LENGTH_BYTES) {
            val n = inputStream.read(iv, offset, IV_LENGTH_BYTES - offset)
            if (n == -1) throw IllegalStateException("Truncated input: missing IV")
            offset += n
        }
        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return CipherInputStream(inputStream, cipher)
    }

    /**
     * Decrypts [data] that was previously produced by [encrypt].
     *
     * @return the original plaintext [ByteArray]
     * @throws javax.crypto.AEADBadTagException if the password is wrong or data is corrupted
     */
    fun decrypt(data: ByteArray, password: String): ByteArray {
        require(data.size >= SALT_LENGTH_BYTES + IV_LENGTH_BYTES) {
            "Encrypted data is too short: expected at least ${SALT_LENGTH_BYTES + IV_LENGTH_BYTES} bytes"
        }

        var offset = 0
        val salt = data.copyOfRange(offset, SALT_LENGTH_BYTES).also { offset += SALT_LENGTH_BYTES }
        val iv = data.copyOfRange(offset, offset + IV_LENGTH_BYTES).also { offset += IV_LENGTH_BYTES }
        val ciphertext = data.copyOfRange(offset, data.size)

        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val factory: SecretKeyFactory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH_BITS)
        val rawKey = factory.generateSecret(spec).encoded
        return SecretKeySpec(rawKey, AES_ALGORITHM)
    }
}
