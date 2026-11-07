package com.yourname.expensetracker.architecture

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * refund-netting S2b source-scan guard (structural, last resort for private mappers).
 *
 * 1. Every data class in app/src/main whose primary constructor declares
 *    `val spendingExclusionReason` must also declare `val refundNettedAmount`, so a future DTO
 *    cannot silently drop the field. Intentional exclusions are listed explicitly below and the
 *    list must stay exact (a stale entry fails the test).
 * 2. Every constructor call of the known money DTOs that passes `spendingExclusionReason` must
 *    also pass `refundNettedAmount` (covers the private mappers in AnomalyAlertOrchestrator,
 *    RecurringExpenseEngine and AnalyticsViewModel, which expose no observable output).
 * 3. Each known copy site still contains the expected number of such calls (so deleting a mapper
 *    or the argument is detected, and the scan cannot pass vacuously).
 */
class RefundNettedAmountDtoParityGuardTest {

    /** DTO name -> why it intentionally has spendingExclusionReason but no refundNettedAmount. */
    private val intentionalExclusions: Map<String, String> = mapOf(
        "SpendingExclusionFlagRow" to "Flag-only projection (id + reason); carries no amounts.",
        "InstallmentExpenseFacts" to "Installment lifecycle eligibility facts; no money fields netted by refunds.",
        "PurchaseDeclarationFacts" to "Schedule-link eligibility facts; no money fields netted by refunds.",
        "RescueExpense" to "OPEN FOLLOW-UP: financial-rescue copy of an expense row; refundNettedAmount is " +
            "derived from expense_refund_links and is outside S2b scope. Remove this entry when added."
    )

    private val moneyDtoTypes = listOf(
        "ExpenseSnapshot",
        "NormalizedExpense",
        "DashboardExpense",
        "NormalizableAnalyticsExpense",
        "ExportTransaction"
    )

    /** relative path (under expensetracker/) -> DTO type -> minimum qualifying constructor calls. */
    private val knownCopySites: Map<String, Map<String, Int>> = mapOf(
        "data/repository/ExpenseRepository.kt" to mapOf("ExpenseSnapshot" to 1),
        "data/repository/DashboardContractsAdapter.kt" to mapOf("DashboardExpense" to 1),
        "domain/forecasting/ForecastInputAssembler.kt" to mapOf("ExpenseSnapshot" to 1),
        "domain/alerts/AnomalyAlertOrchestrator.kt" to mapOf("ExpenseSnapshot" to 1),
        "domain/logic/RecurringExpenseEngine.kt" to mapOf("ExpenseSnapshot" to 1),
        "ui/screens/analytics/AnalyticsViewModel.kt" to mapOf("ExpenseSnapshot" to 1),
        "domain/analytics/AnalyticsInputAssembler.kt" to mapOf("NormalizedExpense" to 1),
        "domain/analytics/SingleCurrencyAnalyticsInput.kt" to mapOf("NormalizedExpense" to 1),
        "domain/core/money/MoneyNormalizationEngine.kt" to mapOf("NormalizedExpense" to 1),
        "domain/analytics/AnalyticsCurrencyNormalizer.kt" to
            mapOf("NormalizableAnalyticsExpense" to 2, "ExpenseSnapshot" to 1),
        "domain/analytics/NormalizedAnalyticsInput.kt" to mapOf("ExpenseSnapshot" to 1),
        "domain/export/ExpenseExportMapper.kt" to mapOf("ExportTransaction" to 1)
    )

    // ---- scan 1: data class properties ----

    @Test
    fun `every data class with spendingExclusionReason also declares refundNettedAmount except listed exclusions`() {
        val scan = scanDataClasses()

        val missing = scan.filter { it.hasExclusionReason && !it.hasRefundNetted }.map { it.name }.toSet()

        val unexpected = missing - intentionalExclusions.keys
        assertThat(unexpected).isEmpty()
    }

    @Test
    fun `intentional exclusion list is exact and has no stale entries`() {
        val scan = scanDataClasses()

        val missing = scan.filter { it.hasExclusionReason && !it.hasRefundNetted }.map { it.name }.toSet()

        assertThat(missing).containsExactlyElementsIn(intentionalExclusions.keys)
    }

    @Test
    fun `scan finds the core carriers and they declare both properties`() {
        val carriers = scanDataClasses().filter { it.hasExclusionReason && it.hasRefundNetted }.map { it.name }.toSet()

        assertThat(carriers).containsAtLeast(
            "Expense",
            "ExpenseSnapshot",
            "NormalizedExpense",
            "NormalizableAnalyticsExpense",
            "DashboardExpense",
            "ExportTransaction"
        )
    }

    // ---- scan 2: constructor calls ----

    @Test
    fun `no money DTO constructor call passes spendingExclusionReason without refundNettedAmount`() {
        val offenders = mutableListOf<String>()
        for (file in mainKotlinFiles()) {
            val src = stripNoise(file.readText())
            for (type in moneyDtoTypes) {
                for (args in constructorCallArguments(src, type)) {
                    if (WORD_EXCLUSION.containsMatchIn(args) && !WORD_REFUND.containsMatchIn(args)) {
                        offenders += "${file.name}:$type"
                    }
                }
            }
        }

        assertThat(offenders).isEmpty()
    }

    // ---- scan 3: each known copy site is still present ----

    @Test
    fun `every known copy site still passes both properties to the expected DTO constructors`() {
        val root = mainRoot()
        val shortfalls = mutableListOf<String>()
        for ((relative, expectations) in knownCopySites) {
            val file = File(root, "com/yourname/expensetracker/$relative")
            assertThat(file.isFile).isTrue()
            val src = stripNoise(file.readText())
            for ((type, minimum) in expectations) {
                val qualifying = constructorCallArguments(src, type).count {
                    WORD_EXCLUSION.containsMatchIn(it) && WORD_REFUND.containsMatchIn(it)
                }
                if (qualifying < minimum) shortfalls += "$relative: $type has $qualifying of $minimum"
            }
        }

        assertThat(shortfalls).isEmpty()
    }

    // ---- scanning helpers ----

    private data class DataClassInfo(
        val file: String,
        val name: String,
        val hasExclusionReason: Boolean,
        val hasRefundNetted: Boolean
    )

    private fun scanDataClasses(): List<DataClassInfo> {
        val result = mutableListOf<DataClassInfo>()
        for (file in mainKotlinFiles()) {
            val src = stripNoise(file.readText())
            for (m in DATA_CLASS.findAll(src)) {
                val open = m.range.last
                val close = matchingClose(src, open)
                check(close > open) { "Unbalanced constructor in ${file.name}" }
                val ctor = src.substring(open + 1, close)
                result += DataClassInfo(
                    file = file.name,
                    name = m.groupValues[1],
                    hasExclusionReason = Regex("""\bval\s+spendingExclusionReason\b""").containsMatchIn(ctor),
                    hasRefundNetted = Regex("""\bval\s+refundNettedAmount\b""").containsMatchIn(ctor)
                )
            }
        }
        return result
    }

    private fun constructorCallArguments(src: String, type: String): List<String> {
        val out = mutableListOf<String>()
        val call = Regex("""(?<![\w])${Regex.escape(type)}\s*\(""")
        for (m in call.findAll(src)) {
            val before = src.substring(0, m.range.first).trimEnd()
            if (before.endsWith("class") || before.endsWith("interface")) continue
            val open = m.range.last
            val close = matchingClose(src, open)
            check(close > open) { "Unbalanced call of $type" }
            out += src.substring(open + 1, close)
        }
        return out
    }

    private fun matchingClose(s: String, openIdx: Int): Int {
        var depth = 0
        var i = openIdx
        while (i < s.length) {
            when (s[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** Replaces comments, string literals and char literals with a space (Kotlin nested comments honoured). */
    private fun stripNoise(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                s.startsWith("//", i) -> while (i < s.length && s[i] != '\n') i++
                s.startsWith("/*", i) -> {
                    var depth = 1
                    i += 2
                    while (i < s.length && depth > 0) {
                        when {
                            s.startsWith("/*", i) -> { depth++; i += 2 }
                            s.startsWith("*/", i) -> { depth--; i += 2 }
                            else -> i++
                        }
                    }
                    sb.append(' ')
                }
                c == '"' -> { i = skipString(s, i); sb.append(' ') }
                c == '\'' -> {
                    i = if (i + 1 < s.length && s[i + 1] == '\\') {
                        val end = s.indexOf('\'', i + 3)
                        if (end < 0) s.length else end + 1
                    } else {
                        minOf(s.length, i + 3)
                    }
                    sb.append(' ')
                }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    private fun skipString(s: String, start: Int): Int {
        if (s.startsWith("\"\"\"", start)) {
            var j = start + 3
            while (j < s.length && !s.startsWith("\"\"\"", j)) j++
            return minOf(s.length, j + 3)
        }
        var i = start + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> i += 2
                c == '"' -> return i + 1
                c == '$' && i + 1 < s.length && s[i + 1] == '{' -> i = skipTemplate(s, i + 2)
                else -> i++
            }
        }
        return s.length
    }

    private fun skipTemplate(s: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < s.length && depth > 0) {
            when (s[i]) {
                '"' -> i = skipString(s, i)
                '{' -> { depth++; i++ }
                '}' -> { depth--; i++ }
                else -> i++
            }
        }
        return i
    }

    private fun mainKotlinFiles(): List<File> =
        mainRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun mainRoot(): File {
        val userDir = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(userDir, "src/main/java"),
            File(userDir, "app/src/main/java")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate app/src/main/java from $userDir")
    }

    private companion object {
        val DATA_CLASS = Regex("""\bdata\s+class\s+(\w+)\s*(?:<[^>]*>)?\s*\(""")
        val WORD_EXCLUSION = Regex("""\bspendingExclusionReason\b""")
        val WORD_REFUND = Regex("""\brefundNettedAmount\b""")
    }
}
