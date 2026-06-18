package com.yourname.expensetracker.contracts

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * U-PR1 — Contract test: CancellationException propagation.
 *
 * Verifies that key pipeline entry points (suspend functions with broad catches)
 * contain the CE rethrow guard. This is a targeted complement to the architecture
 * guard — it names specific critical methods and asserts their catch blocks are safe.
 */
class CancellationPropagationContractTest {

    private val sourceRoot: File by lazy { resolveSourceRoot() }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root.")
    }

    private val ceEvidence = Regex("""CancellationException""")

    /**
     * Critical entry points that MUST propagate CE. Each entry is:
     * (relative file path from source root, method name or unique identifier in catch context)
     */
    private val criticalEntryPoints = listOf(
        CriticalCatch("com/yourname/expensetracker/service/NotificationCaptureService.kt",
            "captureNotification", "Failed to capture notification via coordinator"),
        CriticalCatch("com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt",
            "dispatchAfterSave", "dispatchAfterSave failed"),
        CriticalCatch("com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt",
            "processStatementImport per-item", "Bank transaction processing failed"),
        CriticalCatch("com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt",
            "unlinkReceiptFromExpense", "Result.failure"),
        CriticalCatch("com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt",
            "reconcileAllLinkedExpensesAfterBulkUpdate", "failed++"),
        CriticalCatch("com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt",
            "computeStressForecast outer", "FCST-17"),
        CriticalCatch("com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt",
            "projectOccurrences per-rule", "projectOccurrences failed"),
        CriticalCatch("com/yourname/expensetracker/data/repository/BudgetRepository.kt",
            "addBudget", "Failed to add budget"),
        CriticalCatch("com/yourname/expensetracker/data/repository/BudgetRepository.kt",
            "updateBudget", "Failed to update budget"),
        CriticalCatch("com/yourname/expensetracker/data/repository/BudgetRepository.kt",
            "deleteBudget", "Failed to delete budget"),
        CriticalCatch("com/yourname/expensetracker/data/repository/BudgetRepository.kt",
            "toggleBudget", "Failed to toggle budget"),
        CriticalCatch("com/yourname/expensetracker/data/repository/BudgetRepository.kt",
            "computeAdjustedSpend", "Failed to compute adjusted spend"),
    )

    private data class CriticalCatch(val filePath: String, val methodLabel: String, val catchMarker: String)

    @Test
    fun `critical pipeline entry points propagate CancellationException`() {
        val violations = mutableListOf<String>()

        for (entry in criticalEntryPoints) {
            val file = File(sourceRoot, entry.filePath)
            if (!file.exists()) {
                violations.add("${entry.filePath} — FILE NOT FOUND")
                continue
            }
            val content = file.readText()

            // Find the catch block containing the marker text
            val markerIndex = content.indexOf(entry.catchMarker)
            if (markerIndex == -1) {
                violations.add("${entry.filePath}:${entry.methodLabel} — catch marker '${entry.catchMarker}' not found")
                continue
            }

            // Walk backwards from the marker to find the enclosing catch block start
            val catchBlockStart = findEnclosingCatchStart(content, markerIndex)
            if (catchBlockStart == -1) {
                // Marker might be inside a try body, not a catch — skip
                continue
            }

            // Extract the catch block body from catchBlockStart to its closing brace
            val catchBody = extractCatchBody(content, catchBlockStart) ?: continue

            if (!ceEvidence.containsMatchIn(catchBody)) {
                val lineNum = content.substring(0, catchBlockStart).count { it == '\n' } + 1
                violations.add("${entry.filePath}:$lineNum (${entry.methodLabel}) — missing CE guard")
            }
        }

        assertTrue(
            "Critical entry points missing CancellationException propagation:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `contract test covers at least 10 critical entry points`() {
        assertTrue(
            "Expected at least 10 critical entry points; have ${criticalEntryPoints.size}",
            criticalEntryPoints.size >= 10
        )
    }

    private fun findEnclosingCatchStart(source: String, position: Int): Int {
        // Search backwards for the nearest `catch` keyword before position
        val searchRegion = source.substring(0, position)
        val lastCatch = searchRegion.lastIndexOf("catch")
        return if (lastCatch >= 0) lastCatch else -1
    }

    private fun extractCatchBody(source: String, catchStart: Int): String? {
        var i = catchStart
        // Find opening brace
        while (i < source.length && source[i] != '{') i++
        if (i >= source.length) return null
        val bodyStart = i + 1
        var depth = 1
        i++
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return source.substring(bodyStart, (i - 1).coerceAtLeast(bodyStart))
    }
}
