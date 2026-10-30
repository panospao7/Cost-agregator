package com.yourname.expensetracker.data.privacy.applock.pin

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricKeyResult
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricPinKeyManager
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Android implementation of the domain [BiometricPinKeyManager] port (OD-16a, INV-40): an AndroidKeyStore
 * AES-256-GCM key that needs a BIOMETRIC_STRONG authentication for EVERY use and is invalidated by a new
 * enrolment. The key is only a proof of a fresh strong authentication: it protects no data, and the PIN verifier
 * never depends on it.
 */
@Singleton
class AndroidBiometricPinKeyManager @Inject constructor() : BiometricPinKeyManager {

    override fun createKey(): Boolean = try {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Auth per use, BIOMETRIC_STRONG only (never device credential).
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }
        // Replace any older key so a stale one can never survive a re-enable.
        deleteKey()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(builder.build())
        generator.generateKey()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    override fun cipherForAuthentication(): BiometricKeyResult = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (key == null) {
            BiometricKeyResult.KeyMissing
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            BiometricKeyResult.Ready(cipher)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: KeyPermanentlyInvalidatedException) {
        BiometricKeyResult.KeyInvalidated
    } catch (e: UnrecoverableKeyException) {
        // G26-5: getKey cannot recover an invalidated key on some devices; same bounded state, no message kept.
        BiometricKeyResult.KeyInvalidated
    } catch (e: Exception) {
        BiometricKeyResult.KeystoreError
    }

    override fun verifyAuthenticatedCrypto(cipher: Cipher?): Boolean {
        if (cipher == null) return false
        return try {
            // Fails (UserNotAuthenticated) unless the strong authentication really unlocked this cipher.
            cipher.doFinal(PROOF_INPUT).isNotEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    override fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort (INV-49): a leftover key is useless without a fresh strong authentication.
        }
    }

    override fun toString(): String = "AndroidBiometricPinKeyManager"

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "applock_pin_bio_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val PROOF_INPUT = byteArrayOf(0x01)
    }
}
