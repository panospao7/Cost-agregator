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

    @Test
    fun persistent_and_transient_causes_are_classified_per_inv_44() {
        assertTrue(SettingsUnreadableCause.CORRUPTED.isPersistent)
        assertTrue(SettingsUnreadableCause.INVALID_VALUE.isPersistent)
        assertFalse(SettingsUnreadableCause.IO_ERROR.isPersistent)
        assertFalse(SettingsUnreadableCause.TIMEOUT.isPersistent)
    }
}
