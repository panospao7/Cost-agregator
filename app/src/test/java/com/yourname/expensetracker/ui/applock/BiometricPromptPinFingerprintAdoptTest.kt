package com.yourname.expensetracker.ui.applock

import android.app.Application
import android.content.ComponentName
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.privacy.applock.AuthLaunch
import com.yourname.expensetracker.domain.privacy.applock.AuthPromptCopy
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCallback
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthErrorBucket
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricKeyResult
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricPinKeyManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import javax.crypto.Cipher

/**
 * S26 fix B (G26-3, INV-40): an adopted PIN_FINGERPRINT attempt (after a rotation) keeps the crypto-verified
 * success path, and a PIN_FINGERPRINT request without a key manager fails closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BiometricPromptPinFingerprintAdoptTest {

    private class FakeKeyManager(
        var verifyResult: Boolean = false,
        var cipherResult: BiometricKeyResult = BiometricKeyResult.KeyMissing
    ) : BiometricPinKeyManager {
        var verifyCalls = 0
        var cipherCalls = 0
        override fun createKey(): Boolean = true
        override fun cipherForAuthentication(): BiometricKeyResult {
            cipherCalls++
            return cipherResult
        }

        override fun verifyAuthenticatedCrypto(cipher: Cipher?): Boolean {
            verifyCalls++
            return verifyResult
        }

        override fun deleteKey() = Unit
    }

    private class FakeHandle : PromptHandle {
        var authenticateCalls = 0
        override fun authenticate(info: BiometricPrompt.PromptInfo) {
            authenticateCalls++
        }

        override fun cancelAuthentication() = Unit
    }

    private class RecordingCallback : DeviceAuthCallback {
        val outcomes = mutableListOf<Pair<Long, DeviceAuthOutcome>>()
        override fun onOutcome(attemptId: Long, outcome: DeviceAuthOutcome) {
            outcomes += attemptId to outcome
        }
    }

    private class Rig(keyManager: BiometricPinKeyManager?, activity: FragmentActivity) {
        val handle = FakeHandle()
        lateinit var libraryCallback: BiometricPrompt.AuthenticationCallback
        val adapter = BiometricPromptDeviceAuthenticator(activity, keyManager) { callback ->
            libraryCallback = callback
            handle
        }

        /** A prompt success that carries no crypto object, like a success the key cannot vouch for. */
        fun succeedWithoutCrypto() {
            val result = mockk<BiometricPrompt.AuthenticationResult>()
            every { result.cryptoObject } returns null
            libraryCallback.onAuthenticationSucceeded(result)
        }
    }

    private fun newActivity(): FragmentActivity {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
        return Robolectric.buildActivity(FragmentActivity::class.java).create().get()
    }

    private val failedOther = DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER)

    @Test
    fun inv40_adoptedPinFingerprintAttempt_promptSuccessButCryptoFails_doesNotDeliverSuccess() {
        val keys = FakeKeyManager(verifyResult = false)
        val rig = Rig(keys, newActivity())
        val callback = RecordingCallback()

        assertTrue(rig.adapter.adopt(5L, AuthPromptCopy.PIN_FINGERPRINT, callback))
        rig.succeedWithoutCrypto()

        assertEquals(listOf(5L to failedOther), callback.outcomes)
        assertEquals("the crypto verification must have been consulted", 1, keys.verifyCalls)
    }

    @Test
    fun inv40_adoptedPinFingerprintAttempt_withNoKeyManager_promptSuccessDoesNotDeliverSuccess() {
        val rig = Rig(null, newActivity())
        val callback = RecordingCallback()

        assertTrue(rig.adapter.adopt(5L, AuthPromptCopy.PIN_FINGERPRINT, callback))
        rig.succeedWithoutCrypto()

        assertEquals(listOf(5L to failedOther), callback.outcomes)
    }

    @Test
    fun inv40_adoptedPinFingerprintAttempt_whenCryptoVerificationThrows_failsClosed() {
        val keys = mockk<BiometricPinKeyManager>()
        every { keys.verifyAuthenticatedCrypto(any()) } throws IllegalStateException("synthetic")
        val rig = Rig(keys, newActivity())
        val callback = RecordingCallback()

        assertTrue(rig.adapter.adopt(5L, AuthPromptCopy.PIN_FINGERPRINT, callback))
        rig.succeedWithoutCrypto()

        assertEquals(listOf(5L to failedOther), callback.outcomes)
    }

    @Test
    fun inv40_adoptedPinFingerprintAttempt_control_cryptoVerifies_deliversSuccessOnce() {
        val keys = FakeKeyManager(verifyResult = true)
        val rig = Rig(keys, newActivity())
        val callback = RecordingCallback()

        assertTrue(rig.adapter.adopt(5L, AuthPromptCopy.PIN_FINGERPRINT, callback))
        rig.succeedWithoutCrypto()
        rig.succeedWithoutCrypto()

        assertEquals(listOf(5L to DeviceAuthOutcome.Succeeded), callback.outcomes)
        assertEquals(1, keys.verifyCalls)
    }

    @Test
    fun inv40_adoptedUnlockAttempt_control_doesNotConsultTheKeyManager() {
        val keys = FakeKeyManager(verifyResult = false)
        val rig = Rig(keys, newActivity())
        val callback = RecordingCallback()

        assertTrue(rig.adapter.adopt(5L, AuthPromptCopy.UNLOCK, callback))
        rig.succeedWithoutCrypto()

        assertEquals(listOf(5L to DeviceAuthOutcome.Succeeded), callback.outcomes)
        assertEquals(0, keys.verifyCalls)
    }

    @Test
    fun inv40_pinFingerprintAuthenticate_withNullKeyManager_failsClosedWithPromptUnavailable() {
        val rig = Rig(null, newActivity())
        val callback = RecordingCallback()

        val result = rig.adapter.authenticate(1L, AuthPromptCopy.PIN_FINGERPRINT, callback)

        assertEquals(AuthLaunch.PROMPT_UNAVAILABLE, result)
        assertEquals("nothing may reach the library prompt", 0, rig.handle.authenticateCalls)
        assertTrue(callback.outcomes.isEmpty())
    }

    @Test
    fun inv40_pinFingerprintAuthenticate_keyNotReady_failsClosedWithPromptUnavailable() {
        val keys = FakeKeyManager(cipherResult = BiometricKeyResult.KeyInvalidated)
        val rig = Rig(keys, newActivity())

        val result = rig.adapter.authenticate(1L, AuthPromptCopy.PIN_FINGERPRINT, RecordingCallback())

        assertEquals(AuthLaunch.PROMPT_UNAVAILABLE, result)
        assertEquals(0, rig.handle.authenticateCalls)
        assertEquals(1, keys.cipherCalls)
    }

    @Test
    fun inv40_unlockAuthenticate_control_withNullKeyManager_stillLaunches() {
        val rig = Rig(null, newActivity())

        val result = rig.adapter.authenticate(1L, AuthPromptCopy.UNLOCK, RecordingCallback())

        assertEquals(AuthLaunch.LAUNCHED, result)
        assertEquals(1, rig.handle.authenticateCalls)
    }
}
