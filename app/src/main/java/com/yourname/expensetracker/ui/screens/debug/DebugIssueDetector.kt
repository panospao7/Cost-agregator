package com.yourname.expensetracker.ui.screens.debug

import android.content.Context
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.parser.ParsedTransaction

/**
 * Severity levels for debug issues
 */
enum class IssueSeverity {
    CRITICAL,  // Missing required data, parsing failures
    WARNING,   // Low confidence, unusual patterns
    INFO       // Performance metrics, suggestions
}

/**
 * Represents a detected issue in the parsing/processing
 */
data class DebugIssue(
    val severity: IssueSeverity,
    val category: String,  // e.g., "MISSING_FIELD", "LOW_CONFIDENCE", "OCR_QUALITY"
    val message: String,
    val transactionIndex: Int? = null,  // null for global issues
    val suggestion: String? = null
)

/**
 * Detects and categorizes issues in debug data
 */
object DebugIssueDetector {
    
    fun detectIssues(
        context: Context,
        rawText: String,
        transactions: List<ParsedTransaction>,
        processingTimeMs: Long
    ): List<DebugIssue> {
        val issues = mutableListOf<DebugIssue>()
        
        // Critical: No transactions parsed
        if (transactions.isEmpty()) {
            issues.add(DebugIssue(
                severity = IssueSeverity.CRITICAL,
                category = "PARSING_FAILURE",
                message = context.getString(R.string.debug_issue_no_transactions),
                suggestion = context.getString(R.string.debug_suggestion_check_document_format)
            ))
        }
        
        // Check each transaction
        transactions.forEachIndexed { index, tx ->
            // Critical: Missing required fields
            if (tx.merchant.isBlank()) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.CRITICAL,
                    category = "MISSING_FIELD",
                    message = context.getString(R.string.debug_issue_missing_merchant_format, index + 1),
                    transactionIndex = index,
                    suggestion = context.getString(R.string.debug_suggestion_verify_ocr)
                ))
            }
            
            if (tx.amount <= 0.0) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.CRITICAL,
                    category = "INVALID_AMOUNT",
                    message = context.getString(R.string.debug_issue_invalid_amount_format, index + 1, tx.amount),
                    transactionIndex = index,
                    suggestion = context.getString(R.string.debug_suggestion_check_number_format)
                ))
            }
            
            // Warning: Low confidence
            if (tx.confidence < 0.70f) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "LOW_CONFIDENCE",
                    message = context.getString(R.string.debug_issue_low_confidence_format, index + 1, (tx.confidence * 100).toInt()),
                    transactionIndex = index,
                    suggestion = context.getString(R.string.debug_suggestion_verify_manually)
                ))
            }
            
            // Warning: Missing date
            if (tx.date == null) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "MISSING_DATE",
                    message = context.getString(R.string.debug_issue_missing_date_format, index + 1),
                    transactionIndex = index,
                    suggestion = context.getString(R.string.debug_suggestion_date_will_default)
                ))
            }
            
            // Warning: Unusual amount (too large or suspiciously round)
            if (tx.amount > 10000.0) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "UNUSUAL_AMOUNT",
                    message = context.getString(R.string.debug_issue_unusual_amount_format, index + 1, tx.amount),
                    transactionIndex = index,
                    suggestion = context.getString(R.string.debug_suggestion_verify_decimal_format)
                ))
            }
        }
        
        // OCR Quality checks
        val lineCount = rawText.lines().size
        val charCount = rawText.length
        
        if (lineCount < 5) {
            issues.add(DebugIssue(
                severity = IssueSeverity.WARNING,
                category = "OCR_QUALITY",
                message = context.getString(R.string.debug_ocr_short_output_format, lineCount),
                suggestion = context.getString(R.string.debug_ocr_document_not_fully_scanned)
            ))
        }
        
        if (charCount < 100) {
            issues.add(DebugIssue(
                severity = IssueSeverity.WARNING,
                category = "OCR_QUALITY",
                message = context.getString(R.string.debug_ocr_little_text_format, charCount),
                suggestion = context.getString(R.string.debug_ocr_rescan_better_format)
            ))
        }
        
        // Check for special characters indicating OCR errors
        val specialCharCount = rawText.count { it == '\uFFFD' || it == '?' }
        if (specialCharCount > charCount * 0.05) {  // More than 5% special chars
            issues.add(DebugIssue(
                severity = IssueSeverity.WARNING,
                category = "OCR_QUALITY",
                message = context.getString(R.string.debug_ocr_unrecognized_chars),
                suggestion = context.getString(R.string.debug_ocr_poor_quality)
            ))
        }
        
        // Info: Processing performance
        if (processingTimeMs > 5000) {
            issues.add(DebugIssue(
                severity = IssueSeverity.INFO,
                category = "PERFORMANCE",
                message = context.getString(R.string.debug_performance_slow_format, processingTimeMs / 1000.0),
                suggestion = context.getString(R.string.debug_suggestion_use_pdf)
            ))
        } else {
            issues.add(DebugIssue(
                severity = IssueSeverity.INFO,
                category = "PERFORMANCE",
                message = context.getString(R.string.debug_performance_fast_format, processingTimeMs)
            ))
        }
        
        // Info: Success summary
        val successCount = transactions.count { it.confidence >= 0.70f }
        if (successCount > 0) {
            issues.add(DebugIssue(
                severity = IssueSeverity.INFO,
                category = "SUMMARY",
                message = context.getString(R.string.debug_summary_parsed_format, successCount, transactions.size)
            ))
        }
        
        return issues
    }
    
    fun getIssueCounts(issues: List<DebugIssue>): Map<IssueSeverity, Int> {
        return issues.groupingBy { it.severity }.eachCount()
    }
}
