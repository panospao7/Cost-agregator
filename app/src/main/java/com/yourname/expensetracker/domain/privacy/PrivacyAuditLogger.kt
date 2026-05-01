package com.yourname.expensetracker.domain.privacy

interface PrivacyAuditLogger {
    suspend fun logDecision(capability: PrivacyCapability, decision: PrivacyDecision, context: Map<String, String> = emptyMap())
}
