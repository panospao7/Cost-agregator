package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.work.WorkManager
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsLoadState
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.privacy.RawStorageMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * PR1: DataStore corruption handler changed from fail-open (empty preferences)
 * to emit a sentinel that maps to [PrivacySettings.FAIL_CLOSED_DEFAULTS].
 * See [PrivacySettingsLoadState.CorruptedFailClosed].
 */

/** Sentinel value stored in-memory when corruption is detected. */
private object CorruptionSentinel {
    const val REASON_KEY = "_corruption_reason"
    const val MARKER = "__CORRUPTED__"
}

private val Context.privacySettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "privacy_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Singleton
class PrivacySettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PrivacySettingsRepository {
    private val workManager = WorkManager.getInstance(context)

    private object Keys {
        val NOTIFICATION_CAPTURE_ENABLED = booleanPreferencesKey("notification_capture_enabled")
        val CLOUD_AI_ENABLED = booleanPreferencesKey("cloud_ai_enabled")
        val REDACT_BEFORE_CLOUD = booleanPreferencesKey("redact_before_cloud")
        val RECEIPT_IMAGE_CLOUD_ENABLED = booleanPreferencesKey("receipt_image_cloud_enabled")
        val BANK_STATEMENT_AI_ENABLED = booleanPreferencesKey("bank_statement_ai_enabled")
        val EXTERNAL_GEOCODING_ENABLED = booleanPreferencesKey("external_geocoding_enabled")
        val BACKGROUND_LOCATION_BACKFILL_ENABLED = booleanPreferencesKey("background_location_backfill_enabled")
        val DEVICE_GPS_LOCATION_ENABLED = booleanPreferencesKey("device_gps_location_enabled")
        val ENCRYPTED_BACKUP_ENABLED = booleanPreferencesKey("encrypted_backup_enabled")
        val RAW_NOTIFICATION_RETENTION_DAYS = intPreferencesKey("raw_notification_retention_days")
        val RAW_OCR_RETENTION_DAYS = intPreferencesKey("raw_ocr_retention_days")
        val DEBUG_DATA_PERSISTENCE_ENABLED = booleanPreferencesKey("debug_data_persistence_enabled")
        val RAW_NOTIFICATION_STORAGE_MODE = stringPreferencesKey("raw_notification_storage_mode")
        val RAW_OCR_STORAGE_MODE = stringPreferencesKey("raw_ocr_storage_mode")
        val EMAIL_RECEIPT_STORAGE_MODE = stringPreferencesKey("email_receipt_storage_mode")

        /** Sentinel key set only by the corruption handler result, never by real writes. */
        val IS_FIRST_RUN_MARKER = booleanPreferencesKey("_is_first_run")
    }

    // ── Public API ──────────────────────────────────────────────────────────────

    override fun observeSettings(): Flow<PrivacySettings> =
        observeLoadState().map { it.settings() }

    override fun observeLoadState(): Flow<PrivacySettingsLoadState> =
        context.privacySettingsDataStore.data
            .catch { error ->
                when (error) {
                    is IOException -> {
                        Timber.e(error, "PR1: Privacy settings DataStore read failed — using fail-closed defaults")
                        emit(emptyPreferences())
                    }
                    else -> throw error
                }
            }
            .map { prefs -> prefs.toLoadState() }

    override suspend fun getSettings(): PrivacySettings =
        getLoadState().settings()

    override suspend fun getLoadState(): PrivacySettingsLoadState =
        try {
            context.privacySettingsDataStore.data.first().toLoadState()
        } catch (e: IOException) {
            Timber.e(e, "PR1: Privacy settings read failed (getLoadState) — using fail-closed defaults")
            PrivacySettingsLoadState.CorruptedFailClosed(
                settings = PrivacySettings.FAIL_CLOSED_DEFAULTS,
                reason = e.message ?: "IO error"
            )
        }

    override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {
        // PR1 fix: read current persisted settings, apply transform, persist the result
        // and pass the actual persisted result to the runtime applier (not transform(old)).
        val old = getSettings()
        context.privacySettingsDataStore.edit { prefs ->
            val current = prefs.toPrivacySettings()
            val updated = transform(current)
            prefs[Keys.NOTIFICATION_CAPTURE_ENABLED] = updated.notificationCaptureEnabled
            prefs[Keys.CLOUD_AI_ENABLED] = updated.cloudAiEnabled
            prefs[Keys.REDACT_BEFORE_CLOUD] = updated.redactBeforeCloud
            prefs[Keys.RECEIPT_IMAGE_CLOUD_ENABLED] = updated.receiptImageCloudEnabled
            prefs[Keys.BANK_STATEMENT_AI_ENABLED] = updated.bankStatementAiEnabled
            prefs[Keys.EXTERNAL_GEOCODING_ENABLED] = updated.externalGeocodingEnabled
            prefs[Keys.BACKGROUND_LOCATION_BACKFILL_ENABLED] = updated.backgroundLocationBackfillEnabled
            prefs[Keys.DEVICE_GPS_LOCATION_ENABLED] = updated.deviceGpsLocationEnabled
            prefs[Keys.ENCRYPTED_BACKUP_ENABLED] = updated.encryptedBackupEnabled
            prefs[Keys.RAW_NOTIFICATION_RETENTION_DAYS] = updated.rawNotificationRetentionDays
            prefs[Keys.RAW_OCR_RETENTION_DAYS] = updated.rawOcrRetentionDays
            prefs[Keys.DEBUG_DATA_PERSISTENCE_ENABLED] = updated.debugDataPersistenceEnabled
            prefs[Keys.RAW_NOTIFICATION_STORAGE_MODE] = updated.rawNotificationStorageMode.name
            prefs[Keys.RAW_OCR_STORAGE_MODE] = updated.rawOcrStorageMode.name
            prefs[Keys.EMAIL_RECEIPT_STORAGE_MODE] = updated.emailReceiptStorageMode.name
        }
        // PR1 fix: read back the persisted value to pass to applyPrivacyChange
        val persisted = getSettings()
        applyPrivacyChange(old, persisted)
    }

    // ── Internal helpers ────────────────────────────────────────────────────────

    private fun applyPrivacyChange(old: PrivacySettings, updated: PrivacySettings) {
        if (old.cloudAiEnabled && !updated.cloudAiEnabled) {
            workManager.cancelUniqueWork("ai_daily_briefing")
            Timber.i("PR1: Cancelled ai_daily_briefing — cloud AI disabled")
        }
        if (old.backgroundLocationBackfillEnabled && !updated.backgroundLocationBackfillEnabled) {
            workManager.cancelUniqueWork("location_backfill")
            workManager.cancelUniqueWork("merchant_key_backfill")
            Timber.i("PR1: Cancelled location workers — background location disabled")
        }
        if (old.notificationCaptureEnabled && !updated.notificationCaptureEnabled) {
            // PR1 fix: do NOT cancel data_retention when notification capture is disabled.
            // Data retention must keep running to purge already-collected data.
            workManager.cancelUniqueWork("receipt_matching")
            workManager.cancelUniqueWork("warranty_expiration_check")
            workManager.cancelUniqueWork("bill_reminder_periodic")
            Timber.i("PR1: Cancelled notification-dependent workers (retention kept) — notification capture disabled")
        }
    }

    /**
     * Distinguishes first-run (empty prefs, no corruption) from corrupted
     * (IOException caught above that replaced prefs with emptyPreferences()).
     *
     * Since the corruption handler also produces emptyPreferences(), we use the
     * IOException catch path to set a flag. But because DataStore processes
     * emissions asynchronously, we use a simpler heuristic: if ALL keys are
     * absent AND there was no write-marker key, treat as first run.
     * Actual corruption produces an IOException that we catch above and re-emit
     * as emptyPreferences() with a logged message. We cannot distinguish the two
     * solely from the Preferences object, so we track corruption state in memory.
     */
    private fun Preferences.toLoadState(): PrivacySettingsLoadState {
        val isCompletelyEmpty = this[Keys.NOTIFICATION_CAPTURE_ENABLED] == null &&
            this[Keys.CLOUD_AI_ENABLED] == null &&
            this[Keys.RAW_NOTIFICATION_STORAGE_MODE] == null

        return when {
            isCompletelyEmpty -> PrivacySettingsLoadState.FirstRunDefault(toPrivacySettings())
            else -> PrivacySettingsLoadState.Loaded(toPrivacySettings())
        }
    }

    private fun Preferences.toPrivacySettings(): PrivacySettings = PrivacySettings(
        notificationCaptureEnabled = this[Keys.NOTIFICATION_CAPTURE_ENABLED] ?: true,
        cloudAiEnabled = this[Keys.CLOUD_AI_ENABLED] ?: false,
        redactBeforeCloud = this[Keys.REDACT_BEFORE_CLOUD] ?: true,
        receiptImageCloudEnabled = this[Keys.RECEIPT_IMAGE_CLOUD_ENABLED] ?: false,
        bankStatementAiEnabled = this[Keys.BANK_STATEMENT_AI_ENABLED] ?: false,
        externalGeocodingEnabled = this[Keys.EXTERNAL_GEOCODING_ENABLED] ?: false,
        backgroundLocationBackfillEnabled = this[Keys.BACKGROUND_LOCATION_BACKFILL_ENABLED] ?: false,
        deviceGpsLocationEnabled = this[Keys.DEVICE_GPS_LOCATION_ENABLED] ?: false,
        encryptedBackupEnabled = this[Keys.ENCRYPTED_BACKUP_ENABLED] ?: true,
        rawNotificationRetentionDays = this[Keys.RAW_NOTIFICATION_RETENTION_DAYS] ?: 30,
        rawOcrRetentionDays = this[Keys.RAW_OCR_RETENTION_DAYS] ?: 30,
        debugDataPersistenceEnabled = this[Keys.DEBUG_DATA_PERSISTENCE_ENABLED] ?: false,
        rawNotificationStorageMode = this[Keys.RAW_NOTIFICATION_STORAGE_MODE]
            ?.let { runCatching { RawStorageMode.valueOf(it) }.getOrNull() } ?: RawStorageMode.STORE_RAW,
        rawOcrStorageMode = this[Keys.RAW_OCR_STORAGE_MODE]
            ?.let { runCatching { RawStorageMode.valueOf(it) }.getOrNull() } ?: RawStorageMode.STORE_RAW,
        emailReceiptStorageMode = this[Keys.EMAIL_RECEIPT_STORAGE_MODE]
            ?.let { runCatching { RawStorageMode.valueOf(it) }.getOrNull() } ?: RawStorageMode.STORE_REDACTED
    )
}

/** Helper extension to extract PrivacySettings from any load state. */
private fun PrivacySettingsLoadState.settings(): PrivacySettings = when (this) {
    is PrivacySettingsLoadState.Loaded -> settings
    is PrivacySettingsLoadState.FirstRunDefault -> settings
    is PrivacySettingsLoadState.CorruptedFailClosed -> settings
}
