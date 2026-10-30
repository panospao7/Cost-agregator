package com.yourname.expensetracker.data.privacy.applock.pin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.yourname.expensetracker.domain.privacy.applock.pin.VerifierUnreadableCause
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/** Result of asking the binder for the PIN HMAC key. Carries a bounded cause only, never exception text. */
sealed interface PinKeyOutcome {
    class Available(val key: SecretKey) : PinKeyOutcome {
        override fun toString(): String = "Available"
    }

    data class Unavailable(val cause: VerifierUnreadableCause) : PinKeyOutcome
}

/**
 * Port for the non-exportable Keystore HMAC key that binds the PIN verifier to this device (S24 design decision 2).
 * Abstracted so JVM unit tests can supply a plain HMAC key (AndroidKeyStore is not available on the JVM).
 * Implementations must rethrow `CancellationException` and must never expose exception text.
 */
interface PinKeyBinder {
    /** Deletes any existing key, then creates a fresh one. The only place a key is ever created. */
    fun createForSetup(): PinKeyOutcome

    /** Returns the existing key. Never creates one: a missing key is [VerifierUnreadableCause.KEY_MISSING]. */
    fun getExisting(): PinKeyOutcome

    /** Deletes the key, best effort. Never throws except `CancellationException`. */
    fun delete()
}

/**
 * Production binder: AndroidKeyStore HMAC-SHA256, 256-bit, PURPOSE_SIGN, no user-auth requirement, no StrongBox,
 * alias [KEY_ALIAS]. Non-exportable by construction.
 */
@Singleton
class KeystorePinKeyBinder @Inject constructor() : PinKeyBinder {

    override fun createForSetup(): PinKeyOutcome = try {
        val keyStore = loadKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setKeySize(KEY_SIZE_BITS)
            .build()
        generator.init(spec)
        PinKeyOutcome.Available(generator.generateKey())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PinKeyOutcome.Unavailable(VerifierUnreadableCause.KEYSTORE_ERROR)
    }

    override fun getExisting(): PinKeyOutcome = try {
        val keyStore = loadKeyStore()
        val key = if (keyStore.containsAlias(KEY_ALIAS)) keyStore.getKey(KEY_ALIAS, null) as? SecretKey else null
        if (key == null) {
            PinKeyOutcome.Unavailable(VerifierUnreadableCause.KEY_MISSING)
        } else {
            PinKeyOutcome.Available(key)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        PinKeyOutcome.Unavailable(VerifierUnreadableCause.KEYSTORE_ERROR)
    }

    override fun delete() {
        try {
            val keyStore = loadKeyStore()
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort (INV-49): the decisive deletion step is the store clear. No exception text is kept.
        }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val KEY_ALIAS = "applock_pin_hmac_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE_BITS = 256
    }
}
