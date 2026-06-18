package com.yourname.expensetracker.domain.ai.service

/**
 * S11-003: Abstraction for testing cloud AI provider connectivity.
 * Injected into AiSettingsViewModel so tests can use a fake without real HTTP.
 */
interface CloudProviderConnectionTester {
    /** Returns null on success, or a user-facing error message on failure. */
    suspend fun testGemini(apiKey: String): String?
}
