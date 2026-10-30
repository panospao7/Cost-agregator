package com.yourname.expensetracker.data.privacy.applock.pin

import com.yourname.expensetracker.domain.privacy.applock.pin.VerifierUnreadableCause
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * JVM stand-in for the AndroidKeyStore HMAC key (not available on the JVM / Robolectric).
 * The key is a plain `HmacSHA256` [SecretKeySpec]. Outcomes are switchable so tests can model a
 * missing key (KEY_MISSING) or any other Keystore failure (KEYSTORE_ERROR).
 */
internal class FakePinKeyBinder : PinKeyBinder {

    private val random = SecureRandom()

    /** The current key; null models a missing alias. */
    var key: SecretKey? = null

    /** When set, [getExisting] fails with this cause (the key is untouched). */
    var getFailure: VerifierUnreadableCause? = null

    /** When set, [createForSetup] fails with this cause and creates nothing. */
    var createFailure: VerifierUnreadableCause? = null

    var createCount = 0
        private set
    var getCount = 0
        private set
    var deleteCount = 0
        private set

    /** Invoked at the start of [delete], so tests can inspect the order against the store clear. */
    var onDelete: () -> Unit = {}

    override fun createForSetup(): PinKeyOutcome {
        createCount++
        createFailure?.let { return PinKeyOutcome.Unavailable(it) }
        val fresh = newKey()
        key = fresh
        return PinKeyOutcome.Available(fresh)
    }

    override fun getExisting(): PinKeyOutcome {
        getCount++
        getFailure?.let { return PinKeyOutcome.Unavailable(it) }
        val current = key ?: return PinKeyOutcome.Unavailable(VerifierUnreadableCause.KEY_MISSING)
        return PinKeyOutcome.Available(current)
    }

    override fun delete() {
        onDelete()
        deleteCount++
        key = null
    }

    private fun newKey(): SecretKey {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return SecretKeySpec(bytes, "HmacSHA256")
    }
}
