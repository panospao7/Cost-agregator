package com.yourname.expensetracker.domain.privacy

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the [RawPersistencePolicy] for a given [RawSourceType] by consulting
 * [PrivacySettingsRepository].
 *
 * ## Mode matrix
 *
 * | Source         | Setting key                      | Default mode    |
 * |----------------|----------------------------------|-----------------|
 * | NOTIFICATION   | rawNotificationStorageMode       | STORE_RAW       |
 * | RECEIPT_OCR    | rawOcrStorageMode                | STORE_RAW       |
 * | EMAIL_RECEIPT  | emailReceiptStorageMode          | STORE_REDACTED  |
 * | BANK_STATEMENT | rawBankStatementStorageMode      | STORE_REDACTED  |
 * | BANK_API       | rawBankStatementStorageMode      | STORE_REDACTED  |
 * | AI_ARTIFACT    | debugDataPersistenceEnabled      | DO_NOT_STORE    |
 * | EXPORT_DEBUG   | debugDataPersistenceEnabled      | DO_NOT_STORE    |
 */
@Singleton
class RawPersistencePolicyResolver @Inject constructor(
    private val privacySettingsRepository: PrivacySettingsRepository
) {
    suspend fun forSource(sourceType: RawSourceType): RawPersistencePolicy {
        val settings = privacySettingsRepository.getSettings()
        return buildPolicy(sourceType, settings)
    }

    fun forSourceSync(sourceType: RawSourceType, settings: PrivacySettings): RawPersistencePolicy =
        buildPolicy(sourceType, settings)

    private fun buildPolicy(sourceType: RawSourceType, settings: PrivacySettings): RawPersistencePolicy {
        val mode = modeFor(sourceType, settings)
        return RawPersistencePolicy(
            mode = mode,
            sourceType = sourceType,
            allowParsedAmountDateCurrency = mode != RawStorageMode.DO_NOT_STORE ||
                sourceType == RawSourceType.EMAIL_RECEIPT,  // email always keeps parsed amount/date for dedup
            allowParsedMerchant = mode == RawStorageMode.STORE_RAW || mode == RawStorageMode.STORE_REDACTED,
            allowParsedItems = mode == RawStorageMode.STORE_RAW || mode == RawStorageMode.STORE_REDACTED,
            allowExternalIdHash = mode != RawStorageMode.DO_NOT_STORE || sourceType.needsDedupeHash(),
            allowDebugBody = mode == RawStorageMode.STORE_RAW && settings.debugDataPersistenceEnabled
        )
    }

    private fun modeFor(sourceType: RawSourceType, settings: PrivacySettings): RawStorageMode =
        when (sourceType) {
            RawSourceType.NOTIFICATION -> settings.rawNotificationStorageMode
            RawSourceType.RECEIPT_OCR -> settings.rawOcrStorageMode
            RawSourceType.EMAIL_RECEIPT -> settings.emailReceiptStorageMode
            RawSourceType.BANK_STATEMENT -> settings.rawBankStatementStorageMode
            RawSourceType.BANK_API -> settings.rawBankStatementStorageMode
            RawSourceType.AI_ARTIFACT -> when {
                settings.debugDataPersistenceEnabled -> RawStorageMode.STORE_REDACTED
                else -> RawStorageMode.DO_NOT_STORE
            }
            RawSourceType.EXPORT_DEBUG -> when {
                settings.debugDataPersistenceEnabled -> RawStorageMode.STORE_RAW
                else -> RawStorageMode.DO_NOT_STORE
            }
        }}

/** Sources that always need a keyed hash for deduplication even under DO_NOT_STORE. */
private fun RawSourceType.needsDedupeHash(): Boolean = when (this) {
    RawSourceType.NOTIFICATION,
    RawSourceType.EMAIL_RECEIPT,
    RawSourceType.BANK_API,
    RawSourceType.BANK_STATEMENT -> true
    else -> false
}
