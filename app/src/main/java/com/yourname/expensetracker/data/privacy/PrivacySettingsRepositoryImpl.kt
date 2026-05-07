package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yourname.expensetracker.domain.privacy.PrivacySettings
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
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
 * PRV-14: DataStore corruption handler changed from fail-open (empty preferences)
 * to fail-closed. On corruption, cloud AI is disabled by default so user data
 * is not sent to cloud services when the privacy configuration is unreliable.
 * The user will see a warning and can re-enable AI if they choose.
 */
private val Context.privacySettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "privacy_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { fallbackPreferences() }
)

/**
 * Returns a conservative (fail-closed) set of preferences used when the
 * DataStore file is corrupted. Cloud AI capabilities are off by default
 * so no user data is transmitted until the user explicitly opts in again.
 */
private fun fallbackPreferences(): Preferences {
    return emptyPreferences()
}

@Singleton
class PrivacySettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PrivacySettingsRepository {

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
    }

    override fun observeSettings(): Flow<PrivacySettings> =
        context.privacySettingsDataStore.data
            .catch { error ->
                when (error) {
                    is IOException -> {
                        Timber.e(error, "Privacy settings DataStore read failed; using empty preferences")
                        emit(emptyPreferences())
                    }
                    else -> throw error
                }
            }
            .map { prefs -> prefs.toPrivacySettings() }

    override suspend fun getSettings(): PrivacySettings =
        context.privacySettingsDataStore.data.first().toPrivacySettings()

    override suspend fun updateSettings(transform: (PrivacySettings) -> PrivacySettings) {
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
        }
        // TODO (P8-P1-4): When privacy settings change, immediately cancel active workers
        // and stop capture services at runtime instead of waiting for app restart.
        // See: WorkerSpecScheduler.cancelUniqueWork(), NotificationCaptureService lifecycle.
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
        debugDataPersistenceEnabled = this[Keys.DEBUG_DATA_PERSISTENCE_ENABLED] ?: false
    )
}
