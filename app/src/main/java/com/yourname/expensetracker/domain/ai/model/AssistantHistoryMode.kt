package com.yourname.expensetracker.domain.ai.model

/**
 * W34: Controls how assistant conversation history is stored.
 */
enum class AssistantHistorySettings(val retentionDays: Int, val storePayloadJson: Boolean, val storeDiagnostics: Boolean) {
    OFF(retentionDays = 0, storePayloadJson = false, storeDiagnostics = false),
    REDACTED(retentionDays = 30, storePayloadJson = false, storeDiagnostics = false),
    RAW(retentionDays = 90, storePayloadJson = true, storeDiagnostics = true);

    companion object {
        fun fromPrefs(value: String): AssistantHistorySettings =
            entries.firstOrNull { it.name == value } ?: REDACTED
    }
}
