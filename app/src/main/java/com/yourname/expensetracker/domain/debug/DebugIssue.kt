package com.yourname.expensetracker.domain.debug

/** Severity levels for parser/debug issues. */
enum class IssueSeverity {
    CRITICAL,
    WARNING,
    INFO
}

/** Represents a detected issue in parsing/processing diagnostics. */
data class DebugIssue(
    val severity: IssueSeverity,
    val category: String,
    val message: String,
    val transactionIndex: Int? = null,
    val suggestion: String? = null
)
