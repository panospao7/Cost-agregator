package com.yourname.expensetracker.contracts

import com.yourname.expensetracker.architecture.FailClosedScan
import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFailsWith

/**
 * U-PR1 — Contract test: CancellationException propagation.
 *
 * Every entry names a production METHOD (declared exactly once in its file) and is checked on
 * comment/string-sanitized code only:
 *  - the method must still contain its anchor token (so a refactor cannot leave the contract
 *    pointing at an unrelated or vacuous method);
 *  - each checked broad catch (Exception / Throwable / RuntimeException / IllegalStateException,
 *    all of which can swallow a CancellationException) must start with a cancellation guard on the
 *    CAUGHT variable: `CancellationSafe.rethrowIfCancellation(e)`, `if (e is CancellationException)
 *    throw e`, `if (e is CancellationException) { ...; throw e }`, or be immediately preceded by a
 *    `catch (x: CancellationException) { throw x }` clause (the runCatchingCancellable shape).
 * A mention of "CancellationException" elsewhere in the body (comment, string, later statement,
 * another variable) is NOT evidence.
 *
 * Entries with [CriticalCatch.marker] == null require EVERY broad catch of the method to be guarded
 * (and at least [CriticalCatch.minBroadCatches] of them to exist). Entries with a marker check exactly
 * the one broad catch whose code contains the marker; they exist only where the method deliberately
 * contains another broad catch that must not rethrow (cancellation cleanup).
 */
class CancellationPropagationContractTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sourceRoot: File by lazy {
        FailClosedScan.requireRoot(
            listOf(
                File("src/main/java"),
                File("app/src/main/java"),
                File(System.getProperty("user.dir") ?: ".", "src/main/java"),
                File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
            ),
            "production source root"
        )
    }

    private data class CriticalCatch(
        val filePath: String,
        val scopeMethod: String,
        /** Code token that must occur in the method body (non-vacuity / anti-refactor anchor). */
        val anchor: String,
        val minBroadCatches: Int = 1,
        /** Code token selecting the single catch to verify; null = verify every broad catch. */
        val marker: String? = null
    )

    private val criticalEntryPoints = listOf(
        CriticalCatch("com/yourname/expensetracker/service/NotificationCaptureService.kt",
            "captureNotification", anchor = "intakeCoordinator.capture(", minBroadCatches = 5),
        CriticalCatch("com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt",
            "dispatchAfterSave", anchor = "evidenceService.runBestEffortWithEvidence("),
        // processBankStatement also holds a cancellation-cleanup `catch (cleanupError: Throwable)` that
        // must NEVER rethrow, so only the per-item catch (the one mapping SQLException) is selected.
        CriticalCatch("com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt",
            "processBankStatement", anchor = "bankStatementImportItemDao.insert(",
            marker = "java.sql.SQLException"),
        CriticalCatch("com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt",
            "unlinkReceiptFromExpense", anchor = "Result.failure(e)", minBroadCatches = 2),
        CriticalCatch("com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt",
            "reconcileAllLinkedExpensesAfterBulkUpdate", anchor = "reconcileExpenseLinkAfterUpdate("),
        CriticalCatch("com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt",
            "computeStressForecast", anchor = "StressForecastResult("),
        CriticalCatch("com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt",
            "calculateRecurringOutflows", anchor = "projectOccurrences(", minBroadCatches = 2),
        CriticalCatch("com/yourname/expensetracker/data/repository/BudgetRepository.kt",
            "addBudget", anchor = "writeBarrier.checkWritesAllowed("),
        CriticalCatch("com/yourname/expensetracker/data/repository/BudgetRepository.kt",
            "updateBudget", anchor = "writeBarrier.checkWritesAllowed("),
        CriticalCatch("com/yourname/expensetracker/data/repository/BudgetRepository.kt",
            "deleteBudget", anchor = "writeBarrier.checkWritesAllowed("),
        CriticalCatch("com/yourname/expensetracker/data/repository/BudgetRepository.kt",
            "toggleBudget", anchor = "writeBarrier.checkWritesAllowed("),
        CriticalCatch("com/yourname/expensetracker/data/repository/BudgetRepository.kt",
            "computeAdjustedSpend", anchor = "offsetEngine.calculateEffectiveBudgetSpend("),
        // U-001 (RP-01): create/update currency-conversion boundaries must rethrow CE.
        // createExpenseMutation: RuntimeException barrier catch + provenance event catch + conversion catch.
        CriticalCatch("com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt",
            "createExpenseMutation", anchor = "currencyConverter.convertAsOf(", minBroadCatches = 3),
        CriticalCatch("com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt",
            "updateExpense", anchor = "currencyConverter.convertAsOf("),
    )

    @Test
    fun `critical pipeline entry points propagate CancellationException`() {
        val violations = mutableListOf<String>()
        for (entry in criticalEntryPoints) {
            // Fail closed: a moved/renamed file is an AssertionError naming the path, never a skip.
            val file = FailClosedScan.requireScannedFile(sourceRoot, entry.filePath)
            val source = file.readText()
            CancellationScan.check(
                source, entry.scopeMethod, entry.anchor, entry.marker, entry.minBroadCatches
            ).mapTo(violations) { "${entry.filePath}: $it" }
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

    // ---- self-tests: guard recognition -------------------------------------------------------

    private fun catchOf(variable: String, body: String) = BroadCatch(variable, body, 0, false)

    @Test
    fun `leading guard forms on the caught variable are accepted`() {
        listOf(
            "CancellationSafe.rethrowIfCancellation(e); failed++",
            "CancellationSafe.rethrowIfCancellation(e)\n failed++",
            "if (e is kotlinx.coroutines.CancellationException) throw e; failed++",
            "if (e is CancellationException) { throw e }; failed++",
            "if (e is CancellationException) {\n emitCancelled()\n throw e\n}\n failed++"
        ).forEach { assertTrue(it, CancellationScan.hasLeadingCancellationGuard(catchOf("e", it))) }
        assertTrue(CancellationScan.hasLeadingCancellationGuard(BroadCatch("e", "failed++", 0, true)))
    }

    @Test
    fun `non leading or wrong variable or non throwing guards are rejected`() {
        listOf(
            "failed++; CancellationSafe.rethrowIfCancellation(e)",
            "CancellationSafe.rethrowIfCancellation(other); failed++",
            "if (false) CancellationSafe.rethrowIfCancellation(e); failed++",
            "if (e is CancellationException) throw other; failed++",
            "if (e is CancellationException) { log(e) }; failed++",
            "if (e is CancellationException) { if (flag) { throw e } }; failed++",
            "if (e is CancellationException && flag) throw e; failed++",
            "val x = e is CancellationException; failed++",
            "failed++"
        ).forEach { assertFalse(it, CancellationScan.hasLeadingCancellationGuard(catchOf("e", it))) }
    }

    // ---- self-tests: check() fail-closed branches --------------------------------------------

    private val guardedTarget = """
        fun earlier() { try { work() } catch (e: Exception) { failed++ } }
        suspend fun target() {
            try { anchorCall() } catch (failure: Exception) {
                CancellationSafe.rethrowIfCancellation(failure)
                failed++
            }
        }
        fun later() { try { work() } catch (e: Exception) { failed++ } }
    """.trimIndent()

    private fun violations(
        source: String,
        method: String = "target",
        anchor: String = "anchorCall(",
        marker: String? = null,
        min: Int = 1
    ) = CancellationScan.check(source, method, anchor, marker, min)

    @Test
    fun `check passes a guarded method and ignores unguarded catches in other methods`() {
        assertEquals(emptyList<String>(), violations(guardedTarget))
        // The unguarded catch in `earlier` is reported only when `earlier` is the scoped method.
        assertTrue(violations(guardedTarget, method = "earlier", anchor = "work(").single().contains("unguarded"))
    }

    @Test
    fun `check fails when the scoped method is missing`() {
        val result = violations(guardedTarget, method = "missing")
        assertTrue(result.toString(), result.single().contains("'missing' is declared 0 times"))
    }

    @Test
    fun `check fails when the scoped method is ambiguous`() {
        val result = violations(guardedTarget + "\n" + guardedTarget)
        assertTrue(result.toString(), result.single().contains("'target' is declared 2 times"))
    }

    @Test
    fun `check fails when the method has no broad catch at all (no enclosing catch)`() {
        val result = violations("fun target() { anchorCall(); failed++ }")
        assertTrue(result.toString(), result.single().contains("has 0 broad catch"))
    }

    @Test
    fun `check fails when a CancellationException mention is not a guard`() {
        // Only a comment, a string and a later mention of CancellationException: raw-body search
        // (the old behaviour) would have accepted this; sanitized leading-guard matching must not.
        val source = """
            suspend fun target() {
                try { anchorCall() } catch (e: Exception) {
                    // CancellationException is rethrown elsewhere
                    val note = "CancellationException"
                    failed++
                    if (e is CancellationException) throw e
                }
            }
        """.trimIndent()
        val result = violations(source)
        assertTrue(result.toString(), result.single().contains("unguarded"))
    }

    @Test
    fun `check fails when the method braces cannot be extracted`() {
        val source = "suspend fun target() { try { anchorCall() } catch (e: Exception) { failed++ "
        val result = violations(source)
        assertTrue(result.toString(), result.single().contains("could not extract"))
    }

    @Test
    fun `check fails on an expression bodied method`() {
        val result = violations("fun target() = try { anchorCall() } catch (e: Exception) { null }")
        assertTrue(result.toString(), result.single().contains("could not extract"))
    }

    @Test
    fun `check fails when the anchor token is gone from the method`() {
        val result = violations(guardedTarget, anchor = "renamedAnchor(")
        assertTrue(result.toString(), result.single().contains("anchor 'renamedAnchor('"))
    }

    @Test
    fun `check fails when fewer broad catches exist than the contract expects`() {
        val result = violations(guardedTarget, min = 2)
        assertTrue(result.toString(), result.single().contains("has 1 broad catch"))
    }

    @Test
    fun `check with a marker needs exactly one matching catch`() {
        val two = """
            suspend fun target() {
                try { anchorCall() } catch (a: Exception) { CancellationSafe.rethrowIfCancellation(a); first(); log() }
                try { anchorCall() } catch (b: Exception) { second(); log() }
            }
        """.trimIndent()
        assertEquals(emptyList<String>(), violations(two, marker = "first("))
        assertTrue(violations(two, marker = "second(").single().contains("unguarded"))
        assertTrue(violations(two, marker = "nowhere(").single().contains("matched 0 broad catch"))
        assertTrue(violations(two, marker = "log(").single().contains("matched 2 broad catch"))
    }

    @Test
    fun `check treats RuntimeException and a preceding CancellationException clause correctly`() {
        val unguardedRuntime = "suspend fun target() { try { anchorCall() } catch (b: RuntimeException) { log(b) } }"
        assertTrue(violations(unguardedRuntime).single().contains("unguarded"))
        val clause = "suspend fun target() { try { anchorCall() } " +
            "catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Exception) { log(e) } }"
        assertEquals(emptyList<String>(), violations(clause))
        val narrowOnly = "suspend fun target() { try { anchorCall() } catch (b: java.io.IOException) { log(b) } }"
        assertTrue(violations(narrowOnly).single().contains("has 0 broad catch"))
    }

    @Test
    fun `missing contract file fails with an AssertionError naming the path`() {
        val error = assertFailsWith<AssertionError> {
            FailClosedScan.requireScannedFile(temp.root, "com/yourname/expensetracker/service/Gone.kt")
        }
        assertTrue(error.message, error.message.orEmpty().contains("Gone.kt"))
    }
}

/** One broad catch of a scanned method: [body] is sanitized code (comments/strings blanked). */
internal data class BroadCatch(
    val variable: String,
    val body: String,
    val offset: Int,
    val precededByCancellationClause: Boolean
)

internal class ScanFailure(message: String) : RuntimeException(message)

/** Pure, in-memory cancellation-propagation scanner shared by the contract test and its self-tests. */
internal object CancellationScan {

    private val broadCatch = Regex(
        """\bcatch\s*\(\s*(\w+)\s*:\s*(?:\w+\.)*(?:Exception|Throwable|RuntimeException|IllegalStateException)\s*\)\s*\{"""
    )

    /** Violation messages (empty = contract holds). Structural problems are violations, never skips. */
    fun check(source: String, method: String, anchor: String, marker: String?, minBroadCatches: Int): List<String> {
        return try {
            val code = SourceTextSanitizer.stripCommentsAndStringBodies(source)
            val (bodyStart, bodyEnd) = methodBody(code, method)
            val methodCode = code.substring(bodyStart, bodyEnd)
            if (!methodCode.normalizeWhitespace().contains(anchor.normalizeWhitespace())) {
                return listOf("anchor '$anchor' not found in method '$method' (refactored? update the contract)")
            }
            val catches = broadCatches(methodCode, bodyStart)
            val selected = if (marker == null) {
                if (catches.size < minBroadCatches) {
                    return listOf(
                        "method '$method' has ${catches.size} broad catch(es), expected at least " +
                            "$minBroadCatches (catch removed or contract vacuous)"
                    )
                }
                catches
            } else {
                val matching = catches.filter {
                    it.body.normalizeWhitespace().contains(marker.normalizeWhitespace())
                }
                if (matching.size != 1) {
                    return listOf(
                        "marker '$marker' matched ${matching.size} broad catch(es) in method '$method' " +
                            "(must match exactly 1)"
                    )
                }
                matching
            }
            selected.filterNot { hasLeadingCancellationGuard(it) }.map {
                val line = code.substring(0, it.offset).count { ch -> ch == '\n' } + 1
                "method '$method' line $line: unguarded broad catch (${it.variable}) - the first statement " +
                    "must rethrow CancellationException for the caught variable"
            }
        } catch (failure: ScanFailure) {
            listOf(failure.message.orEmpty())
        }
    }

    /** Offsets (in [code]) of the inside of the block body of the single declaration of [method]. */
    private fun methodBody(code: String, method: String): Pair<Int, Int> {
        val declarations = Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?${Regex.escape(method)}\s*\(""")
            .findAll(code).toList()
        if (declarations.size != 1) {
            throw ScanFailure("method '$method' is declared ${declarations.size} times (must be exactly 1)")
        }
        val open = declarations.single().range.last
        val closeParen = FailClosedScan.matchingParen(code, open)
            ?: throw ScanFailure("could not extract parameter list of method '$method'")
        var index = closeParen + 1
        while (index < code.length && code[index] != '{' && code[index] != '=') index++
        if (index >= code.length || code[index] != '{') {
            throw ScanFailure("could not extract block body of method '$method' (expression-bodied or unbalanced)")
        }
        val close = closingBrace(code, index)
            ?: throw ScanFailure("could not extract block body of method '$method' (unbalanced braces)")
        return (index + 1) to close
    }

    private fun broadCatches(methodCode: String, baseOffset: Int): List<BroadCatch> =
        broadCatch.findAll(methodCode).map { match ->
            val open = match.range.last
            val close = closingBrace(methodCode, open)
                ?: throw ScanFailure("could not extract catch body at offset ${baseOffset + open}")
            val before = methodCode.substring(0, match.range.first).trimEnd()
            val precededByClause = Regex(
                """catch\s*\(\s*(\w+)\s*:\s*(?:\w+\.)*CancellationException\s*\)\s*\{\s*throw\s+\1\s*;?\s*\}$"""
            ).containsMatchIn(before)
            BroadCatch(match.groupValues[1], methodCode.substring(open + 1, close), baseOffset + match.range.first, precededByClause)
        }.toList()

    fun hasLeadingCancellationGuard(caught: BroadCatch): Boolean {
        if (caught.precededByCancellationClause) return true
        val code = caught.body.trimStart()
        val variable = Regex.escape(caught.variable)
        val helper = Regex("^CancellationSafe\\s*\\.\\s*rethrowIfCancellation\\s*\\(\\s*" + variable + "\\s*\\)(?=\\s|;|$)")
        if (helper.containsMatchIn(code)) return true
        val head = Regex("^if\\s*\\(\\s*" + variable + "\\s+is\\s+(?:\\w+\\.)*CancellationException\\s*\\)\\s*")
            .find(code) ?: return false
        val rest = code.substring(head.range.last + 1)
        if (rest.startsWith("{")) {
            val close = closingBrace(rest, 0) ?: return false
            val block = rest.substring(1, close).trim()
            // The guard block must END by rethrowing the caught variable (cleanup/diagnostics may precede).
            return Regex("(^|[\\s;{}])throw\\s+" + variable + "\\s*;?$").containsMatchIn(block)
        }
        return Regex("^throw\\s+" + variable + "\\b").containsMatchIn(rest)
    }

    private fun String.normalizeWhitespace(): String = replace(Regex("""\s+"""), " ").trim()

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
}
