package com.yourname.expensetracker.ui.applock

import androidx.biometric.BiometricPrompt
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthErrorBucket
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S15, INV-34 (auth outcomes) and INV-37 (bounded buckets), AE-45 (lockout), AE-50 (no credential).
 *
 * Only a matching AUTH_SUCCEEDED unlocks. The error mapping can never produce
 * [DeviceAuthOutcome.Succeeded]; every unlisted code is bounded to Failed(OTHER).
 *
 * Not covered here: "one prompt in flight" and "duplicate callback dropped". Both live inside
 * [BiometricPromptDeviceAuthenticator], which builds a library BiometricPrompt on a FragmentActivity
 * and receives its callbacks from the library's internal fragment. Driving that needs the library's
 * internals, so the behaviour is left to S16 controller tests (fake DeviceAuthenticator) and the
 * reviewer; this file tests the pure mapping function that every error callback goes through.
 */
class BiometricPromptOutcomeMappingTest {

    // The library error codes written out as literals (androidx BiometricPrompt.ERROR_*).
    private val errorHwUnavailable = 1
    private val errorUnableToProcess = 2
    private val errorTimeout = 3
    private val errorNoSpace = 4
    private val errorCanceled = 5
    private val errorLockout = 7
    private val errorVendor = 8
    private val errorLockoutPermanent = 9
    private val errorUserCanceled = 10
    private val errorNoBiometrics = 11
    private val errorHwNotPresent = 12
    private val errorNegativeButton = 13
    private val errorNoDeviceCredential = 14
    private val errorSecurityUpdateRequired = 15

    @Test
    fun inv34_libraryConstants_matchTheLiteralsUsedByThisTest() {
        assertEquals(errorHwUnavailable, BiometricPrompt.ERROR_HW_UNAVAILABLE)
        assertEquals(errorUnableToProcess, BiometricPrompt.ERROR_UNABLE_TO_PROCESS)
        assertEquals(errorTimeout, BiometricPrompt.ERROR_TIMEOUT)
        assertEquals(errorNoSpace, BiometricPrompt.ERROR_NO_SPACE)
        assertEquals(errorCanceled, BiometricPrompt.ERROR_CANCELED)
        assertEquals(errorLockout, BiometricPrompt.ERROR_LOCKOUT)
        assertEquals(errorVendor, BiometricPrompt.ERROR_VENDOR)
        assertEquals(errorLockoutPermanent, BiometricPrompt.ERROR_LOCKOUT_PERMANENT)
        assertEquals(errorUserCanceled, BiometricPrompt.ERROR_USER_CANCELED)
        assertEquals(errorNoBiometrics, BiometricPrompt.ERROR_NO_BIOMETRICS)
        assertEquals(errorHwNotPresent, BiometricPrompt.ERROR_HW_NOT_PRESENT)
        assertEquals(errorNegativeButton, BiometricPrompt.ERROR_NEGATIVE_BUTTON)
        assertEquals(errorNoDeviceCredential, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL)
    }

    // ---- cancel ----------------------------------------------------------------------------

    @Test
    fun inv34_userCanceled_isCancelled() {
        assertEquals(DeviceAuthOutcome.Cancelled, mapAuthenticationError(errorUserCanceled))
    }

    @Test
    fun inv34_negativeButton_isCancelled() {
        assertEquals(DeviceAuthOutcome.Cancelled, mapAuthenticationError(errorNegativeButton))
    }

    /**
     * G1c pin: the user-initiated ends map to Cancelled while ERROR_CANCELED (the system or a client cancel echo)
     * stays a bounded failure. The silent host-loss cancel relies on this split: it must never be remapped to Cancelled.
     */
    @Test
    fun g1c_userCancelAndNegativeButton_areCancelled_butLibraryCanceledIsFailedSystemCanceled() {
        assertEquals(DeviceAuthOutcome.Cancelled, mapAuthenticationError(BiometricPrompt.ERROR_USER_CANCELED))
        assertEquals(DeviceAuthOutcome.Cancelled, mapAuthenticationError(BiometricPrompt.ERROR_NEGATIVE_BUTTON))
        assertEquals(
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED),
            mapAuthenticationError(BiometricPrompt.ERROR_CANCELED)
        )
        assertNotEquals(DeviceAuthOutcome.Cancelled, mapAuthenticationError(BiometricPrompt.ERROR_CANCELED))
    }

    // ---- AE-45 lockout ---------------------------------------------------------------------

    @Test
    fun ae45_temporaryLockout_isLockedOutNotPermanent() {
        assertEquals(DeviceAuthOutcome.LockedOut(permanent = false), mapAuthenticationError(errorLockout))
    }

    @Test
    fun ae45_permanentLockout_isLockedOutPermanent() {
        assertEquals(DeviceAuthOutcome.LockedOut(permanent = true), mapAuthenticationError(errorLockoutPermanent))
    }

    @Test
    fun ae45_temporaryAndPermanentLockout_areDistinguished() {
        assertNotEquals(
            mapAuthenticationError(errorLockout),
            mapAuthenticationError(errorLockoutPermanent)
        )
    }

    // ---- AE-50 not available ---------------------------------------------------------------

    @Test
    fun ae50_noDeviceCredential_isNotAvailable() {
        assertEquals(DeviceAuthOutcome.NotAvailable, mapAuthenticationError(errorNoDeviceCredential))
    }

    @Test
    fun ae50_noBiometrics_isNotAvailable() {
        assertEquals(DeviceAuthOutcome.NotAvailable, mapAuthenticationError(errorNoBiometrics))
    }

    @Test
    fun ae50_hwNotPresent_isNotAvailable() {
        assertEquals(DeviceAuthOutcome.NotAvailable, mapAuthenticationError(errorHwNotPresent))
    }

    // ---- INV-37 buckets --------------------------------------------------------------------

    @Test
    fun inv37_hardwareFamily_isFailedHwUnavailable() {
        val expected = DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.HW_UNAVAILABLE)
        assertEquals(expected, mapAuthenticationError(errorHwUnavailable))
        assertEquals(expected, mapAuthenticationError(errorUnableToProcess))
        assertEquals(expected, mapAuthenticationError(errorNoSpace))
        assertEquals(expected, mapAuthenticationError(errorVendor))
    }

    @Test
    fun inv37_timeout_isFailedTimeout() {
        assertEquals(
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.TIMEOUT),
            mapAuthenticationError(errorTimeout)
        )
    }

    @Test
    fun inv37_systemCanceled_isFailedSystemCanceled() {
        assertEquals(
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.SYSTEM_CANCELED),
            mapAuthenticationError(errorCanceled)
        )
    }

    @Test
    fun inv37_securityUpdateRequired_isFailedOther() {
        assertEquals(
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER),
            mapAuthenticationError(errorSecurityUpdateRequired)
        )
    }

    @Test
    fun inv37_unknownCodes_areFailedOther() {
        val unknown = listOf(0, 6, 16, 17, 100, 999, -1, -2, Int.MAX_VALUE, Int.MIN_VALUE)
        for (code in unknown) {
            assertEquals(
                "code $code",
                DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER),
                mapAuthenticationError(code)
            )
        }
    }

    // ---- INV-34: nothing but AUTH_SUCCEEDED authenticates -----------------------------------

    @Test
    fun inv34_noErrorCode_everMapsToSucceeded() {
        val outcomes = (-100..100).map { mapAuthenticationError(it) } +
            listOf(Int.MAX_VALUE, Int.MIN_VALUE).map { mapAuthenticationError(it) }

        assertFalse(outcomes.any { it == DeviceAuthOutcome.Succeeded })
        assertTrue(outcomes.none { it is DeviceAuthOutcome.Succeeded })
    }

    @Test
    fun inv34_errorCode0_isNotSucceeded() {
        // 0 is the success-like value in several platform APIs; as an error code it must stay a failure.
        assertEquals(
            DeviceAuthOutcome.Failed(DeviceAuthErrorBucket.OTHER),
            mapAuthenticationError(0)
        )
    }

    @Test
    fun inv34_everyListedCode_mapsToAKnownBoundedOutcome() {
        val listed = listOf(
            errorHwUnavailable, errorUnableToProcess, errorTimeout, errorNoSpace, errorCanceled,
            errorLockout, errorVendor, errorLockoutPermanent, errorUserCanceled, errorNoBiometrics,
            errorHwNotPresent, errorNegativeButton, errorNoDeviceCredential, errorSecurityUpdateRequired
        )
        for (code in listed) {
            val outcome = mapAuthenticationError(code)
            val bounded = outcome is DeviceAuthOutcome.Cancelled ||
                outcome is DeviceAuthOutcome.LockedOut ||
                outcome is DeviceAuthOutcome.NotAvailable ||
                outcome is DeviceAuthOutcome.Failed
            assertTrue("code $code -> $outcome", bounded)
        }
    }

    @Test
    fun inv34_mapping_isDeterministic() {
        for (code in -5..20) {
            assertEquals(mapAuthenticationError(code), mapAuthenticationError(code))
        }
    }
}
