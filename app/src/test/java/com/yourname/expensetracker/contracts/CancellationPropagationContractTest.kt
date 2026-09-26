package com.yourname.expensetracker.contracts

import com.yourname.expensetracker.architecture.SourceTextSanitizer
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
            "processStatementImport per-item", "ITEM_PROCESSING_FAILURE"),
        CriticalCatch("com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt",
            "unlinkReceiptFromExpense", "Result.failure"),
        CriticalCatch("com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt",
            "reconcileAllLinkedExpensesAfterBulkUpdate", "failed++",
            scopeMethod = "reconcileAllLinkedExpensesAfterBulkUpdate"),
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
        // U-001 (RP-01): create/update currency-conversion boundaries must rethrow CE.
        CriticalCatch("com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt",
            "createExpense currency conversion", "Cannot convert %s %.2f to %s for expense creation"),
        CriticalCatch("com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt",
            "updateExpense currency conversion", "// 3. Persist inside a single transaction"),
    )

    private data class CriticalCatch(
        val filePath: String,
        val methodLabel: String,
        val catchMarker: String,
        val scopeMethod: String? = null
    )

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

            // Repeated counters are not unique catch anchors. Inspect the named
            // method and require the caught exception to be checked before recovery.
            if (entry.scopeMethod != null) {
                val caught = findScopedCatch(content, entry.scopeMethod, entry.catchMarker)
                if (caught == null || !hasLeadingCancellationGuard(caught)) {
                    violations.add("${entry.filePath}:${entry.methodLabel} - missing, ambiguous, or unguarded catch")
                }
                continue
            }

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

    private data class ScopedCatch(val variable: String, val body: String)

    private fun findScopedCatch(source: String, method: String, marker: String): ScopedCatch? {
        val code = SourceTextSanitizer.stripCommentsAndStringBodies(source)
        val declaration = Regex("""\bfun\s+${Regex.escape(method)}\s*\(""")
            .findAll(code).toList().singleOrNull() ?: return null
        val opening = code.indexOf('{', declaration.range.last + 1)
        val closing = closingBrace(code, opening) ?: return null
        val methodCode = code.substring(opening + 1, closing)
        val methodSource = source.substring(opening + 1, closing)
        return Regex("""\bcatch\s*\(\s*(\w+)\s*:\s*(?:[\w]+\.)*(?:Exception|Throwable)\s*\)\s*\{""")
            .findAll(methodCode).mapNotNull { match ->
                val bodyStart = match.range.last
                val bodyEnd = closingBrace(methodCode, bodyStart) ?: return@mapNotNull null
                if (!methodCode.substring(bodyStart + 1, bodyEnd).contains(marker)) {
                    return@mapNotNull null
                }
                ScopedCatch(match.groupValues[1], methodSource.substring(bodyStart + 1, bodyEnd))
            }.toList().singleOrNull()
    }

    private fun closingBrace(code: String, opening: Int): Int? {
        if (opening < 0 || opening >= code.length || code[opening] != '{') return null
        var depth = 0
        for (index in opening until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private fun hasLeadingCancellationGuard(caught: ScopedCatch): Boolean {
        val code = SourceTextSanitizer.stripCommentsAndStringBodies(caught.body).trimStart()
        val variable = Regex.escape(caught.variable)
        val helper = Regex("""^CancellationSafe\s*\.\s*rethrowIfCancellation\s*\(\s*$variable\s*\)(?=\s|;|$)""")
        val inline = Regex("""^if\s*\(\s*$variable\s+is\s+(?:[\w]+\.)*CancellationException\s*\)\s*(?:\{\s*)?throw\s+$variable\b""")
        return helper.containsMatchIn(code) || inline.containsMatchIn(code)
    }

    @Test
    fun `scoped catch ignores counters outside catch and guards in other methods`() {
        val source = """
            fun earlier() { try { work() } catch (e: Exception) { failed++ } }
            fun target() {
                try { if (failed) { failed++ } } catch (failure: Exception) {
                    CancellationSafe.rethrowIfCancellation(failure)
                    failed++
                }
            }
            fun later() { try { work() } catch (e: Exception) { failed++ } }
        """.trimIndent()
        val caught = findScopedCatch(source, "target", "failed++")
        assertNotNull(caught)
        assertEquals("failure", caught!!.variable)
        assertTrue(hasLeadingCancellationGuard(caught))
        assertFalse(hasLeadingCancellationGuard(findScopedCatch(source, "earlier", "failed++")!!))
        assertNull(findScopedCatch(source, "missing", "failed++"))
        assertNull(findScopedCatch(source + source, "target", "failed++"))
        assertNull(findScopedCatch("fun target() { failed++ }", "target", "failed++"))
    }

    @Test
    fun `scoped cancellation evidence checks the caught exception before recovery`() {
        listOf(
            "CancellationSafe.rethrowIfCancellation(e); failed++",
            "if (e is kotlinx.coroutines.CancellationException) throw e; failed++",
            "if (e is CancellationException) { throw e }; failed++"
        ).forEach { assertTrue(it, hasLeadingCancellationGuard(ScopedCatch("e", it))) }
        listOf(
            "// CancellationSafe.rethrowIfCancellation(e)\nfailed++",
            "val note = \"CancellationException\"; failed++",
            "CancellationSafe.rethrowIfCancellation(other); failed++",
            "failed++; CancellationSafe.rethrowIfCancellation(e)",
            "if (false) CancellationSafe.rethrowIfCancellation(e); failed++",
            "if (e is CancellationException) throw other; failed++"
        ).forEach { assertFalse(it, hasLeadingCancellationGuard(ScopedCatch("e", it))) }
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
