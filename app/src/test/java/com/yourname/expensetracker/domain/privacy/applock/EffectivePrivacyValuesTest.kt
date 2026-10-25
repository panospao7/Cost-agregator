package com.yourname.expensetracker.domain.privacy.applock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INV-2 / spec 2.3: unreadable or missing reads never collapse into the permissive defaults.
 * AE-7 (domain part: unreadable -> discreet), AE-51 (fail-closed record F).
 */
class EffectivePrivacyValuesTest {

    @Test
    fun loaded_discreet_off_is_not_discreet() {
        val state = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = false))
        assertFalse(EffectivePrivacyValues.effectiveDiscreet(state))
    }

    @Test
    fun loaded_discreet_on_is_discreet() {
        val state = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = true))
        assertTrue(EffectivePrivacyValues.effectiveDiscreet(state))
    }

    @Test
    fun first_run_default_is_not_discreet() {
        val state = AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS)
        assertFalse(EffectivePrivacyValues.effectiveDiscreet(state))
    }

    @Test
    fun every_unreadable_cause_is_discreet() {
        for (cause in SettingsUnreadableCause.values()) {
            assertTrue(
                "Unreadable($cause) must be discreet",
                EffectivePrivacyValues.effectiveDiscreet(AppLockSettingsLoadState.Unreadable(cause))
            )
        }
    }

    @Test
    fun read_that_produced_no_state_in_time_is_discreet() {
        assertTrue(EffectivePrivacyValues.effectiveDiscreet(null))
    }

    @Test
    fun effective_settings_for_unreadable_is_the_fail_closed_record_not_the_defaults() {
        for (cause in SettingsUnreadableCause.values()) {
            val effective = EffectivePrivacyValues.effectiveSettings(AppLockSettingsLoadState.Unreadable(cause))
            assertEquals(AppLockPrivacySettings.FAIL_CLOSED, effective)
            assertNotEquals(AppLockPrivacySettings.DEFAULTS, effective)
        }
        assertEquals(AppLockPrivacySettings.FAIL_CLOSED, EffectivePrivacyValues.effectiveSettings(null))
    }

    @Test
    fun effective_settings_for_readable_states_are_the_stored_values() {
        val stored = AppLockPrivacySettings(discreetNotifications = true)
        assertEquals(stored, EffectivePrivacyValues.effectiveSettings(AppLockSettingsLoadState.Loaded(stored)))
        assertEquals(
            AppLockPrivacySettings.DEFAULTS,
            EffectivePrivacyValues.effectiveSettings(
                AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS)
            )
        )
    }

    @Test
    fun fail_closed_record_is_discreet_and_defaults_are_not() {
        assertTrue(AppLockPrivacySettings.FAIL_CLOSED.discreetNotifications)
        assertFalse(AppLockPrivacySettings.DEFAULTS.discreetNotifications)
        assertFalse(AppLockPrivacySettings.DEFAULT_DISCREET_NOTIFICATIONS)
    }

    // ---------------------------------------------------------------------------------
    // 1b terms (S14): INV-2, INV-6 (OD-2a), INV-29 / INV-53 settings part, record F
    // ---------------------------------------------------------------------------------

    private fun loaded(
        discreet: Boolean = false,
        lock: Boolean = false,
        timeout: AutoLockTimeout = AutoLockTimeout.M1,
        secure: Boolean = false
    ) = AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreet, lock, timeout, secure))

    @Test
    fun defaults_are_lock_off_m1_secure_off() {
        val defaults = AppLockPrivacySettings.DEFAULTS
        assertFalse(defaults.appLockEnabled)
        assertEquals(AutoLockTimeout.M1, defaults.autoLockTimeout)
        assertFalse(defaults.secureScreen)
    }

    @Test
    fun fail_closed_record_carries_every_1b_restrictive_value() {
        val f = AppLockPrivacySettings.FAIL_CLOSED
        assertTrue(f.discreetNotifications)
        assertTrue(f.appLockEnabled)
        assertEquals(AutoLockTimeout.IMMEDIATE, f.autoLockTimeout)
        assertTrue(f.secureScreen)
        assertEquals(0L, f.autoLockTimeout.millis)
    }

    @Test
    fun timeout_tokens_round_trip_and_unknown_tokens_are_rejected() {
        for (timeout in AutoLockTimeout.entries) {
            assertEquals(timeout, AutoLockTimeout.fromToken(timeout.token))
        }
        assertEquals(listOf("IMMEDIATE", "S30", "M1", "M5", "M15"), AutoLockTimeout.entries.map { it.token })
        assertEquals(
            listOf(0L, 30_000L, 60_000L, 300_000L, 900_000L),
            AutoLockTimeout.entries.map { it.millis }
        )
        assertEquals(null, AutoLockTimeout.fromToken("m1"))
        assertEquals(null, AutoLockTimeout.fromToken("90000"))
        assertEquals(null, AutoLockTimeout.fromToken(""))
    }

    @Test
    fun lock_enabled_forces_discreet_in_every_readable_state() {
        assertTrue(EffectivePrivacyValues.effectiveDiscreet(loaded(discreet = false, lock = true)))
        assertTrue(EffectivePrivacyValues.effectiveDiscreet(loaded(discreet = true, lock = true)))
        assertFalse(EffectivePrivacyValues.effectiveDiscreet(loaded(discreet = false, lock = false, secure = true)))
        assertTrue(
            EffectivePrivacyValues.effectiveDiscreet(
                AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings(appLockEnabled = true))
            )
        )
    }

    @Test
    fun effective_lock_enabled_is_the_flag_or_fail_closed() {
        assertFalse(EffectivePrivacyValues.effectiveLockEnabled(loaded(lock = false)))
        assertTrue(EffectivePrivacyValues.effectiveLockEnabled(loaded(lock = true)))
        assertFalse(
            EffectivePrivacyValues.effectiveLockEnabled(
                AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS)
            )
        )
        assertTrue(EffectivePrivacyValues.effectiveLockEnabled(null))
        for (cause in SettingsUnreadableCause.values()) {
            assertTrue(EffectivePrivacyValues.effectiveLockEnabled(AppLockSettingsLoadState.Unreadable(cause)))
        }
    }

    @Test
    fun effective_secure_screen_truth_table() {
        // secureScreen OR appLockEnabled OR Unreadable/no read OR RESOLVING; no AUTHENTICATING term.
        assertFalse(EffectivePrivacyValues.effectiveSecureScreen(loaded(), resolving = false))
        assertTrue(EffectivePrivacyValues.effectiveSecureScreen(loaded(secure = true), resolving = false))
        assertTrue(EffectivePrivacyValues.effectiveSecureScreen(loaded(lock = true), resolving = false))
        assertTrue(EffectivePrivacyValues.effectiveSecureScreen(loaded(), resolving = true))
        assertFalse(
            EffectivePrivacyValues.effectiveSecureScreen(
                AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings.DEFAULTS),
                resolving = false
            )
        )
        assertTrue(EffectivePrivacyValues.effectiveSecureScreen(null, resolving = false))
        for (cause in SettingsUnreadableCause.values()) {
            assertTrue(
                EffectivePrivacyValues.effectiveSecureScreen(AppLockSettingsLoadState.Unreadable(cause), resolving = false)
            )
        }
    }

    @Test
    fun effective_secure_screen_follows_committed_settings_only_during_a_prompt() {
        // INV-53: a prompt is not an input. Lock off and secure off stays off while not resolving,
        // and an enabled lock stays on, regardless of any in-flight authentication.
        assertFalse(EffectivePrivacyValues.effectiveSecureScreen(loaded(lock = false, secure = false), resolving = false))
        assertTrue(EffectivePrivacyValues.effectiveSecureScreen(loaded(lock = true, secure = false), resolving = false))
        assertTrue(EffectivePrivacyValues.effectiveSecureScreen(loaded(lock = false, secure = true), resolving = false))
    }

    @Test
    fun persistent_and_transient_causes_are_classified_per_inv_44() {
        assertTrue(SettingsUnreadableCause.CORRUPTED.isPersistent)
        assertTrue(SettingsUnreadableCause.INVALID_VALUE.isPersistent)
        assertFalse(SettingsUnreadableCause.IO_ERROR.isPersistent)
        assertFalse(SettingsUnreadableCause.TIMEOUT.isPersistent)
    }
}
