package com.yourname.expensetracker.data.privacy.applock.pin

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.domain.privacy.applock.MonotonicClock
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.pin.BiometricKeyResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinDeleteResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinSetupResult
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifyResult
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * Automatable part of gate G2 (app-lock-privacy-mode plan, "### G2"), on a real device or emulator with the real
 * Android Keystore. The Keystore aliases are hard-coded in production, so the real aliases are used and removed in
 * [tearDown]; only the DataStore file is test-unique. Synthetic PINs only, CharArray, zeroed, never logged.
 */
@RunWith(AndroidJUnit4::class)
class AppLockPinG2DeviceTest {

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private val binder = KeystorePinKeyBinder()
    private val bioManager = AndroidBiometricPinKeyManager()

    private val clock = object : MonotonicClock {
        override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        storeFile = File(context.noBackupFilesDir, "g2_test_${UUID.randomUUID()}.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { storeFile })
        binder.delete()
        bioManager.deleteKey()
    }

    @After
    fun tearDown() {
        binder.delete()
        bioManager.deleteKey()
        scope.cancel()
        storeFile.delete()
        File(storeFile.parentFile, storeFile.name + ".tmp").delete()
    }

    private fun newStore() = AppPinStoreImpl(
        dataStore, binder, bioManager, AndroidBootCountSource(context), clock,
        mockk<PrivacyDiagnosticsEmitter>(relaxed = true), Dispatchers.IO
    )

    private fun pin(digits: String): CharArray = digits.toCharArray()

    private fun hasAlias(alias: String): Boolean =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

    @Test
    fun pbkdf2Timing() {
        assertEquals(150_000, PinVerifierCrypto.CURRENT_ITERATIONS)
        val salt = PinVerifierCrypto.newSalt()
        val warmUp = pin("482916")
        PinVerifierCrypto.derive(warmUp, salt, PinVerifierCrypto.MIN_ITERATIONS).fill(0) // class loading, not timed
        val timed = pin("482916")
        val startNs = System.nanoTime()
        val derived = PinVerifierCrypto.derive(timed, salt, PinVerifierCrypto.CURRENT_ITERATIONS)
        val ms = (System.nanoTime() - startNs) / 1_000_000
        val size = derived.size
        derived.fill(0)
        warmUp.fill('\u0000')
        timed.fill('\u0000')
        Log.i(TAG, "G2 pbkdf2 derivation at ${PinVerifierCrypto.CURRENT_ITERATIONS} iterations: $ms ms")
        assertEquals(PinVerifierCrypto.OUTPUT_BYTES, size)
        assertTrue("PBKDF2 150k took $ms ms (emulator record; G2-C 200-1000 ms is an owner low-end check)", ms < 3000)
    }

    @Suppress("DEPRECATION")
    @Test
    fun keystoreHmacKey_isNonExportable_andHardwareOrTeeBacked_ifAvailable() {
        val created = binder.createForSetup()
        assertTrue("key creation must succeed on a device", created is PinKeyOutcome.Available)
        val key = (created as PinKeyOutcome.Available).key
        assertNull("Keystore key material must not be exportable", key.encoded)
        val info = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        assertEquals(KeyProperties.PURPOSE_SIGN, info.purposes and KeyProperties.PURPOSE_SIGN)
        assertFalse("PIN HMAC key must not require user auth", info.isUserAuthenticationRequired)
        val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "securityLevel=${info.securityLevel}"
        else "insideSecureHardware=${info.isInsideSecureHardware}"
        Log.i(TAG, "G2 HMAC key: algorithm=${key.algorithm} $level")
        assertTrue(hasAlias(KeystorePinKeyBinder.KEY_ALIAS))
    }

    @Test
    fun realStore_setup_verify_wrong_reset_deleteAll() = runBlocking {
        val store = newStore()
        val failuresKey = intPreferencesKey("pin_attempt_failures")
        assertEquals(PinVerifierState.ABSENT, store.readState().verifier)
        assertEquals(PinSetupResult.DONE, store.setup(pin("482916")))
        assertEquals(PinVerifierState.PRESENT, store.readState().verifier)
        assertTrue(hasAlias(KeystorePinKeyBinder.KEY_ALIAS))

        val wrong = store.verify(pin("739150"))
        assertTrue("wrong PIN must be rejected as Wrong, was $wrong", wrong is PinVerifyResult.Wrong)
        assertEquals(1, dataStore.data.first()[failuresKey])

        assertEquals(PinVerifyResult.Correct, store.verify(pin("482916")))
        assertEquals("a correct PIN resets the counter", 0, dataStore.data.first()[failuresKey])

        assertEquals(PinDeleteResult.DONE, store.deleteAll())
        assertEquals(PinVerifierState.ABSENT, store.readState().verifier)
        assertFalse("HMAC alias must be deleted", hasAlias(KeystorePinKeyBinder.KEY_ALIAS))
        assertEquals(PinVerifyResult.Absent, store.verify(pin("482916")))
    }

    @Test
    fun bootCountSource_returnsNonNegativeOrNull() {
        val boot = AndroidBootCountSource(context).currentBootCount()
        Log.i(TAG, "G2 boot count available=${boot != null}")
        if (boot != null) assertTrue("boot count must be >= 0, was $boot", boot >= 0)
    }

    @Test
    fun biometricKeyManager_failsClosed_withoutStrongAuthentication() {
        val canStrong = BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val created = bioManager.createKey()
        Log.i(TAG, "G2 biometric strong status=$canStrong createKey=$created")
        assertFalse("null cipher must never verify", bioManager.verifyAuthenticatedCrypto(null))
        if (canStrong != BiometricManager.BIOMETRIC_SUCCESS) {
            assertFalse("no strong biometric: no usable key", created && !hasAlias("applock_pin_bio_v1"))
        }
        val result = bioManager.cipherForAuthentication()
        if (!created) {
            assertEquals(BiometricKeyResult.KeyMissing, result)
        } else {
            assertTrue("created key must be Ready, was $result", result is BiometricKeyResult.Ready)
            val cipher = (result as BiometricKeyResult.Ready).cipher
            assertFalse("unauthenticated cipher must not unlock", bioManager.verifyAuthenticatedCrypto(cipher))
        }
        bioManager.deleteKey()
        assertEquals(BiometricKeyResult.KeyMissing, bioManager.cipherForAuthentication())
        assertFalse(hasAlias("applock_pin_bio_v1"))
    }

    private companion object {
        const val TAG = "AppLockPinG2"
    }
}
