package com.yourname.expensetracker.domain.privacy

data class PrivacySettings(
    val notificationCaptureEnabled: Boolean = true,  // Default ON — core app feature, user can disable in Privacy Settings
    val cloudAiEnabled: Boolean = false,
    val redactBeforeCloud: Boolean = true,
    val receiptImageCloudEnabled: Boolean = false,
    val bankStatementAiEnabled: Boolean = false,
    val externalGeocodingEnabled: Boolean = false,
    val backgroundLocationBackfillEnabled: Boolean = false,
    val deviceGpsLocationEnabled: Boolean = false,
    val encryptedBackupEnabled: Boolean = true,
    val rawNotificationRetentionDays: Int = 30,
    val rawOcrRetentionDays: Int = 30,
    val rawNotificationStorageMode: RawStorageMode = RawStorageMode.STORE_RAW,
    val rawOcrStorageMode: RawStorageMode = RawStorageMode.STORE_RAW,
    val emailReceiptStorageMode: RawStorageMode = RawStorageMode.STORE_REDACTED,
    val rawBankStatementStorageMode: RawStorageMode = RawStorageMode.STORE_REDACTED,
    val debugDataPersistenceEnabled: Boolean = false
) {
    companion object {
        /**
         * Fail-closed settings used when DataStore is corrupted.
         * All opt-in features are disabled; all raw storage modes are DO_NOT_STORE.
         */
        val FAIL_CLOSED_DEFAULTS = PrivacySettings(
            notificationCaptureEnabled = false,
            cloudAiEnabled = false,
            redactBeforeCloud = true,
            receiptImageCloudEnabled = false,
            bankStatementAiEnabled = false,
            externalGeocodingEnabled = false,
            backgroundLocationBackfillEnabled = false,
            deviceGpsLocationEnabled = false,
            encryptedBackupEnabled = true,
            rawNotificationRetentionDays = 30,
            rawOcrRetentionDays = 30,
            rawNotificationStorageMode = RawStorageMode.DO_NOT_STORE,
            rawOcrStorageMode = RawStorageMode.DO_NOT_STORE,
            emailReceiptStorageMode = RawStorageMode.DO_NOT_STORE,
            rawBankStatementStorageMode = RawStorageMode.DO_NOT_STORE,
            debugDataPersistenceEnabled = false
        )
    }
}

/**
 * Distinguishes why the current [PrivacySettings] value is in effect,
 * so callers can surface appropriate warnings to the user.
 */
sealed interface PrivacySettingsLoadState {
    /** Settings were successfully read from DataStore. */
    data class Loaded(val settings: PrivacySettings) : PrivacySettingsLoadState

    /** No persisted settings found — first run; defaults applied. */
    data class FirstRunDefault(val settings: PrivacySettings) : PrivacySettingsLoadState

    /** DataStore read failed; fail-closed defaults applied. User should be warned. */
    data class CorruptedFailClosed(
        val settings: PrivacySettings,
        val reason: String
    ) : PrivacySettingsLoadState
}
