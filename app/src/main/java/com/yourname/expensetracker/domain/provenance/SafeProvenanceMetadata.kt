package com.yourname.expensetracker.domain.provenance

import org.json.JSONObject

/**
 * CURR-SL-01: Privacy-safe metadata for source links.
 *
 * Fail-closed: rejects any known sensitive keys.
 * Only allows a curated allowlist of safe summary fields.
 */
class SafeProvenanceMetadata private constructor(
    private val values: Map<String, Any?>
) {
    fun toJson(): String {
        val obj = JSONObject()
        for ((key, value) in values) {
            obj.put(key, value)
        }
        return obj.toString()
    }

    fun isEmpty(): Boolean = values.isEmpty()

    companion object {
        private val BLOCKED_KEYS = setOf(
            "rawText", "rawBody", "emailBody", "emailSubjectRaw", "emailSenderRaw",
            "bankDescription", "bankReference", "accessToken", "refreshToken",
            "prompt", "fullPath", "iban", "accountNumber", "cardNumber",
            "messageId", "providerTransactionId", "bankAccountId",
            "notificationKey", "orderId", "rawNotificationBody",
            "rawNotificationTitle", "emailSubject", "emailSender"
        )

        private val ALLOWED_KEYS = setOf(
            "parserId", "parserVersion", "providerId", "confidence",
            "importFormat", "importSchemaVersion", "statementPageNumber",
            "transactionStatus", "bookingDate", "valueDate",
            "receiptLinkType", "dedupeReason", "matchedExpenseId",
            "receiptLinkId", "linkType", "importRowCount",
            "originalSource", "migrationVersion",
            // PR3: Review provenance metadata keys
            "stage", "reason", "extractionState", "routingDecision",
            // PR3: Promotion tracking keys
            "promotedFromTargetType", "promotedFromPendingReviewId",
            "promotedFromSourceLinkId", "promotedFromRole",
            // PR5: Notification provenance metadata keys
            "appName", "packageNameHash", "notificationHash",
            "matchedNotificationId", "matchType",
            // PR5: Receipt/email provenance metadata keys
            "provider", "messageIdHash", "contentFingerprintHash",
            // PR6: Bank sync provenance metadata keys
            "providerTransactionHash", "accountHash"
        )

        fun fromMap(raw: Map<String, Any?>): SafeProvenanceMetadata {
            val filtered = mutableMapOf<String, Any?>()
            for ((key, value) in raw) {
                if (key in BLOCKED_KEYS) {
                    throw IllegalArgumentException(
                        "Blocked sensitive key in provenance metadata: $key"
                    )
                }
                if (key in ALLOWED_KEYS || key.startsWith("custom_")) {
                    filtered[key] = value
                }
                // Silently drop unknown keys (fail-closed for safety)
            }
            return SafeProvenanceMetadata(filtered)
        }

        fun empty(): SafeProvenanceMetadata = SafeProvenanceMetadata(emptyMap())
    }
}
