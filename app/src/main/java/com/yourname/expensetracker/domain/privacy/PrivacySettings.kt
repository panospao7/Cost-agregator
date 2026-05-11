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
    val debugDataPersistenceEnabled: Boolean = false
)
