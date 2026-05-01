package com.yourname.expensetracker.domain.privacy

interface PrivacyGate {
    suspend fun check(capability: PrivacyCapability, context: Map<String, String> = emptyMap()): PrivacyDecision
}
