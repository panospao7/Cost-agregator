package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.domain.privacy.SensitiveHashingService
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default [SensitiveHashingService] implementation.
 *
 * HMAC key derivation: the HMAC key is derived from a per-purpose constant
 * combined with the app's package signature. In this implementation we use
 * a deterministic key derived from the purpose string itself (SHA-256 of the
 * purpose) so that hashes are stable across installs but purpose-isolated.
 * In production this should be replaced with a key stored in AndroidKeyStore.
 */
@Singleton
class DefaultSensitiveHashingService @Inject constructor() : SensitiveHashingService {

    override fun hmacSha256Prefix(value: String?, purpose: String, length: Int): String? {
        value ?: return null
        require(length in 1..64) { "length must be 1-64" }
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest("privacy-hmac-key-$purpose".toByteArray())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return mac.doFinal(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(length)
    }

    override fun sha256Prefix(value: String?, length: Int): String? {
        value ?: return null
        require(length in 1..64) { "length must be 1-64" }
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(length)
    }
}
