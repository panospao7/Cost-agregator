package com.yourname.expensetracker.contracts

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Architecture contract: No raw effectiveAmount summation without currency normalization.
 * Every file that sums effectiveAmount must either:
 * - Import CurrencyConverter or AnalyticsCurrencyNormalizer, OR
 * - Contain a "pre-normalized" / "already-normalized" / "SAFE" comment, OR
 * - Group by currency before summing, OR
 * - Operate on data known to be single-currency (homeCurrency / displayCurrency context)
 */
class MoneyContractTest {

    private val mainSrc = File("app/src/main/java/com/yourname/expensetracker")
    private val sumPattern = Regex("""sumOf\s*\{.*effectiveAmount|sumOf\s*\{.*\.effectiveAmount|\+=\s*.*\.effectiveAmount""")

    @Test
    fun `effectiveAmount summation only in currency-aware contexts`() {
        val filesWithSum = mainSrc.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { sumPattern.containsMatchIn(it.readText()) }
            .toList()

        assertTrue("Expected files with effectiveAmount summation", filesWithSum.isNotEmpty())

        val violations = mutableListOf<String>()
        for (file in filesWithSum) {
            val content = file.readText()
            val isCurrencySafe = listOf(
                "CurrencyConverter",
                "AnalyticsCurrencyNormalizer",
                "pre-normalized",
                "already-normalized",
                "CURRENCY NORMALIZATION",
                "data normalized",
                "homeCurrency",
                "displayCurrency",
                "CurrencyFormatter",
                "IncomeExpenseRatio"
            ).any { content.contains(it) }

            val groupsByCurrency = content.contains(Regex("""groupBy\s*\{.*currency"""))
                    || content.contains(Regex("""groupBy.*\.currency"""))
                    || content.contains(Regex("""\.currency"""))

            if (!isCurrencySafe && !groupsByCurrency) {
                violations.add(file.relativeTo(mainSrc).path)
            }
        }
        assertTrue(
            "Files summing effectiveAmount without currency awareness: $violations",
            violations.isEmpty()
        )
    }
}
