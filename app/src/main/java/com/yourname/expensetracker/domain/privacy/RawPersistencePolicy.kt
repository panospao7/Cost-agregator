package com.yourname.expensetracker.domain.privacy

/**
 * Describes what may be persisted for a given [RawSourceType] under the
 * current [PrivacySettings].
 *
 * Callers MUST use [RawPersistencePolicyResolver] to obtain an instance;
 * do not construct manually in production code.
 */
data class RawPersistencePolicy(
    val mode: RawStorageMode,
    val sourceType: RawSourceType,
    /** Parsed amount, date, and currency are safe to keep even in METADATA_ONLY / DO_NOT_STORE. */
    val allowParsedAmountDateCurrency: Boolean,
    /** Parsed merchant name is safe to keep (not raw text). */
    val allowParsedMerchant: Boolean,
    /** Parsed item lines — only allowed for STORE_RAW or STORE_REDACTED. */
    val allowParsedItems: Boolean,
    /** Keyed HMAC hash of the external identifier for deduplication. */
    val allowExternalIdHash: Boolean,
    /** Debug body text — only allowed for STORE_RAW + debug persistence enabled. */
    val allowDebugBody: Boolean
) {
    val allowRawBody: Boolean get() = mode == RawStorageMode.STORE_RAW
    val allowRedactedBody: Boolean get() = mode == RawStorageMode.STORE_RAW || mode == RawStorageMode.STORE_REDACTED
}
