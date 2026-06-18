package com.yourname.expensetracker.domain.provenance

import com.yourname.expensetracker.domain.transaction.ExpenseSource

/**
 * PR5: Builds source-link payloads for notification provenance.
 *
 * Safe fields only: notificationId, appName, packageNameHash, notificationHash,
 * matchedExpenseId, matchType, confidence.
 *
 * Never include: raw notification title/body, raw action text, raw package names.
 */
object NotificationSourceLinkPayloadFactory {

    /**
     * Creates a payload linking a RAW_NOTIFICATION to an EXPENSE.
     */
    fun forNotificationToExpense(
        notificationId: Long,
        appName: String,
        packageNameHash: String? = null,
        notificationHash: String? = null,
        confidence: Double? = null
    ): SourceLinkPayload {
        val metadataMap = mutableMapOf<String, Any?>()
        metadataMap["appName"] = appName
        packageNameHash?.let { metadataMap["packageNameHash"] = it }
        notificationHash?.let { metadataMap["notificationHash"] = it }
        confidence?.let { metadataMap["confidence"] = it }
        return SourceLinkPayload(
            sourceType = ExpenseSource.SMS_NOTIFICATION.name,
            sourceEntityType = SourceEntityType.RAW_NOTIFICATION,
            sourceEntityLocalId = notificationId,
            role = SourceLinkRole.CREATED_FROM,
            status = SourceLinkStatus.ACTIVE,
            isPrimary = true,
            confidence = confidence?.toFloat(),
            metadata = SafeProvenanceMetadata.fromMap(metadataMap)
        )
    }

    /**
     * Creates a payload linking a RAW_NOTIFICATION to a PENDING_REVIEW.
     */
    fun forNotificationToReview(
        notificationId: Long,
        appName: String,
        packageNameHash: String? = null,
        notificationHash: String? = null,
        confidence: Double? = null
    ): SourceLinkPayload {
        val metadataMap = mutableMapOf<String, Any?>()
        metadataMap["appName"] = appName
        packageNameHash?.let { metadataMap["packageNameHash"] = it }
        notificationHash?.let { metadataMap["notificationHash"] = it }
        confidence?.let { metadataMap["confidence"] = it }
        return SourceLinkPayload(
            sourceType = ExpenseSource.SMS_NOTIFICATION.name,
            sourceEntityType = SourceEntityType.RAW_NOTIFICATION,
            sourceEntityLocalId = notificationId,
            role = SourceLinkRole.REVIEWED_FROM,
            status = SourceLinkStatus.ACTIVE,
            isPrimary = true,
            confidence = confidence?.toFloat(),
            metadata = SafeProvenanceMetadata.fromMap(metadataMap)
        )
    }

    /**
     * Creates a payload for a deduplication match between two notifications.
     */
    fun forDedupeMatch(
        sourceNotificationId: Long,
        matchedNotificationId: Long,
        matchType: String,
        confidence: Double? = null
    ): SourceLinkPayload {
        val metadataMap = mutableMapOf<String, Any?>()
        metadataMap["matchedNotificationId"] = matchedNotificationId
        metadataMap["matchType"] = matchType
        confidence?.let { metadataMap["confidence"] = it }
        return SourceLinkPayload(
            sourceType = ExpenseSource.SMS_NOTIFICATION.name,
            sourceEntityType = SourceEntityType.RAW_NOTIFICATION,
            sourceEntityLocalId = sourceNotificationId,
            role = SourceLinkRole.DUPLICATE_MATCHED,
            status = SourceLinkStatus.DUPLICATE,
            isPrimary = false,
            confidence = confidence?.toFloat(),
            metadata = SafeProvenanceMetadata.fromMap(metadataMap)
        )
    }
}
