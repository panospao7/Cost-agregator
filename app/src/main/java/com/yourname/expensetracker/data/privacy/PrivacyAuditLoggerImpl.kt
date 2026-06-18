package com.yourname.expensetracker.data.privacy

import com.yourname.expensetracker.data.database.dao.PrivacyAuditDao
import com.yourname.expensetracker.data.database.entity.PrivacyAuditEvent
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.util.TimeProvider
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PrivacyAuditLoggerImpl @Inject constructor(
    private val dao: PrivacyAuditDao,
    private val timeProvider: TimeProvider
) : PrivacyAuditLogger {

    private val allowedContextKeys = setOf(
        "operation", "caller", "entityType", "entityId",
        "provider", "modelId", "payloadHash", "receiptId",
        // P8F-03: cloud-call provenance fields (non-sensitive booleans/ids/enums)
        "purpose", "redactionApplied", "rawTextIncluded", "rawImageIncluded", "correlationId"
    )

    override suspend fun logDecision(
        capability: PrivacyCapability,
        decision: PrivacyDecision,
        context: Map<String, String>
    ) {
        val now = timeProvider.now()
        // P8-PR2 (P8-P1-03): Semantic audit — include a meaningful capability
        // description alongside the raw name so audit trails are human-readable.
        val decisionStr = when (decision) {
            is PrivacyDecision.Allowed -> "ALLOWED"
            is PrivacyDecision.Denied -> "DENIED"
            is PrivacyDecision.NotApplicable -> "NOT_APPLICABLE"
            is PrivacyDecision.FailClosed -> "DENIED_FAIL_CLOSED"
        }
        val reason = when (decision) {
            is PrivacyDecision.Denied -> decision.reason
            is PrivacyDecision.FailClosed -> decision.reason
            else -> null
        }
        // P8-PR2 (P8-P1-03): Add semantic context — the specific capability
        // being checked is already captured in the capability column; enrich the
        // context JSON with a human-readable description of the semantic meaning.
        val sanitizedContext = sanitizeContext(context).toMutableMap().apply {
            put("capabilityDescription", capabilityDescription(capability))
        }
        dao.insert(
            PrivacyAuditEvent(
                capability = capability.name,
                decision = decisionStr,
                reason = reason,
                context = JSONObject(sanitizedContext).toString(),
                timestampMs = now,
                caller = "privacy-gate"
            )
        )
    }

    /**
     * P8-PR2 (P8-P1-03): Returns a human-readable description of [capability]
     * for inclusion in audit-log context. This makes audit trails semantic
     * instead of requiring readers to look up raw enum names.
     */
    private fun capabilityDescription(capability: PrivacyCapability): String = when (capability) {
        PrivacyCapability.NOTIFICATION_CAPTURE -> "Capture incoming notification content"
        PrivacyCapability.NOTIFICATION_PACKAGE_ALLOWLIST -> "Filter notifications by package allowlist"
        PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST -> "Cloud AI receipt data extraction assist"
        PrivacyCapability.CLOUD_AI_RECEIPT_OCR -> "Cloud AI OCR receipt text processing"
        PrivacyCapability.CLOUD_AI_ITEM_CATEGORIZATION -> "Cloud AI item category suggestion"
        PrivacyCapability.CLOUD_AI_WARRANTY_EXTRACTION -> "Cloud AI warranty information extraction"
        PrivacyCapability.CLOUD_AI_BANK_STATEMENT -> "Cloud AI bank statement parsing"
        PrivacyCapability.AI_BANK_STATEMENT_PARSING -> "Local AI bank statement parsing"
        PrivacyCapability.CLOUD_AI_DAILY_BRIEFING -> "Cloud AI daily spending briefing"
        PrivacyCapability.CLOUD_AI_GENERAL -> "General cloud AI capability"
        PrivacyCapability.RECEIPT_IMAGE_CLOUD_UPLOAD -> "Upload receipt image to cloud AI"
        PrivacyCapability.EXTERNAL_GEOCODING -> "External geocoding service (address lookup)"
        PrivacyCapability.BACKGROUND_LOCATION_BACKFILL -> "Background location history backfill"
        PrivacyCapability.DEVICE_GPS_LOCATION -> "Device GPS location access"
        PrivacyCapability.RAWBACKUP_EXPORT -> "Raw (unredacted) backup export"
        PrivacyCapability.ENCRYPTED_BACKUP -> "Encrypted backup export"
        PrivacyCapability.RAW_NOTIFICATION_RETENTION -> "Raw notification text retention period"
        PrivacyCapability.RAW_OCR_RETENTION -> "Raw OCR text retention period"
        PrivacyCapability.DEBUG_DATA_PERSISTENCE -> "Debug data persistence (PII logging)"
        PrivacyCapability.OVERPASS_API -> "Overpass API (OpenStreetMap data query)"
        PrivacyCapability.TIMBER_PII_LOGGING -> "Timber log output containing PII"
        PrivacyCapability.EXPENSE_EXPORT -> "Standard expense export (CSV/JSON)"
        PrivacyCapability.EXPENSE_EXPORT_RAW -> "Raw (unredacted) expense export"
        PrivacyCapability.EXPENSE_EXPORT_REDACTED -> "Redacted expense export"
        PrivacyCapability.EXPENSE_EXPORT_ENCRYPTED -> "Encrypted expense export"
        PrivacyCapability.DEBUG_RAW_EXPORT -> "Debug raw export (release-disabled)"
        PrivacyCapability.RAW_DATABASE_EXPORT -> "Raw database export (release-disabled)"
    }

    private fun sanitizeContext(context: Map<String, String>): Map<String, String> {
        val sanitized = mutableMapOf<String, String>()
        for ((key, value) in context) {
            if (key in allowedContextKeys && value.length <= 200) {
                sanitized[key] = value
            }
        }
        return sanitized
    }
}
