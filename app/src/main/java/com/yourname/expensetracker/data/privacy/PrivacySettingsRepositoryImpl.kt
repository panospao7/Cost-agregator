package com.yourname.expensetracker.data.privacy

import android.content.Context
import androidx.annotation.VisibleForTesting
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
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.PrivacyRuntimeWorkerPolicy
import com.yourname.expensetracker.domain.workers.WorkerRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    @ApplicationContext private val context: Context,
    private val timeProvider: TimeProvider
) : PrivacySettingsRepository {
    private val workManager = WorkManager.getInstance(context)

    /**
     * P8-PR1 (NEW-P8-001): Mutex serialising read-modify-write in [updateSettings]
     * so concurrent callers cannot interleave and cause TOCTOU corruption.
     */
    private val settingsMutex = Mutex()

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
        val RAW_BANK_STATEMENT_STORAGE_MODE = stringPreferencesKey("raw_bank_statement_storage_mode")
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
        // P8-PR1 (NEW-P8-001): Serialise under Mutex so concurrent callers cannot
        // interleave read-modify-write cycles and cause TOCTOU corruption.
        settingsMutex.withLock {
            val old = getSettings()
            context.privacySettingsDataStore.edit { prefs ->
                // PRIV-6825-04: Use load-state settings as base so corruption cannot resurrect unsafe defaults.
                // toLoadState().settings() returns FAIL_CLOSED_DEFAULTS on corruption, not normal defaults.
                val current = prefs.toLoadState().settings()
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
                prefs[Keys.RAW_BANK_STATEMENT_STORAGE_MODE] = updated.rawBankStatementStorageMode.name
                // Mark as initialized so future empty-prefs reads are not misclassified as first-run
                prefs[LOAD_STATE_KEY] = LOAD_STATE_NORMAL
            }
            val persisted = getSettings()
            applyPrivacyChange(old, persisted)
        }
    }

    /**
     * Drives worker cancel/reschedule from [PrivacyRuntimeWorkerPolicy] (the single
     * source of truth) instead of hardcoded worker-name strings.
     *
     * P9-P1-11 / PR8:
     *  - Toggles that went `true -> false` cancel their gated workers. The policy
     *    already excludes [PrivacyRuntimeWorkerPolicy.cancelExemptWorkers] (e.g.
     *    `data_retention`) so cleanup keeps running, and never gates
     *    `merchant_key_backfill` on background location (it is local).
     *  - Toggles that went `false -> true` reschedule their gated workers via
     *    [WorkerRegistry], so each [WorkerSpec.enabled] flag is still honoured
     *    (a disabled spec cancels rather than enqueues).
     */
    @VisibleForTesting
    internal fun applyPrivacyChange(old: PrivacySettings, updated: PrivacySettings) {
        val disabled = PrivacyRuntimeWorkerPolicy.disabledToggles(old, updated)
        val enabled = PrivacyRuntimeWorkerPolicy.enabledToggles(old, updated)

        // Defensive: cancelExemptWorkers (e.g. data_retention) is excluded by the
        // policy itself, so it can never appear here even if a toggle maps to it.
        val toCancel = PrivacyRuntimeWorkerPolicy.workersToCancel(disabled)
        for (workerName in toCancel) {
            workManager.cancelUniqueWork(workerName)
            Timber.i("Cancelled %s — gating privacy toggle disabled", workerName)
        }

        val toReschedule = PrivacyRuntimeWorkerPolicy.workersToReschedule(enabled)
        if (toReschedule.isNotEmpty()) {
            val scheduleBySpec = WorkerRegistry.entries.associateBy({ it.specName }, { it.schedule })
            for (workerName in toReschedule) {
                val schedule = scheduleBySpec[workerName] ?: continue
                // Route through WorkerRegistry so a disabled WorkerSpec is respected
                // (scheduleAtMidnight / scheduleFromSpec cancel when spec.enabled=false).
                runCatching { schedule(context, timeProvider) }
                    .onSuccess { Timber.i("Rescheduled %s — gating privacy toggle enabled", workerName) }
                    .onFailure { Timber.w(it, "Failed to reschedule %s", workerName) }
            }
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
            ?.let { runCatching { RawStorageMode.valueOf(it) }.getOrNull() } ?: RawStorageMode.STORE_REDACTED,
        rawBankStatementStorageMode = this[Keys.RAW_BANK_STATEMENT_STORAGE_MODE]
            ?.let { runCatching { RawStorageMode.valueOf(it) }.getOrNull() } ?: RawStorageMode.STORE_REDACTED
    )
}

private fun PrivacySettingsLoadState.settings(): PrivacySettings = when (this) {
    is PrivacySettingsLoadState.Loaded -> settings
    is PrivacySettingsLoadState.FirstRunDefault -> settings
    is PrivacySettingsLoadState.CorruptedFailClosed -> settings
}
