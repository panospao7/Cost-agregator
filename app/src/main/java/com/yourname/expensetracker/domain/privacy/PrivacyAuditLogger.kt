package com.yourname.expensetracker.domain.privacy

interface PrivacyAuditLogger {
    suspend fun logDecision(capability: PrivacyCapability, decision: PrivacyDecision, context: Map<String, String> = emptyMap())

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
