package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FC-PAID-UNLINKED S2 architecture guards (source scans, same style as [RecurringArchitectureGuardTest]).
 *
 * - INV-1 (forecast isolation): no forecast, budget, savings or Radar consumer references the
 *   suggestion API, the decline event or the use case; the API is referenced only by the lifecycle
 *   coordinator, the use case and `ui/`.
 * - INV-3 (single write path): the occurrence claim and the decline event are written only by
 *   [com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator]; ViewModels
 *   and UI never touch the occurrence or lifecycle event DAOs.
 * - INV-10 (read only): the suggestion read performs no write.
 * - INV-16 / INV-11: confirm and decline run under the write barrier; the read under the read barrier.
 *
 * Comments and string bodies are blanked by [SourceTextSanitizer] before matching, and a missing
 * root, an empty scan or a missing marker fails the test (never skips).
 */
class PaymentLinkArchitectureGuardTest {

    private val sourceRoot: File by lazy {
        val userDir = System.getProperty("user.dir") ?: "."
        FailClosedScan.requireRoot(
            listOf(
                File("src/main/java"),
                File("app/src/main/java"),
                File(userDir, "src/main/java"),
                File(userDir, "app/src/main/java")
            ),
            "production source root"
        )
    }

    private companion object {
        const val BASE = "com/yourname/expensetracker/"
        const val COORDINATOR = BASE + "domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt"
        const val USE_CASE = BASE + "domain/recurring/usecase/MarkRecurringBillPaidUseCase.kt"
        const val MATCHER = BASE + "domain/recurring/lifecycle/RecurringPaymentLinkMatcher.kt"
        const val TYPES = BASE + "domain/recurring/lifecycle/PaymentLinkSuggestion.kt"
        const val UI_PREFIX = BASE + "ui/"
        const val HOME_VIEW_MODEL = BASE + "ui/screens/home/HomeViewModel.kt"
        const val MANUAL_RECURRING_VIEW_MODEL = BASE + "ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt"

        /** Coordinator-level API: the coordinator declares it, only the use case calls it. */
        val COORDINATOR_API = Regex("""\b(findPaymentLinkSuggestions|confirmPaymentLink|declinePaymentLink)\b""")

        /** Use-case-level API: the use case declares it, ViewModels call it. */
        val USE_CASE_API = Regex("""\b(linkExistingPayment|declinePayment)\b""")

        /** Value types of the suggestion API. */
        val API_TYPES = Regex(
            """\b(PaymentLinkSuggestion\w*|PaymentLinkConfirmResult|PaymentLinkDeclineResult|""" +
                """PaymentLinkUnavailableReason|PaymentLinkFailureCodes)\b"""
        )

        /** Forecast consumers named by INV-1; every one must exist. */
        val FORECAST_CONSUMERS = listOf(
            BASE + "domain/forecasting/ForecastInputAssembler.kt",
            BASE + "domain/cashflow/CashFlowCalculator.kt",
            BASE + "domain/forecasting/FinancialStressForecastEngine.kt",
            BASE + "domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt",
            BASE + "data/repository/FinancialWeatherRepository.kt",
            BASE + "domain/usecase/savings/MonthlySavingsSweepUseCase.kt",
            BASE + "domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt"
        )

        /** Whole packages of forecast, cash flow, dashboard (incl. Radar) and savings logic. */
        val FORECAST_PACKAGES = listOf(
            BASE + "domain/forecasting/",
            BASE + "domain/cashflow/",
            BASE + "domain/usecase/dashboard/",
            BASE + "domain/usecase/savings/"
        )
    }

    /** Every identifier of the suggestion API a forecast consumer must never reference. */
    private fun forecastForbiddenReferences(code: String): List<String> {
        val found = mutableListOf<String>()
        for (regex in listOf(COORDINATOR_API, USE_CASE_API, API_TYPES)) {
            regex.findAll(code).forEach { found += it.value }
        }
        for (token in listOf(
            "MarkRecurringBillPaidUseCase",
            "LINK_SUGGESTION_DECLINED",
            "RecurringPaymentLinkMatcher",
            "RecurringLifecycleEventDao",
            "lifecycleEventDao"
        )) {
            if (Regex("""\b$token\b""").containsMatchIn(code)) found += token
        }
        return found.distinct()
    }

    private fun code(raw: String) = SourceTextSanitizer.stripCommentsAndStringBodies(raw)

    private fun coordinatorText(): String =
        FailClosedScan.requireScannedFile(sourceRoot, COORDINATOR).readText()

    // -- INV-1 ---------------------------------------------------------------------------------------

    @Test
    fun `INV-1 the forecast budget savings and radar consumers do not reference the suggestion API`() {
        val errors = mutableListOf<String>()
        for (relative in FORECAST_CONSUMERS) {
            val file = FailClosedScan.requireScannedFile(sourceRoot, relative)
            val refs = forecastForbiddenReferences(code(file.readText()))
            if (refs.isNotEmpty()) errors += "${file.name}: $refs"
        }
        var scanned = 0
        FailClosedScan.walkSourceFiles(sourceRoot) { file, raw ->
            val relative = FailClosedScan.relativePath(sourceRoot, file)
            if (FORECAST_PACKAGES.none { relative.startsWith(it) }) return@walkSourceFiles
            scanned++
            val refs = forecastForbiddenReferences(code(raw))
            if (refs.isNotEmpty()) errors += "$relative: $refs"
        }
        assertTrue("the forecast package scan was vacuous", scanned > 0)
        assertTrue(
            "Forecast consumers reference the suggestion API (INV-1):\n${errors.joinToString("\n")}",
            errors.isEmpty()
        )
    }

    @Test
    fun `INV-1 only the coordinator and the use case reference the coordinator-level suggestion API`() {
        val allowed = setOf(COORDINATOR, USE_CASE)
        val errors = mutableListOf<String>()
        val referencing = mutableSetOf<String>()
        FailClosedScan.walkSourceFiles(sourceRoot) { file, raw ->
            val relative = FailClosedScan.relativePath(sourceRoot, file)
            val refs = COORDINATOR_API.findAll(code(raw)).map { it.value }.toSet()
            if (refs.isEmpty()) return@walkSourceFiles
            referencing += relative
            if (relative !in allowed) errors += "$relative: $refs"
        }
        assertTrue(
            "Only the coordinator and the use case may reference findPaymentLinkSuggestions / " +
                "confirmPaymentLink / declinePaymentLink:\n${errors.joinToString("\n")}",
            errors.isEmpty()
        )
        assertEquals("the guard must see both declaring sites", allowed, referencing)
    }

    @Test
    fun `INV-1 only the use case and ui reference the use-case-level suggestion API`() {
        val errors = mutableListOf<String>()
        var seenInUseCase = false
        FailClosedScan.walkSourceFiles(sourceRoot) { file, raw ->
            val relative = FailClosedScan.relativePath(sourceRoot, file)
            val refs = USE_CASE_API.findAll(code(raw)).map { it.value }.toSet()
            if (refs.isEmpty()) return@walkSourceFiles
            if (relative == USE_CASE) {
                seenInUseCase = true
            } else if (!relative.startsWith(UI_PREFIX)) {
                errors += "$relative: $refs"
            }
        }
        assertTrue("the use case must declare linkExistingPayment / declinePayment", seenInUseCase)
        assertTrue(
            "Only the use case and ui/ may reference linkExistingPayment / declinePayment:\n${errors.joinToString("\n")}",
            errors.isEmpty()
        )
    }

    @Test
    fun `INV-1 inside ui only the two view models call the suggestion API and only through the use case`() {
        val findSuggestions = Regex("""\bfindSuggestions\b""")
        val coordinatorType = Regex("""\bRecurringLifecycleCoordinator\b""")
        val useCaseType = Regex("""\bMarkRecurringBillPaidUseCase\b""")
        val readers = mutableSetOf<String>()
        val writers = mutableSetOf<String>()
        val viewModelTexts = mutableMapOf<String, String>()
        FailClosedScan.walkSourceFiles(sourceRoot) { file, raw ->
            val relative = FailClosedScan.relativePath(sourceRoot, file)
            if (!relative.startsWith(UI_PREFIX)) return@walkSourceFiles
            val text = code(raw)
            if (findSuggestions.containsMatchIn(text)) readers += relative
            if (USE_CASE_API.containsMatchIn(text)) writers += relative
            if (relative == HOME_VIEW_MODEL || relative == MANUAL_RECURRING_VIEW_MODEL) viewModelTexts[relative] = text
        }
        assertEquals("ui/ readers of findSuggestions", setOf(HOME_VIEW_MODEL, MANUAL_RECURRING_VIEW_MODEL), readers)
        assertEquals("ui/ callers of linkExistingPayment / declinePayment", setOf(MANUAL_RECURRING_VIEW_MODEL), writers)
        assertEquals(setOf(HOME_VIEW_MODEL, MANUAL_RECURRING_VIEW_MODEL), viewModelTexts.keys)
        for ((relative, text) in viewModelTexts) {
            assertTrue("$relative must reach the suggestion API through MarkRecurringBillPaidUseCase", useCaseType.containsMatchIn(text))
            assertFalse("$relative must never reference the coordinator", coordinatorType.containsMatchIn(text))
            assertFalse(
                "$relative must never reference the coordinator-level API",
                COORDINATOR_API.containsMatchIn(text)
            )
        }
    }

    @Test
    fun `INV-1 the suggestion value types are referenced only by the lifecycle package the use case and ui`() {
        val allowed = setOf(COORDINATOR, TYPES, USE_CASE)
        val errors = mutableListOf<String>()
        FailClosedScan.walkSourceFiles(sourceRoot) { file, raw ->
            val relative = FailClosedScan.relativePath(sourceRoot, file)
            val refs = API_TYPES.findAll(code(raw)).map { it.value }.toSet()
            if (refs.isEmpty()) return@walkSourceFiles
            if (relative !in allowed && !relative.startsWith(UI_PREFIX)) errors += "$relative: $refs"
        }
        assertTrue(
            "Suggestion value types leak outside the coordinator, the use case and ui/:\n${errors.joinToString("\n")}",
            errors.isEmpty()
        )
    }

    // -- INV-3: single write path ------------------------------------------------------------------------

    @Test
    fun `INV-3 ViewModels and ui never touch the occurrence or lifecycle event DAOs or the link writers`() {
        val forbidden = listOf(
            "claimForExpense",
            "linkExpenseToSpecificOccurrence",
            "linkExpenseToOccurrence",
            "linkExpenseToOccurrenceDetailed",
            "RecurringOccurrenceDao",
            "recurringOccurrenceDao",
            "RecurringLifecycleEventDao",
            "recurringLifecycleEventDao",
            "lifecycleEventDao",
            "RecurringPaymentLinkMatcher",
            "LINK_SUGGESTION_DECLINED"
        )
        val errors = mutableListOf<String>()
        var scanned = 0
        FailClosedScan.walkSourceFiles(sourceRoot) { file, raw ->
            val relative = FailClosedScan.relativePath(sourceRoot, file)
            if (!relative.startsWith(UI_PREFIX)) return@walkSourceFiles
            scanned++
            val text = code(raw)
            for (token in forbidden) {
                if (Regex("""\b$token\b""").containsMatchIn(text)) errors += "$relative: $token"
            }
        }
        assertTrue("the ui scan was vacuous", scanned > 0)
        assertTrue(
            "ui/ must reach the link and decline writes only through the use case:\n${errors.joinToString("\n")}",
            errors.isEmpty()
        )
    }

    @Test
    fun `INV-3 the occurrence claim is called from exactly one place - the coordinator`() {
        val call = Regex("""\.\s*claimForExpense\s*\(""")
        val callers = mutableMapOf<String, Int>()
        FailClosedScan.walkSourceFiles(sourceRoot) { file, raw ->
            val count = call.findAll(code(raw)).count()
            if (count > 0) callers[FailClosedScan.relativePath(sourceRoot, file)] = count
        }
        assertEquals("claimForExpense callers: $callers", mapOf(COORDINATOR to 1), callers)
    }

    @Test
    fun `INV-14 the decline event type is named only by the matcher and the coordinator`() {
        val literal = Regex("\"LINK_SUGGESTION_DECLINED\"")
        val identifier = Regex("""\bLINK_SUGGESTION_DECLINED\b""")
        val literalFiles = mutableSetOf<String>()
        val identifierFiles = mutableSetOf<String>()
        FailClosedScan.walkSourceFiles(sourceRoot) { file, raw ->
            val relative = FailClosedScan.relativePath(sourceRoot, file)
            // The literal lives in a string, so match the raw text of the non-comment lines.
            val noLineComments = raw.lineSequence().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .joinToString("\n")
            if (literal.containsMatchIn(noLineComments)) literalFiles += relative
            if (identifier.containsMatchIn(code(raw))) identifierFiles += relative
        }
        assertEquals("the literal is defined once, in the matcher", setOf(MATCHER), literalFiles)
        assertEquals(
            "only the matcher (definition, decline test) and the coordinator (write) use the constant",
            setOf(MATCHER, COORDINATOR),
            identifierFiles
        )
    }

    @Test
    fun `INV-3 confirm links only through the shared claim path with the confirmed-match source`() {
        val section = FailClosedScan.section(
            coordinatorText(), "suspend fun confirmPaymentLink(", "private suspend fun selectConfirmTarget("
        )
        val body = code(section)
        assertTrue("confirm must use claimLinkSuppressInWrite", body.contains("claimLinkSuppressInWrite("))
        assertTrue("confirm must use the user_confirmed_match source", body.contains("SOURCE_USER_CONFIRMED_MATCH"))
        assertTrue("confirm must advance through advanceRuleAfterAutoLink", body.contains("advanceRuleAfterAutoLink("))
        val ensureActiveAt = body.indexOf("ensureActive()")
        val nonCancellableAt = body.indexOf("withContext(NonCancellable)")
        val claimAt = body.indexOf("claimLinkSuppressInWrite(")
        val advanceAt = body.indexOf("advanceRuleAfterAutoLink(")
        assertTrue("confirm must check cancellation before anything is written", ensureActiveAt >= 0)
        assertTrue(
            "the claim AND the advance must both run inside one withContext(NonCancellable): " +
                "ensureActive() < NonCancellable < claim < advance",
            ensureActiveAt < nonCancellableAt && nonCancellableAt in 0 until claimAt && claimAt < advanceAt
        )
        assertEquals("confirm has exactly one NonCancellable block", 1, Regex("""\bNonCancellable\b""").findAll(body).count())
        assertTrue(
            "confirm must rethrow cancellation",
            Regex("""CancellationSafe\s*\.\s*rethrowIfCancellation\s*\(""").containsMatchIn(body)
        )
        for (forbidden in listOf("occurrenceDao.", "lifecycleEventDao.", "eventWriter.", "plannedExpenseDao.", "withTransaction")) {
            assertFalse("confirm must not write on its own ($forbidden)", body.contains(forbidden))
        }
    }

    @Test
    fun `INV-12 the in-transaction revalidation of confirm is read-only and checks the whole pair rule`() {
        val section = FailClosedScan.section(
            coordinatorText(), "private suspend fun selectConfirmTarget(", "suspend fun declinePaymentLink("
        )
        val body = code(section)
        for (required in listOf(
            "RecurringNextDatePolicy.isOpen(",
            "SOURCE_TYPE_RECURRING_RULE",
            "occurrence.sourceId != ruleId",
            "isActive",
            "RecurringPaymentLinkMatcher.pairMatches(",
            "RecurringPaymentLinkMatcher.isDeclined("
        )) {
            assertTrue("selectConfirmTarget must check $required", body.contains(required))
        }
        assertFalse("selectConfirmTarget must not write", mutatingCall.containsMatchIn(body))
    }

    @Test
    fun `INV-14 decline runs under the write barrier in one transaction through the event writer`() {
        val section = FailClosedScan.section(
            coordinatorText(), "suspend fun declinePaymentLink(", "suspend fun findEarliestOpenOccurrence("
        )
        val body = code(section)
        assertTrue("decline must run under writeBarrier.runWrite", body.contains("writeBarrier.runWrite("))
        assertTrue("decline must run in database.withTransaction", body.contains("database.withTransaction"))
        assertTrue(
            "decline must check the rule ownership inside the transaction",
            body.indexOf("database.withTransaction") in 0 until body.indexOf("occurrence.sourceId != ruleId")
        )
        assertTrue(
            "decline must write through eventWriter.writeCritical with the decline event type",
            Regex("""eventWriter\s*\.\s*writeCritical\s*\([^)]*eventType\s*=\s*RecurringPaymentLinkMatcher\s*\.\s*LINK_SUGGESTION_DECLINED""")
                .containsMatchIn(body)
        )
        assertTrue(
            "decline must rethrow cancellation",
            Regex("""CancellationSafe\s*\.\s*rethrowIfCancellation\s*\(""").containsMatchIn(body)
        )
        for (forbidden in listOf("lifecycleEventDao.insert(", "claimForExpense", "occurrenceDao.update", "occurrenceDao.claim")) {
            assertFalse("decline must not use $forbidden", body.contains(forbidden))
        }
        assertEquals(
            "decline writes exactly one event per call",
            1,
            Regex("""\beventWriter\s*\.\s*writeCritical\s*\(""").findAll(body).count()
        )
    }

    // -- INV-10 / INV-11 ---------------------------------------------------------------------------------------

    private val mutatingCall = Regex(
        """\.\s*(insert\w*|update\w*|delete\w*|upsert\w*|claim\w*|suppress\w*|writeCritical|set\w*Status\w*|topUpHorizon|advanceNextDate|link\w*|unlink\w*|cancel\w*|reopen\w*|mark\w*|release\w*|recover\w*|regenerate\w*)\s*\("""
    )

    @Test
    fun `INV-10 the suggestion read performs no write`() {
        val section = FailClosedScan.section(
            coordinatorText(), "suspend fun findPaymentLinkSuggestions(", "private class OwnedExpense"
        )
        val body = code(section)
        assertFalse(
            "findPaymentLinkSuggestions must stay read-only: ${mutatingCall.findAll(body).map { it.value }.toList()}",
            mutatingCall.containsMatchIn(body)
        )
        for (forbidden in listOf("writeBarrier", "withTransaction", "eventWriter", "materializer", "NonCancellable")) {
            assertFalse("the suggestion read must not use $forbidden", body.contains(forbidden))
        }
    }

    @Test
    fun `INV-11 the suggestion read is behind the read barrier and rethrows cancellation`() {
        val section = FailClosedScan.section(
            coordinatorText(), "suspend fun findPaymentLinkSuggestions(", "private suspend fun computePaymentLinkSuggestions("
        )
        val body = code(section)
        assertTrue(
            "the read barrier must be checked before any read",
            Regex("""readBarrier\s*\.\s*checkReadAllowed\s*\(""").containsMatchIn(body)
        )
        assertTrue(
            "cancellation must be rethrown, not mapped to Unavailable",
            Regex("""CancellationSafe\s*\.\s*rethrowIfCancellation\s*\(""").containsMatchIn(body)
        )
        assertTrue("READ_BLOCKED must be a distinct reason", body.contains("PaymentLinkUnavailableReason.READ_BLOCKED"))
        assertTrue("READ_FAILED must be a distinct reason", body.contains("PaymentLinkUnavailableReason.READ_FAILED"))
        assertTrue(
            "a barrier block must be told apart by its exception type",
            body.contains("DatabaseAccessBlockedException")
        )
    }

    // -- detector self-tests ---------------------------------------------------------------------------------

    @Test
    fun `the forecast reference detector flags every shape of the suggestion API`() {
        val violating = listOf(
            "val s = coordinator.findPaymentLinkSuggestions(now)",
            "useCase.linkExistingPayment(1L, 2L, 3L)",
            "useCase.declinePayment(1L, 2L, 3L)",
            "private val useCase: MarkRecurringBillPaidUseCase",
            "val r: PaymentLinkSuggestions = x",
            "if (type == LINK_SUGGESTION_DECLINED) return",
            "RecurringPaymentLinkMatcher.pairMatches(a, b, c, d, w)",
            "private val lifecycleEventDao: RecurringLifecycleEventDao"
        )
        for (snippet in violating) {
            assertTrue("detector must flag: $snippet", forecastForbiddenReferences(code(snippet)).isNotEmpty())
        }
    }

    @Test
    fun `the forecast reference detector ignores comments and strings and unrelated names`() {
        val benign = listOf(
            "// coordinator.findPaymentLinkSuggestions(now)",
            "/* PaymentLinkSuggestion */ val x = 1",
            "val s = \"linkExistingPayment\"",
            "fun computeCommittedObligations() {}",
            "val declinedPaymentsCount = 3"
        )
        for (snippet in benign) {
            assertTrue("detector must not flag: $snippet", forecastForbiddenReferences(code(snippet)).isEmpty())
        }
    }

    @Test
    fun `the mutation detector flags write-style verbs and ignores the read-only matcher helpers`() {
        for (snippet in listOf(
            "occurrenceDao.markPaid(1L)", "dao.cancelOccurrence(1L)", "occurrenceDao.linkExpense(1, 2)",
            "dao.unlinkExpense(1)", "dao.reopenOccurrence(1)", "dao.releaseClaim(1)",
            "coordinator.recoverOccurrence(1)", "coordinator.regenerateOccurrences(1)"
        )) {
            assertTrue("detector must flag: $snippet", mutatingCall.containsMatchIn(code(snippet)))
        }
        for (snippet in listOf(
            "RecurringPaymentLinkMatcher.isDeclined(events, 1L)",
            "RecurringPaymentLinkMatcher.pairMatches(a, b, c, d, w)",
            "val w = linkWindowsFor(rule.frequency, occurrence)",
            "occurrenceDao.getByDateRange(a, b)"
        )) {
            assertFalse("detector must not flag: $snippet", mutatingCall.containsMatchIn(code(snippet)))
        }
    }

    @Test
    fun `the mutation detector flags writes and ignores reads`() {
        for (snippet in listOf(
            "occurrenceDao.update(x)", "eventWriter.writeCritical(1L, t)", "dao.insert(r)",
            "occurrenceDao.claimForExpense(1, 2, 3.0, c, 4)", "ruleCoordinator.advanceNextDate(1, 2)"
        )) {
            assertTrue("detector must flag: $snippet", mutatingCall.containsMatchIn(code(snippet)))
        }
        for (snippet in listOf(
            "occurrenceDao.getById(1)", "expenseDao.getExpensesBetween(a, b)", "list.filter { it.id > 0 }",
            "lifecycleEventDao.getEventsForOccurrence(1)"
        )) {
            assertFalse("detector must not flag: $snippet", mutatingCall.containsMatchIn(code(snippet)))
        }
    }
}
