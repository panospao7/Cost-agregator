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

    override suspend fun logDecision(
        capability: PrivacyCapability,
        decision: PrivacyDecision,
        context: Map<String, String>
    ) {
        val now = timeProvider.now()
        dao.insert(
            PrivacyAuditEvent(
                capability = capability.name,
                decision = if (decision is PrivacyDecision.Allowed) "ALLOWED" else "DENIED",
                reason = (decision as? PrivacyDecision.Denied)?.reason,
                context = JSONObject(context).toString(),
                timestampMs = now,
                caller = "privacy-gate"
            )
        )
    }
}
