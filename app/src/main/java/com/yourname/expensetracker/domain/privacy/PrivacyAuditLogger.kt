package com.yourname.expensetracker.domain.privacy

interface PrivacyAuditLogger {
    suspend fun logDecision(capability: PrivacyCapability, decision: PrivacyDecision, context: Map<String, String> = emptyMap())

    /** PR7: Typed audit context overload — preferred for cloud call provenance. */
    suspend fun logDecision(capability: PrivacyCapability, decision: PrivacyDecision, context: PrivacyAuditContext) {
        logDecision(capability, decision, context.toMap())
    }

    /** PR7: Log a cloud AI call with full provenance. */
    suspend fun logCloudCall(
        capability: PrivacyCapability,
        decision: PrivacyDecision,
        context: PrivacyAuditContext
    ) {
        logDecision(capability, decision, context)
    }

    companion object {
        val NO_OP: PrivacyAuditLogger = object : PrivacyAuditLogger {
            override suspend fun logDecision(
                capability: PrivacyCapability,
                decision: PrivacyDecision,
                context: Map<String, String>
            ) { }
        }
    }
}
