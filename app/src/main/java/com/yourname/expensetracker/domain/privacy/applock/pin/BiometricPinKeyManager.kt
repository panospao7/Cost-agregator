package com.yourname.expensetracker.domain.privacy.applock.pin

import javax.crypto.Cipher

/** Bounded outcome of preparing the cipher for a fingerprint prompt (S26). No raw messages are kept. */
sealed interface BiometricKeyResult {
    /** An initialised ENCRYPT cipher, to be wrapped in a BiometricPrompt CryptoObject. */
    class Ready(val cipher: Cipher) : BiometricKeyResult {
        override fun toString(): String = "BiometricKeyResult.Ready"
    }

    /** The key was invalidated (new fingerprint enrolled, the lock screen was removed, or the key is unrecoverable). */
    data object KeyInvalidated : BiometricKeyResult

    /** No key exists (never created, or deleted). */
    data object KeyMissing : BiometricKeyResult

    /** Any other Keystore failure. */
    data object KeystoreError : BiometricKeyResult
}

/**
 * The fingerprint key of PIN mode (OD-16a, INV-40). A key that needs a BIOMETRIC_STRONG authentication for
 * EVERY use and is invalidated by a new enrolment. The key is only a proof of a fresh strong authentication: it
 * protects no data, and the PIN verifier never depends on it. The Android implementation lives in `data`.
 */
interface BiometricPinKeyManager {
    /** Creates (replacing any old) the key. False on any failure. */
    fun createKey(): Boolean

    /** An initialised cipher for `BiometricPrompt.CryptoObject`, or a bounded failure. */
    fun cipherForAuthentication(): BiometricKeyResult

    /** Performs one operation with the cipher the prompt returned, proving the authentication. */
    fun verifyAuthenticatedCrypto(cipher: Cipher?): Boolean

    /** Best effort; never throws except cancellation. */
    fun deleteKey()
}
