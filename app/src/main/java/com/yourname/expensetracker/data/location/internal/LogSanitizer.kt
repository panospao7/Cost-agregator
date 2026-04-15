package com.yourname.expensetracker.data.location.internal

import java.security.MessageDigest
import java.security.SecureRandom

private object LogSanitizer {
    private val processSalt: ByteArray = ByteArray(SALT_SIZE_BYTES).also { SecureRandom().nextBytes(it) }

    private const val SHA_256 = "SHA-256"
    private const val TOKEN_PREFIX = "sha256:"
    private const val SALT_SIZE_BYTES = 32
    private val HEX_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')

    fun anonymize(value: String): String {
        val digest = MessageDigest.getInstance(SHA_256)
            .apply {
                update(processSalt)
                update(value.toByteArray(Charsets.UTF_8))
            }
            .digest()

        return TOKEN_PREFIX + digest.toHexString()
    }

    private fun ByteArray.toHexString(): String {
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = HEX_DIGITS[value ushr 4]
            chars[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(chars)
    }
}

fun String.anonymizeForLog(): String = LogSanitizer.anonymize(this)
