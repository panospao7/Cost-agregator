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
        val sanitizedContext = sanitizeContext(context)
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
