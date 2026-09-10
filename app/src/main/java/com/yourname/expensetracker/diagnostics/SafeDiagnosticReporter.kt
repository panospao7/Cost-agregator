package com.yourname.expensetracker.diagnostics

/**
 * Typed diagnostic contract for safe error reporting.
 * Replaces raw paths, exception messages, OCR text, and Throwables in logs/UI.
 */
data class SafeDiagnostic(
    val reasonCode: String,        // fixed enum-style code e.g. "BACKUP_CREATE_FAILED"
    val stage: String,             // e.g. "EXPORT", "GEOCODE", "BACKUP"
    val severity: Severity,
    val exceptionClass: String? = null,  // category only, NOT message
    val retryable: Boolean = false,
)

enum class Severity { INFO, WARNING, ERROR }

fun safeDiagnostic(reasonCode: String, stage: String, severity: Severity, exception: Exception? = null, retryable: Boolean = false): SafeDiagnostic {
    return SafeDiagnostic(
        reasonCode = reasonCode,
        stage = stage,
        severity = severity,
        exceptionClass = exception?.javaClass?.simpleName,
        retryable = retryable
    )
}
