package com.yourname.expensetracker.data.privacy.applock.pin

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The persisted PIN verifier record (S24 design decision 1). One string preference:
 * `recordVersion:algId:iterations:saltHex:keyAliasVersion:tagHex`.
 * It holds no PIN, no PBKDF2 output and no Keystore material: [tag] is a device-bound HMAC over the derived output.
 */
internal class PinVerifierRecord(
    val recordVersion: Int,
    val algId: Int,
    val iterations: Int,
    val salt: ByteArray,
    val keyAliasVersion: Int,
    val tag: ByteArray
) {
    fun serialize(): String = listOf(
        recordVersion.toString(),
        algId.toString(),
        iterations.toString(),
        PinVerifierCrypto.toHex(salt),
        keyAliasVersion.toString(),
        PinVerifierCrypto.toHex(tag)
    ).joinToString(SEPARATOR)

    /** True when a successful verify should re-hash with the current parameters (design decision 1). */
    fun isOutdated(): Boolean =
        iterations < PinVerifierCrypto.CURRENT_ITERATIONS || algId != PinVerifierCrypto.CURRENT_ALG_ID

    fun wipe() {
        salt.fill(0)
        tag.fill(0)
    }

    companion object {
        const val SEPARATOR = ":"
        const val CURRENT_RECORD_VERSION = 1
        const val CURRENT_KEY_ALIAS_VERSION = 1

        /** Strict parse. Anything unexpected is null, which the store reports as present-but-unreadable. */
        fun parse(raw: String): PinVerifierRecord? {
            val parts = raw.split(SEPARATOR)
            if (parts.size != FIELD_COUNT) return null
            val recordVersion = parts[0].toIntOrNull() ?: return null
            val algId = parts[1].toIntOrNull() ?: return null
            val iterations = parts[2].toIntOrNull() ?: return null
            val keyAliasVersion = parts[4].toIntOrNull() ?: return null
            if (recordVersion != CURRENT_RECORD_VERSION) return null
            if (algId != PinVerifierCrypto.CURRENT_ALG_ID) return null
            if (iterations !in PinVerifierCrypto.MIN_ITERATIONS..PinVerifierCrypto.MAX_ITERATIONS) return null
            if (keyAliasVersion != CURRENT_KEY_ALIAS_VERSION) return null
            val salt = PinVerifierCrypto.fromHex(parts[3]) ?: return null
            val tag = PinVerifierCrypto.fromHex(parts[5])
            if (tag == null || salt.size != PinVerifierCrypto.SALT_BYTES || tag.size != PinVerifierCrypto.TAG_BYTES) {
                salt.fill(0)
                tag?.fill(0)
                return null
            }
            return PinVerifierRecord(recordVersion, algId, iterations, salt, keyAliasVersion, tag)
        }

        private const val FIELD_COUNT = 6
    }
}

/** PBKDF2 + Keystore-HMAC tag primitives. Pure JVM crypto; the key comes from a [PinKeyBinder]. */
internal object PinVerifierCrypto {
    const val CURRENT_ALG_ID = 1
    const val CURRENT_ITERATIONS = 150_000
    const val MIN_ITERATIONS = 50_000
    const val MAX_ITERATIONS = 2_000_000
    const val SALT_BYTES = 16
    const val OUTPUT_BYTES = 32
    const val TAG_BYTES = 32
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val MAC_ALGORITHM = "HmacSHA256"
    private val DOMAIN_TAG = "applock-pin-verifier-v1".toByteArray(Charsets.US_ASCII)
    private val secureRandom = SecureRandom()

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }

    /** PBKDF2WithHmacSHA256 over [pin]. The caller zero-fills the result. [pin] is not modified. */
    fun derive(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, OUTPUT_BYTES * 8)
        try {
            return SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** A Mac initialised with the Keystore key. Failure here means the key is not usable. */
    fun newMac(key: SecretKey): Mac = Mac.getInstance(MAC_ALGORITHM).apply { init(key) }

    /** tag = HMAC(domainTag || iterations || salt || pbkdf2Out). The caller zero-fills the result. */
    fun computeTag(mac: Mac, iterations: Int, salt: ByteArray, derived: ByteArray): ByteArray {
        mac.reset()
        mac.update(DOMAIN_TAG)
        mac.update(
            byteArrayOf(
                (iterations ushr 24).toByte(),
                (iterations ushr 16).toByte(),
                (iterations ushr 8).toByte(),
                iterations.toByte()
            )
        )
        mac.update(salt)
        mac.update(derived)
        return mac.doFinal()
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    fun fromHex(text: String): ByteArray? {
        if (text.isEmpty() || text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[i * 2], 16)
            val lo = Character.digit(text[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
