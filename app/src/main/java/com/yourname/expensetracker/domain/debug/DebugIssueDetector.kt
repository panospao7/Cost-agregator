package com.yourname.expensetracker.domain.debug

import com.yourname.expensetracker.domain.parser.ParsedTransaction
import javax.inject.Inject
import javax.inject.Singleton

/** Detects and categorizes issues in debug data. */
@Singleton
class DebugIssueDetector @Inject constructor() {

    fun detectIssues(
        rawText: String,
        transactions: List<ParsedTransaction>,
        processingTimeMs: Long
    ): List<DebugIssue> {
        val issues = mutableListOf<DebugIssue>()

        if (transactions.isEmpty()) {
            issues.add(
                DebugIssue(
                    severity = IssueSeverity.CRITICAL,
                    category = "PARSING_FAILURE",
                    message = "No transactions parsed from document",
                    suggestion = "Check document format and try again"
                )
            )
        }

        transactions.forEachIndexed { index, tx ->
            if (tx.merchant.isBlank()) {
                issues.add(
                    DebugIssue(
                        severity = IssueSeverity.CRITICAL,
                        category = "MISSING_FIELD",
                        message = "Transaction #${index + 1}: Missing merchant name",
                        transactionIndex = index,
                        suggestion = "Verify OCR quality and retry"
                    )
                )
            }

            if (tx.amount <= 0.0) {
                issues.add(
                    DebugIssue(
                        severity = IssueSeverity.CRITICAL,
                        category = "INVALID_AMOUNT",
                        message = "Transaction #${index + 1}: Invalid amount ${tx.amount}",
                        transactionIndex = index,
                        suggestion = "Check number format in document"
                    )
                )
            }

            if (tx.confidence < 0.70f) {
                issues.add(
                    DebugIssue(
                        severity = IssueSeverity.WARNING,
                        category = "LOW_CONFIDENCE",
                        message = "Transaction #${index + 1}: Low confidence ${(tx.confidence * 100).toInt()}%",
                        transactionIndex = index,
                        suggestion = "Verify transaction manually"
                    )
                )
            }

            if (tx.date == null) {
                issues.add(
                    DebugIssue(
                        severity = IssueSeverity.WARNING,
                        category = "MISSING_DATE",
                        message = "Transaction #${index + 1}: Missing date",
                        transactionIndex = index,
                        suggestion = "Date will default to today"
                    )
                )
            }

            if (tx.amount > 10000.0) {
                issues.add(
                    DebugIssue(
                        severity = IssueSeverity.WARNING,
                        category = "UNUSUAL_AMOUNT",
                        message = "Transaction #${index + 1}: Unusual amount ${tx.amount}",
                        transactionIndex = index,
                        suggestion = "Verify decimal format in document"
                    )
                )
            }
        }

        val lineCount = rawText.lines().size
        val charCount = rawText.length

        if (lineCount < 5) {
            issues.add(
                DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "OCR_QUALITY",
                    message = "OCR output is short: $lineCount lines",
                    suggestion = "Document may not be fully scanned"
                )
            )
        }

        if (charCount < 100) {
            issues.add(
                DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "OCR_QUALITY",
                    message = "Little text extracted: $charCount chars",
                    suggestion = "Rescan with better lighting"
                )
            )
        }

        val specialCharCount = rawText.count { it == '\uFFFD' || it == '?' }
        if (specialCharCount > charCount * 0.05) {
            issues.add(
                DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "OCR_QUALITY",
                    message = "Unrecognized characters detected",
                    suggestion = "Poor OCR quality, try better image"
                )
            )
        }

        if (processingTimeMs > 5000) {
            issues.add(
                DebugIssue(
                    severity = IssueSeverity.INFO,
                    category = "PERFORMANCE",
                    message = "Processing took ${processingTimeMs / 1000.0}s",
                    suggestion = "Use PDF format for better performance"
                )
            )
        } else {
            issues.add(
                DebugIssue(
                    severity = IssueSeverity.INFO,
                    category = "PERFORMANCE",
                    message = "Processing took ${processingTimeMs}ms"
                )
            )
        }

        val successCount = transactions.count { it.confidence >= 0.70f }
        if (successCount > 0) {
            issues.add(
                DebugIssue(
                    severity = IssueSeverity.INFO,
                    category = "SUMMARY",
                    message = "Successfully parsed $successCount of ${transactions.size} transactions"
                )
            )
        }

        return issues
    }

    fun getIssueCounts(issues: List<DebugIssue>): Map<IssueSeverity, Int> =
        issues.groupingBy { it.severity }.eachCount()
}
