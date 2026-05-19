package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
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

private val LOAD_STATE_KEY = stringPreferencesKey("_privacy_load_state")
private const val LOAD_STATE_NORMAL = "NORMAL"
private const val LOAD_STATE_CORRUPTED = "CORRUPTED"

/**
 * Corruption handler writes a CORRUPTED sentinel so [toLoadState] can
 * distinguish corruption from a genuine first run.
 * Both produce empty-looking prefs, but only corruption has the sentinel.
 */
private val Context.privacySettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "privacy_settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        mutablePreferencesOf(LOAD_STATE_KEY to LOAD_STATE_CORRUPTED)
    }
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
    }

    override fun observeSettings(): Flow<PrivacySettings> =
        observeLoadState().map { it.settings() }

    override fun observeLoadState(): Flow<PrivacySettingsLoadState> =
        context.privacySettingsDataStore.data
            .catch { error ->
                when (error) {
                    is IOException -> {
                        Timber.e(error, "Privacy settings DataStore read failed — using fail-closed defaults")
                        emit(mutablePreferencesOf(LOAD_STATE_KEY to LOAD_STATE_CORRUPTED))
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
            Timber.e(e, "Privacy settings read failed (getLoadState) — using fail-closed defaults")
            PrivacySettingsLoadState.CorruptedFailClosed(
                settings = PrivacySettings.FAIL_CLOSED_DEFAULTS,
                reason = e.message ?: "IO error"
            )
        }

    override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {
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
            // Mark as initialized so future empty-prefs reads are not misclassified as first-run
            prefs[LOAD_STATE_KEY] = LOAD_STATE_NORMAL
        }
        val persisted = getSettings()
        applyPrivacyChange(old, persisted)
    }

    private fun applyPrivacyChange(old: PrivacySettings, updated: PrivacySettings) {
        if (old.cloudAiEnabled && !updated.cloudAiEnabled) {
            workManager.cancelUniqueWork("ai_daily_briefing")
            Timber.i("Cancelled ai_daily_briefing — cloud AI disabled")
        }
        if (old.backgroundLocationBackfillEnabled && !updated.backgroundLocationBackfillEnabled) {
            workManager.cancelUniqueWork("location_backfill")
            workManager.cancelUniqueWork("merchant_key_backfill")
            Timber.i("Cancelled location workers — background location disabled")
        }
        if (old.notificationCaptureEnabled && !updated.notificationCaptureEnabled) {
            // Do NOT cancel data_retention — it must keep running to purge already-collected data
            workManager.cancelUniqueWork("receipt_matching")
            workManager.cancelUniqueWork("warranty_expiration_check")
            workManager.cancelUniqueWork("bill_reminder_periodic")
            Timber.i("Cancelled notification-dependent workers (retention kept) — notification capture disabled")
        }
    }

    /**
     * Load-state precedence:
     * 1. CORRUPTED sentinel -> CorruptedFailClosed (fail-closed defaults)
     * 2. No sentinel + all keys absent -> FirstRunDefault (normal defaults)
     * 3. NORMAL sentinel or any real key present -> Loaded
     */
    private fun Preferences.toLoadState(): PrivacySettingsLoadState {
        val marker = this[LOAD_STATE_KEY]
        return when {
            marker == LOAD_STATE_CORRUPTED -> {
                Timber.w("Privacy settings: CORRUPTED sentinel detected — using fail-closed defaults")
                PrivacySettingsLoadState.CorruptedFailClosed(
                    settings = PrivacySettings.FAIL_CLOSED_DEFAULTS,
                    reason = "DataStore corruption detected"
                )
            }
            marker == null && this[Keys.NOTIFICATION_CAPTURE_ENABLED] == null &&
                this[Keys.CLOUD_AI_ENABLED] == null &&
                this[Keys.RAW_NOTIFICATION_STORAGE_MODE] == null -> {
                PrivacySettingsLoadState.FirstRunDefault(toPrivacySettings())
            }
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

private fun PrivacySettingsLoadState.settings(): PrivacySettings = when (this) {
    is PrivacySettingsLoadState.Loaded -> settings
    is PrivacySettingsLoadState.FirstRunDefault -> settings
    is PrivacySettingsLoadState.CorruptedFailClosed -> settings
}
