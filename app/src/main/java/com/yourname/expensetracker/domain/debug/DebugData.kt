package com.yourname.expensetracker.domain.debug

import com.yourname.expensetracker.domain.parser.ParsedTransaction
import com.yourname.expensetracker.domain.util.DateFormatterUtils

/**
 * Data class to hold parser debug information for display/export.
 */
data class DebugData(
    val rawText: String,
    val parsedTransactions: List<ParsedTransaction>,
    val parsingLogs: List<String> = emptyList(),
    val processingTimeMs: Long = 0,
    val parserUsed: String = "Unknown",
    val issues: List<DebugIssue> = emptyList(),
    /**
     * Maps transaction index to its validation source.
     * Values: "PARSER_ONLY", "AI_VALIDATED", "AI_CORRECTED".
     * An empty map means all transactions are PARSER_ONLY (legacy).
     */
    val validationSources: Map<Int, String> = emptyMap()
) {
    fun toJson(timestamp: Long): String {
        val issueCounts = issues.groupingBy { it.severity }.eachCount()

        return buildString {
            appendLine("{")
            appendLine("  \"metadata\": {")
            appendLine("    \"timestamp\": \"${DateFormatterUtils.javaTimeIsoTimestamp(timestamp)}\",")
            appendLine("    \"processingTimeMs\": $processingTimeMs,")
            appendLine("    \"parserUsed\": \"$parserUsed\"")
            appendLine("  },")
            appendLine("  \"rawText\": {")
            appendLine("    \"lineCount\": ${rawText.lines().size},")
            appendLine("    \"characterCount\": ${rawText.length},")
            appendLine("    \"preview\": \"${rawText.take(200).replace("\"", "\\\"").replace("\n", "\\n")}...\"")
            appendLine("  },")
            appendLine("  \"transactions\": [")
            parsedTransactions.forEachIndexed { index, tx ->
                appendLine("    {")
                appendLine("      \"index\": $index,")
                appendLine("      \"merchant\": \"${tx.merchant.replace("\"", "\\\"")}\",")
                appendLine("      \"amount\": ${tx.amount},")
                appendLine("      \"currency\": \"${tx.currency}\",")
                appendLine("      \"confidence\": ${tx.confidence},")
                appendLine("      \"type\": \"${tx.type.name}\",")
                appendLine("      \"date\": ${tx.date ?: "null"},")
                appendLine("      \"validationSource\": \"${validationSources[index] ?: "PARSER_ONLY"}\",")
                val txIssues = issues.filter { it.transactionIndex == index }
                appendLine("      \"issues\": [${txIssues.joinToString { "\"${it.category}\"" }}]")
                append("    }")
                if (index < parsedTransactions.size - 1) appendLine(",") else appendLine()
            }
            appendLine("  ],")
            appendLine("  \"issues\": {")
            appendLine("    \"critical\": ${issueCounts[IssueSeverity.CRITICAL] ?: 0},")
            appendLine("    \"warnings\": ${issueCounts[IssueSeverity.WARNING] ?: 0},")
            appendLine("    \"info\": ${issueCounts[IssueSeverity.INFO] ?: 0},")
            appendLine("    \"details\": [")
            issues.forEachIndexed { index, issue ->
                appendLine("      {")
                appendLine("        \"severity\": \"${issue.severity.name}\",")
                appendLine("        \"category\": \"${issue.category}\",")
                appendLine("        \"message\": \"${issue.message.replace("\"", "\\\"")}\",")
                appendLine("        \"transactionIndex\": ${issue.transactionIndex ?: "null"},")
                appendLine("        \"suggestion\": ${if (issue.suggestion != null) "\"${issue.suggestion.replace("\"", "\\\"")}\"" else "null"}")
                append("      }")
                if (index < issues.size - 1) appendLine(",") else appendLine()
            }
            appendLine("    ]")
            appendLine("  },")
            appendLine("  \"parsingLogs\": [")
            parsingLogs.forEachIndexed { index, log ->
                append("    \"${log.replace("\"", "\\\"")}\"")
                if (index < parsingLogs.size - 1) appendLine(",") else appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }
    }
}
