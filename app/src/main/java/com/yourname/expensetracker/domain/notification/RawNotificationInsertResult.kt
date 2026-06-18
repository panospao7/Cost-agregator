package com.yourname.expensetracker.domain.notification

sealed interface RawNotificationInsertResult {
    val dedupeFingerprint: String?

    data class Inserted(
        val rawId: Long,
        override val dedupeFingerprint: String?
    ) : RawNotificationInsertResult

    data class Duplicate(
        val existingRawId: Long?,
        override val dedupeFingerprint: String?,
        val basis: DuplicateBasis
    ) : RawNotificationInsertResult
}

enum class DuplicateBasis {
    DEDUPE_FINGERPRINT_PRECHECK,
    DEDUPE_FINGERPRINT_INSERT_CONFLICT,
    LEGACY_RAW_FIELD_PRECHECK
}
