package com.yourname.expensetracker.domain.privacy.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure notice table of INV-51 (AMD-6, AMD-22): states x load states x the biometric flag. */
class LockNoticeDerivationTest {

    private val readable: AppLockSettingsLoadState = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings())
    private val firstRun: AppLockSettingsLoadState = AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS)

    private fun unreadable(cause: SettingsUnreadableCause): AppLockSettingsLoadState =
        AppLockSettingsLoadState.Unreadable(cause)

    private val persistent = SettingsUnreadableCause.entries.filter { it.isPersistent }
    private val transientCauses = SettingsUnreadableCause.entries.filterNot { it.isPersistent }

    private val allLoadStates: List<AppLockSettingsLoadState?> =
        listOf(null, readable, firstRun) + SettingsUnreadableCause.entries.map { unreadable(it) }

    @Test
    fun `RESOLVING, every LOCKED cause and AUTHENTICATING UNLOCK carry no notice whatever the load state or biometric flag`() {
        val closed: List<AppLockState> = listOf(AppLockState.Resolving) +
            LockCause.entries.map { AppLockState.Locked(it) } +
            AppLockState.Authenticating(1L, AuthPurpose.UNLOCK)

        for (state in closed) {
            for (loadState in allLoadStates) {
                for (biometric in listOf(false, true)) {
                    assertNull(
                        "$state / $loadState / $biometric",
                        LockNoticeDerivation.derive(state, loadState, biometric)
                    )
                }
            }
        }
    }

    @Test
    fun `INERT wins over every other notice for every reason`() {
        for (reason in InertReason.entries) {
            for (loadState in allLoadStates) {
                for (biometric in listOf(false, true)) {
                    assertEquals(
                        "$reason / $loadState / $biometric",
                        LockNotice.Inert(reason),
                        LockNoticeDerivation.derive(AppLockState.Inert(reason), loadState, biometric)
                    )
                }
            }
        }
    }

    @Test
    fun `an open state shows REVIEW_SETTINGS only for a persistent unreadable load state`() {
        for (state in listOf(AppLockState.Disabled, AppLockState.Unlocked)) {
            for (cause in persistent) {
                assertEquals("$state / $cause", LockNotice.ReviewSettings, LockNoticeDerivation.derive(state, unreadable(cause)))
            }
            for (cause in transientCauses) {
                assertNull("$state / $cause", LockNoticeDerivation.derive(state, unreadable(cause)))
            }
            assertNull(LockNoticeDerivation.derive(state, null))
            assertNull(LockNoticeDerivation.derive(state, readable))
            assertNull(LockNoticeDerivation.derive(state, firstRun))
        }
    }

    @Test
    fun `REVIEW_SETTINGS has priority over the biometric notice, which waits and is not dropped`() {
        for (cause in persistent) {
            assertEquals(
                LockNotice.ReviewSettings,
                LockNoticeDerivation.derive(AppLockState.Unlocked, unreadable(cause), biometricPending = true)
            )
        }
        // The lower notice is shown once the higher condition ended.
        assertEquals(
            LockNotice.BiometricInvalidated,
            LockNoticeDerivation.derive(AppLockState.Unlocked, readable, biometricPending = true)
        )
        assertEquals(
            LockNotice.BiometricInvalidated,
            LockNoticeDerivation.derive(AppLockState.Disabled, unreadable(SettingsUnreadableCause.IO_ERROR), biometricPending = true)
        )
    }

    @Test
    fun `the biometric flag defaults to false`() {
        assertNull(LockNoticeDerivation.derive(AppLockState.Unlocked, readable))
        assertNull(LockNoticeDerivation.derive(AppLockState.Disabled, null))
    }

    @Test
    fun `AMD-22 an open-origin prompt keeps the origin's notice unchanged`() {
        val enable = AppLockState.Authenticating(7L, AuthPurpose.ENABLE)
        val change = AppLockState.Authenticating(8L, AuthPurpose.CONFIRM_CHANGE)
        for (loadState in allLoadStates) {
            for (biometric in listOf(false, true)) {
                assertEquals(
                    "ENABLE / $loadState / $biometric",
                    LockNoticeDerivation.derive(AppLockState.Disabled, loadState, biometric),
                    LockNoticeDerivation.derive(enable, loadState, biometric)
                )
                assertEquals(
                    "CONFIRM_CHANGE / $loadState / $biometric",
                    LockNoticeDerivation.derive(AppLockState.Unlocked, loadState, biometric),
                    LockNoticeDerivation.derive(change, loadState, biometric)
                )
            }
        }
        // AE-115: the review notice stays shown while the CONFIRM_CHANGE prompt is open.
        assertEquals(
            LockNotice.ReviewSettings,
            LockNoticeDerivation.derive(change, unreadable(SettingsUnreadableCause.CORRUPTED))
        )
    }
}
