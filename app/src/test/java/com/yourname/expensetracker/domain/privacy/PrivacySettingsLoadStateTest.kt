package com.yourname.expensetracker.domain.privacy

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * PR1 acceptance tests:
 *
 * datastore_corruption_disables_notification_capture
 * datastore_corruption_sets_raw_notification_do_not_store
 * datastore_corruption_sets_raw_ocr_do_not_store
 * datastore_corruption_sets_email_do_not_store
 * datastore_corruption_disables_cloud_ai
 * first_run_defaults_are_distinct_from_corruption_defaults
 * privacy_update_applies_actual_persisted_updated_settings
 */
class PrivacySettingsLoadStateTest {

    // ── Fail-closed defaults contract ──────────────────────────────────────────

    @Test
    fun datastore_corruption_disables_notification_capture() {
        assertFalse(PrivacySettings.FAIL_CLOSED_DEFAULTS.notificationCaptureEnabled)
    }

    @Test
    fun datastore_corruption_sets_raw_notification_do_not_store() {
        assertEquals(RawStorageMode.DO_NOT_STORE, PrivacySettings.FAIL_CLOSED_DEFAULTS.rawNotificationStorageMode)
    }

    @Test
    fun datastore_corruption_sets_raw_ocr_do_not_store() {
        assertEquals(RawStorageMode.DO_NOT_STORE, PrivacySettings.FAIL_CLOSED_DEFAULTS.rawOcrStorageMode)
    }

    @Test
    fun datastore_corruption_sets_email_do_not_store() {
        assertEquals(RawStorageMode.DO_NOT_STORE, PrivacySettings.FAIL_CLOSED_DEFAULTS.emailReceiptStorageMode)
    }

    @Test
    fun datastore_corruption_disables_cloud_ai() {
        assertFalse(PrivacySettings.FAIL_CLOSED_DEFAULTS.cloudAiEnabled)
    }

    @Test
    fun datastore_corruption_disables_location() {
        assertFalse(PrivacySettings.FAIL_CLOSED_DEFAULTS.externalGeocodingEnabled)
        assertFalse(PrivacySettings.FAIL_CLOSED_DEFAULTS.backgroundLocationBackfillEnabled)
        assertFalse(PrivacySettings.FAIL_CLOSED_DEFAULTS.deviceGpsLocationEnabled)
    }

    @Test
    fun datastore_corruption_disables_bank_statement_ai() {
        assertFalse(PrivacySettings.FAIL_CLOSED_DEFAULTS.bankStatementAiEnabled)
    }

    @Test
    fun datastore_corruption_keeps_redact_before_cloud_true() {
        assertTrue(PrivacySettings.FAIL_CLOSED_DEFAULTS.redactBeforeCloud)
    }

    @Test
    fun datastore_corruption_keeps_encrypted_backup_enabled() {
        assertTrue(PrivacySettings.FAIL_CLOSED_DEFAULTS.encryptedBackupEnabled)
    }

    @Test
    fun datastore_corruption_disables_debug_persistence() {
        assertFalse(PrivacySettings.FAIL_CLOSED_DEFAULTS.debugDataPersistenceEnabled)
    }

    // ── First run defaults distinct from corruption defaults ───────────────────

    @Test
    fun first_run_defaults_are_distinct_from_corruption_defaults() {
        val firstRun = PrivacySettings()
        val failClosed = PrivacySettings.FAIL_CLOSED_DEFAULTS
        // First run allows notification capture; corruption does not
        assertTrue(firstRun.notificationCaptureEnabled)
        assertFalse(failClosed.notificationCaptureEnabled)
        // First run allows STORE_RAW for notifications; corruption uses DO_NOT_STORE
        assertEquals(RawStorageMode.STORE_RAW, firstRun.rawNotificationStorageMode)
        assertEquals(RawStorageMode.DO_NOT_STORE, failClosed.rawNotificationStorageMode)
    }

    // ── Load state sealed interface ────────────────────────────────────────────

    @Test
    fun loaded_state_carries_settings() {
        val settings = PrivacySettings(cloudAiEnabled = true)
        val state: PrivacySettingsLoadState = PrivacySettingsLoadState.Loaded(settings)
        assertTrue(state is PrivacySettingsLoadState.Loaded)
        assertEquals(settings, (state as PrivacySettingsLoadState.Loaded).settings)
    }

    @Test
    fun first_run_state_carries_settings() {
        val settings = PrivacySettings()
        val state: PrivacySettingsLoadState = PrivacySettingsLoadState.FirstRunDefault(settings)
        assertTrue(state is PrivacySettingsLoadState.FirstRunDefault)
        assertEquals(settings, (state as PrivacySettingsLoadState.FirstRunDefault).settings)
    }

    @Test
    fun corrupted_state_carries_fail_closed_settings_and_reason() {
        val state = PrivacySettingsLoadState.CorruptedFailClosed(
            settings = PrivacySettings.FAIL_CLOSED_DEFAULTS,
            reason = "IO error"
        )
        assertFalse(state.settings.notificationCaptureEnabled)
        assertEquals(RawStorageMode.DO_NOT_STORE, state.settings.rawNotificationStorageMode)
        assertEquals("IO error", state.reason)
    }

    // ── Repository contract via fake ───────────────────────────────────────────

    @Test
    fun privacy_update_applies_actual_persisted_updated_settings() = runTest {
        val repo = FakePrivacySettingsRepository()
        // Start with default (notificationCaptureEnabled = true)
        val before = repo.getSettings()
        assertTrue(before.notificationCaptureEnabled)

        // Apply transform
        repo.updateSettings { it.copy(notificationCaptureEnabled = false) }

        val after = repo.getSettings()
        assertFalse("updateSettings must persist the transformed value", after.notificationCaptureEnabled)
    }

    @Test
    fun corrupt_state_emits_fail_closed_from_observe_load_state() = runTest {
        val repo = FakePrivacySettingsRepository(
            corruptOnRead = true,
            corruptionReason = "simulated IO error"
        )
        val state = repo.observeLoadState().first()
        assertTrue(state is PrivacySettingsLoadState.CorruptedFailClosed)
        assertFalse((state as PrivacySettingsLoadState.CorruptedFailClosed).settings.notificationCaptureEnabled)
        assertEquals(RawStorageMode.DO_NOT_STORE, state.settings.rawNotificationStorageMode)
    }

    @Test
    fun first_run_state_emits_first_run_default_from_observe_load_state() = runTest {
        val repo = FakePrivacySettingsRepository(isEmpty = true)
        val state = repo.observeLoadState().first()
        assertTrue(state is PrivacySettingsLoadState.FirstRunDefault)
    }
}

// ── Fake repository used in tests ─────────────────────────────────────────────

class FakePrivacySettingsRepository(
    initialSettings: PrivacySettings = PrivacySettings(),
    private val isEmpty: Boolean = false,
    private val corruptOnRead: Boolean = false,
    private val corruptionReason: String = "corrupted"
) : PrivacySettingsRepository {

    private val _settings = MutableStateFlow(initialSettings)

    override fun observeSettings(): Flow<PrivacySettings> = _settings

    override fun observeLoadState(): Flow<PrivacySettingsLoadState> = _settings.map { settings ->
        when {
            corruptOnRead -> PrivacySettingsLoadState.CorruptedFailClosed(
                PrivacySettings.FAIL_CLOSED_DEFAULTS, corruptionReason
            )
            isEmpty -> PrivacySettingsLoadState.FirstRunDefault(settings)
            else -> PrivacySettingsLoadState.Loaded(settings)
        }
    }

    override suspend fun getSettings(): PrivacySettings = when {
        corruptOnRead -> PrivacySettings.FAIL_CLOSED_DEFAULTS
        else -> _settings.value
    }

    override suspend fun getLoadState(): PrivacySettingsLoadState = when {
        corruptOnRead -> PrivacySettingsLoadState.CorruptedFailClosed(
            PrivacySettings.FAIL_CLOSED_DEFAULTS, corruptionReason
        )
        isEmpty -> PrivacySettingsLoadState.FirstRunDefault(_settings.value)
        else -> PrivacySettingsLoadState.Loaded(_settings.value)
    }

    override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {
        _settings.value = transform(_settings.value)
    }
}
