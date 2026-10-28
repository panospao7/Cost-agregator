package com.yourname.expensetracker.data.privacy.applock

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapability
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S15, INV-23 (availability, AMD-3 re-evaluation), AE-34 (API 26 / 28 / 29), AE-35 (no screen lock).
 *
 * Synthetic framework results only. The framework biometric service is replaced by a mock that is
 * handed out through a [ContextWrapper], so no real device state is read.
 */

/** Context that hands out [framework] for the biometric service and counts every service lookup. */
private class BiometricServiceContext(
    base: Context,
    // Typed Any? so loading this class never touches android.hardware.biometrics on API 26 / 28.
    private val framework: Any?
) : ContextWrapper(base) {
    var serviceLookups: Int = 0
        private set

    // The provider keeps the application context; return this wrapper so the lookups stay counted.
    override fun getApplicationContext(): Context = this

    override fun getSystemService(name: String): Any? {
        serviceLookups++
        // Literal of Context.BIOMETRIC_SERVICE (API 28), kept literal so this helper is API-safe on 26.
        return if (name == "biometric") framework else super.getSystemService(name)
    }
}

@SuppressLint("NewApi")
private fun frameworkReturning(vararg codes: Int): android.hardware.biometrics.BiometricManager {
    val framework = mockk<android.hardware.biometrics.BiometricManager>()
    every { framework.canAuthenticate(any<Int>()) } returnsMany codes.toList()
    return framework
}

/** INV-23 / AE-34: API below 30 is UNSUPPORTED_API and the library is never asked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 28, 29])
class AndroidDeviceAuthCapabilityProviderTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    // A non-null stand-in that is not a BiometricManager: if the library were consulted it would
    // either be counted as a service lookup or fail with a ClassCastException.
    private val trap = Any()

    @Test
    fun inv23_belowApi30_isUnsupportedApi_withoutAnyLibraryCall() {
        val context = BiometricServiceContext(base, trap)

        val capability = AndroidDeviceAuthCapabilityProvider(context).current()

        assertEquals(DeviceAuthCapability.UNSUPPORTED_API, capability)
        assertEquals("no system service may be looked up below API 30", 0, context.serviceLookups)
    }

    @Test
    fun inv23_belowApi30_everyCallStaysUnsupportedApi() {
        val context = BiometricServiceContext(base, trap)
        val provider = AndroidDeviceAuthCapabilityProvider(context)

        repeat(3) {
            assertEquals(DeviceAuthCapability.UNSUPPORTED_API, provider.current())
        }
        assertEquals(0, context.serviceLookups)
    }

    @Test
    fun inv23_belowApi30_neverReportsAvailable() {
        val provider = AndroidDeviceAuthCapabilityProvider(BiometricServiceContext(base, null))

        assertNotEquals(DeviceAuthCapability.AVAILABLE, provider.current())
        assertEquals(DeviceAuthCapability.UNSUPPORTED_API, provider.current())
    }
}

/** INV-23 / AMD-3 / AE-35: API 30 and above asks the library and maps the result, fresh on every call. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 31, 34])
@SuppressLint("NewApi")
class AndroidDeviceAuthCapabilityProviderApi30PlusTest {

    private val base: Context = ApplicationProvider.getApplicationContext()

    private fun capabilityFor(code: Int): DeviceAuthCapability {
        val context = BiometricServiceContext(base, frameworkReturning(code))
        return AndroidDeviceAuthCapabilityProvider(context).current()
    }

    @Test
    fun inv23_api30Plus_success_isAvailable() {
        assertEquals(DeviceAuthCapability.AVAILABLE, capabilityFor(BiometricManager.BIOMETRIC_SUCCESS))
    }

    @Test
    fun ae35_api30Plus_noneEnrolled_isNoCredential() {
        assertEquals(
            DeviceAuthCapability.NO_CREDENTIAL,
            capabilityFor(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED)
        )
    }

    @Test
    fun inv23_api30Plus_hwUnavailableAndNoHardware_areHwUnavailable() {
        assertEquals(
            DeviceAuthCapability.HW_UNAVAILABLE,
            capabilityFor(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE)
        )
        assertEquals(
            DeviceAuthCapability.HW_UNAVAILABLE,
            capabilityFor(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE)
        )
    }

    @Test
    fun inv23_api30Plus_otherResults_areUnknown_neverAvailable() {
        val others = listOf(
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            999,
            -1000
        )
        for (code in others) {
            assertEquals("code $code", DeviceAuthCapability.UNKNOWN, capabilityFor(code))
        }
    }

    @Test
    fun inv23_api30Plus_asksFrameworkForStrongOrCredentialOnly() {
        val framework = frameworkReturning(BiometricManager.BIOMETRIC_SUCCESS)
        AndroidDeviceAuthCapabilityProvider(BiometricServiceContext(base, framework)).current()

        // 0x800F = DEVICE_CREDENTIAL (0x8000) | BIOMETRIC_STRONG (0x000F). BIOMETRIC_WEAK (0x00FF) is never requested.
        verify(exactly = 1) { framework.canAuthenticate(0x800F) }
        verify(exactly = 0) { framework.canAuthenticate(BIOMETRIC_WEAK) }
        verify(exactly = 0) { framework.canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) }
    }

    @Test
    fun inv23_api30Plus_runtimeFailureOfTheCheck_isCheckFailed_transientAndNeverAvailable() {
        val framework = mockk<android.hardware.biometrics.BiometricManager>()
        every { framework.canAuthenticate(any<Int>()) } throws IllegalStateException("synthetic failure text")

        val capability = AndroidDeviceAuthCapabilityProvider(BiometricServiceContext(base, framework)).current()

        // S16 R3: an exception is not a genuine UNKNOWN device state. The controller keeps the lock on it.
        assertEquals(DeviceAuthCapability.CHECK_FAILED, capability)
        assertNotEquals(DeviceAuthCapability.UNKNOWN, capability)
        assertNotEquals(DeviceAuthCapability.AVAILABLE, capability)
    }

    @Test
    fun inv23_api30Plus_securityExceptionFromTheCheck_isCheckFailed() {
        val framework = mockk<android.hardware.biometrics.BiometricManager>()
        every { framework.canAuthenticate(any<Int>()) } throws SecurityException("synthetic")

        val capability = AndroidDeviceAuthCapabilityProvider(BiometricServiceContext(base, framework)).current()

        assertEquals(DeviceAuthCapability.CHECK_FAILED, capability)
    }

    @Test
    fun inv23_api30Plus_aFailedCheckDoesNotStick_theNextCallAsksAgain() {
        val framework = mockk<android.hardware.biometrics.BiometricManager>()
        every { framework.canAuthenticate(any<Int>()) } throws IllegalStateException("synthetic") andThen
            BiometricManager.BIOMETRIC_SUCCESS
        val provider = AndroidDeviceAuthCapabilityProvider(BiometricServiceContext(base, framework))

        assertEquals(DeviceAuthCapability.CHECK_FAILED, provider.current())
        assertEquals(DeviceAuthCapability.AVAILABLE, provider.current())
        verify(exactly = 2) { framework.canAuthenticate(any<Int>()) }
    }

    @Test
    fun amd3_api30Plus_reEvaluatesOnEveryCall_noCaching() {
        val framework = frameworkReturning(
            BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_SUCCESS
        )
        val provider = AndroidDeviceAuthCapabilityProvider(BiometricServiceContext(base, framework))

        assertEquals(DeviceAuthCapability.AVAILABLE, provider.current())
        assertEquals(DeviceAuthCapability.NO_CREDENTIAL, provider.current())
        assertEquals(DeviceAuthCapability.HW_UNAVAILABLE, provider.current())
        assertEquals(DeviceAuthCapability.AVAILABLE, provider.current())
        verify(exactly = 4) { framework.canAuthenticate(any<Int>()) }
    }

    @Test
    fun inv23_api30Plus_realFrameworkService_resultMatchesIndependentTable() {
        val service = base.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
        // Independent literal table (androidx BiometricManager result codes).
        val raw = service?.canAuthenticate(0x800F) ?: 1
        val expected = when (raw) {
            0 -> DeviceAuthCapability.AVAILABLE
            11 -> DeviceAuthCapability.NO_CREDENTIAL
            1, 12 -> DeviceAuthCapability.HW_UNAVAILABLE
            else -> DeviceAuthCapability.UNKNOWN
        }

        assertEquals(expected, AndroidDeviceAuthCapabilityProvider(base).current())
        assertNotEquals(DeviceAuthCapability.UNSUPPORTED_API, AndroidDeviceAuthCapabilityProvider(base).current())
    }
}

/** INV-23: pure mapping table and the pinned authenticator combination. No platform needed. */
class AndroidDeviceAuthCapabilityProviderMappingTest {

    private fun map(code: Int): DeviceAuthCapability =
        AndroidDeviceAuthCapabilityProvider.mapCanAuthenticate(code)

    @Test
    fun inv23_mapCanAuthenticate_fullTable() {
        assertEquals(DeviceAuthCapability.AVAILABLE, map(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(DeviceAuthCapability.NO_CREDENTIAL, map(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
        assertEquals(DeviceAuthCapability.HW_UNAVAILABLE, map(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertEquals(DeviceAuthCapability.HW_UNAVAILABLE, map(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals(DeviceAuthCapability.UNKNOWN, map(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED))
        assertEquals(DeviceAuthCapability.UNKNOWN, map(BiometricManager.BIOMETRIC_STATUS_UNKNOWN))
        assertEquals(DeviceAuthCapability.UNKNOWN, map(BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED))
    }

    @Test
    fun inv23_mapCanAuthenticate_literalCodes() {
        assertEquals(DeviceAuthCapability.AVAILABLE, map(0))
        assertEquals(DeviceAuthCapability.HW_UNAVAILABLE, map(1))
        assertEquals(DeviceAuthCapability.NO_CREDENTIAL, map(11))
        assertEquals(DeviceAuthCapability.HW_UNAVAILABLE, map(12))
        assertEquals(DeviceAuthCapability.UNKNOWN, map(15))
        assertEquals(DeviceAuthCapability.UNKNOWN, map(-1))
        assertEquals(DeviceAuthCapability.UNKNOWN, map(-2))
    }

    @Test
    fun inv23_mapCanAuthenticate_unknownCodes_areUnknown_neverAvailable() {
        for (code in listOf(2, 3, 13, 14, 16, 999, -3, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals("code $code", DeviceAuthCapability.UNKNOWN, map(code))
        }
    }

    @Test
    fun inv23_mapCanAuthenticate_onlySuccessIsAvailable() {
        val availableCodes = (-50..50).filter { map(it) == DeviceAuthCapability.AVAILABLE }

        assertEquals(listOf(0), availableCodes)
    }

    @Test
    fun inv23_mapCanAuthenticate_neverReturnsUnsupportedApi() {
        // UNSUPPORTED_API is decided by the API level alone, never by a library code.
        val results = (-50..50).map { map(it) }.toSet()

        assertTrue(DeviceAuthCapability.UNSUPPORTED_API !in results)
    }

    @Test
    fun inv23_mapCanAuthenticate_neverReturnsCheckFailed() {
        // CHECK_FAILED means the check itself threw (S16 R3). No library code can produce it, so an
        // unrecognised code stays a genuine UNKNOWN device state.
        val results = (-50..50).map { map(it) }.toSet()

        assertTrue(DeviceAuthCapability.CHECK_FAILED !in results)
        assertEquals(DeviceAuthCapability.UNKNOWN, map(999))
    }

    @Test
    fun inv23_allowedAuthenticators_isStrongOrDeviceCredential() {
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, APP_LOCK_ALLOWED_AUTHENTICATORS)
        assertEquals(0x800F, APP_LOCK_ALLOWED_AUTHENTICATORS)
    }

    @Test
    fun inv23_allowedAuthenticators_hasNoBiometricWeakBit() {
        // BIOMETRIC_WEAK (0x00FF) adds the 0x00F0 bits on top of BIOMETRIC_STRONG (0x000F).
        val weakOnlyBits = BIOMETRIC_WEAK and BIOMETRIC_STRONG.inv()
        assertTrue("weak-only bits must be non-empty for this check to mean anything", weakOnlyBits != 0)
        assertEquals(0, APP_LOCK_ALLOWED_AUTHENTICATORS and weakOnlyBits)
        // BIOMETRIC_STRONG is a subset of BIOMETRIC_WEAK: masking with WEAK leaves exactly STRONG.
        assertEquals(BIOMETRIC_STRONG, APP_LOCK_ALLOWED_AUTHENTICATORS and BIOMETRIC_WEAK)
        assertEquals(0x800F, APP_LOCK_ALLOWED_AUTHENTICATORS)
    }

    @Test
    fun inv23_allowedAuthenticators_includesDeviceCredential() {
        assertEquals(DEVICE_CREDENTIAL, APP_LOCK_ALLOWED_AUTHENTICATORS and DEVICE_CREDENTIAL)
    }
}
