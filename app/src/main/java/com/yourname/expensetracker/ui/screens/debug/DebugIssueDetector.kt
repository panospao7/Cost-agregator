package com.yourname.expensetracker.ui.screens.debug

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
                message = "No transactions were parsed from the document",
                suggestion = "Check if the document format is supported or try re-scanning with better quality"
            ))
        }
        
        // Check each transaction
        transactions.forEachIndexed { index, tx ->
            // Critical: Missing required fields
            if (tx.merchant.isBlank()) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.CRITICAL,
                    category = "MISSING_FIELD",
                    message = "Transaction #${index + 1}: Missing merchant name",
                    transactionIndex = index,
                    suggestion = "Verify OCR quality or manually enter merchant name"
                ))
            }
            
            if (tx.amount <= 0.0) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.CRITICAL,
                    category = "INVALID_AMOUNT",
                    message = "Transaction #${index + 1}: Invalid amount (${tx.amount})",
                    transactionIndex = index,
                    suggestion = "Check number format in source document"
                ))
            }
            
            // Warning: Low confidence
            if (tx.confidence < 0.70f) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "LOW_CONFIDENCE",
                    message = "Transaction #${index + 1}: Low confidence (${(tx.confidence * 100).toInt()}%)",
                    transactionIndex = index,
                    suggestion = "Manually verify merchant name and amount"
                ))
            }
            
            // Warning: Missing date
            if (tx.date == null) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "MISSING_DATE",
                    message = "Transaction #${index + 1}: Missing transaction date",
                    transactionIndex = index,
                    suggestion = "Date will default to current time"
                ))
            }
            
            // Warning: Unusual amount (too large or suspiciously round)
            if (tx.amount > 10000.0) {
                issues.add(DebugIssue(
                    severity = IssueSeverity.WARNING,
                    category = "UNUSUAL_AMOUNT",
                    message = "Transaction #${index + 1}: Unusually large amount (€${tx.amount})",
                    transactionIndex = index,
                    suggestion = "Verify this is not a decimal separator error"
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
                message = "Very short OCR output ($lineCount lines)",
                suggestion = "Document may not have been fully scanned"
            ))
        }
        
        if (charCount < 100) {
            issues.add(DebugIssue(
                severity = IssueSeverity.WARNING,
                category = "OCR_QUALITY",
                message = "Very little text extracted ($charCount characters)",
                suggestion = "Try re-scanning with better lighting or higher resolution"
            ))
        }
        
        // Check for special characters indicating OCR errors
        val specialCharCount = rawText.count { it == '�' || it == '?' }
        if (specialCharCount > charCount * 0.05) {  // More than 5% special chars
            issues.add(DebugIssue(
                severity = IssueSeverity.WARNING,
                category = "OCR_QUALITY",
                message = "High number of unrecognized characters detected",
                suggestion = "OCR quality may be poor, consider re-scanning"
            ))
        }
        
        // Info: Processing performance
        if (processingTimeMs > 5000) {
            issues.add(DebugIssue(
                severity = IssueSeverity.INFO,
                category = "PERFORMANCE",
                message = "Processing took ${processingTimeMs / 1000.0}s",
                suggestion = "Consider using PDF format for faster processing"
            ))
        } else {
            issues.add(DebugIssue(
                severity = IssueSeverity.INFO,
                category = "PERFORMANCE",
                message = "Processing completed in ${processingTimeMs}ms"
            ))
        }
        
        // Info: Success summary
        val successCount = transactions.count { it.confidence >= 0.70f }
        if (successCount > 0) {
            issues.add(DebugIssue(
                severity = IssueSeverity.INFO,
                category = "SUMMARY",
                message = "Successfully parsed $successCount/${transactions.size} transactions with good confidence"
            ))
        }
        
        return issues
    }
    
    fun getIssueCounts(issues: List<DebugIssue>): Map<IssueSeverity, Int> {
        return issues.groupingBy { it.severity }.eachCount()
    }
}
