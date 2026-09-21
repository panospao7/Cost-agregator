package com.yourname.expensetracker.data.privacy

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.yourname.expensetracker.domain.privacy.InstallationSecretHasher
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the per-installation HMAC secret key for [InstallationSecretHasher].
 *
 * Abstracted behind an interface so unit tests can supply a JVM key store —
 * AndroidKeyStore is not available on the desktop JVM (same constraint as
 * `SecureKeyStorage`, whose Robolectric Keystore tests are @Ignore'd).
 * Production binds [AndroidKeystoreInstallationSecretKeyProvider].
 */
interface InstallationSecretKeyProvider {
    /**
     * Returns the persisted secret key for [version], creating a random
     * Keystore-generated key on first use. Returns `null` when the keystore is
     * unavailable or the key is invalidated — callers fail closed.
     */
    fun getOrCreateSecretKey(version: Int): SecretKey?
}

/**
 * Production provider: Android Keystore HMAC-SHA-256 key, one alias per version.
 *
 * The key is generated with the keystore's own secure RNG (never derived from
 * public constants), is non-exportable, and is NOT included in Android backups —
 * giving restart stability within an install and unlinkability across
 * installs/restores. Following the established pattern of
 * `AndroidKeystoreNotificationTransientKeyProvider`.
 */
@Singleton
class AndroidKeystoreInstallationSecretKeyProvider @Inject constructor() : InstallationSecretKeyProvider {

    override fun getOrCreateSecretKey(version: Int): SecretKey? = try {
        val alias = KEY_ALIAS_PREFIX + version
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: run {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_HMAC,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                ).build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
            }
    } catch (e: Exception) {
        // RP-15 (15-C): fail closed — keystore unavailability/invalidation must
        // not degrade to a public hash. Never log e.message (privacy rule).
        null
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS_PREFIX = "installation_secret_hmac_v"
    }
}

/**
 * RP-15 (15-C, D13 remediation): the single Keystore-backed installation-secret
 * HMAC service. Bound as THE [InstallationSecretHasher]; both cloud-redaction
 * paths (`DefaultCloudPayloadRedactor.redactText()` and
 * `redactMerchant()`/`CloudPiiSanitizer`) consume this one instance.
 */
@Singleton
class KeystoreInstallationSecretHasher @Inject constructor(
    private val keyProvider: InstallationSecretKeyProvider
) : InstallationSecretHasher {

    override val currentVersion: Int
        get() = CURRENT_VERSION

    override fun hmacPrefix(value: String, length: Int): String? = try {
        val key = keyProvider.getOrCreateSecretKey(CURRENT_VERSION) ?: return null
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key)
        mac.doFinal(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }
            .take(length.coerceIn(1, 64))
    } catch (e: Exception) {
        // RP-15 (15-C): fail closed (e.g. KeyPermanentlyInvalidatedException,
        // ProviderException). No public-hash fallback; no exception text logged.
        null
    }

    companion object {
        /** Rotating: bump to 2 to force a fresh key generation for all new outputs. */
        const val CURRENT_VERSION = 1
        private const val HMAC_ALGORITHM = "HmacSHA256"
    }
}

/**
 * Fail-closed hasher for secondary constructors of redaction components
 * (test/fallback wiring that cannot reach Hilt). It never produces a hash, so
 * every pseudonym degrades to the identity-free marker — never a public hash.
 */
object FailClosedInstallationSecretHasher : InstallationSecretHasher {
    override val currentVersion: Int get() = 0
    override fun hmacPrefix(value: String, length: Int): String? = null
}
