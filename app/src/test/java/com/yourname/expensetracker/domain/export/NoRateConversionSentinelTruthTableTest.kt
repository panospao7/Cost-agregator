package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export rule: a row with no usable conversion (rate not finite, not positive, or blank base
 * currency) exports blank/null conversion fields, never 0. This pins the single rule
 * ([isNoRateConversionSentinel] / [ExportTransaction.hasNoRateConversion]) and its effect on the
 * real mapper and the real Xero/FreshBooks row writers. The generic CSV/JSON writers are pinned
 * in ExportOptionsViewModelTest.
 */
class NoRateConversionSentinelTruthTableTest {

    private data class Case(val rate: Double, val base: String, val sentinel: Boolean)

    private val truthTable = listOf(
        Case(0.85, "EUR", false),
        Case(1.0, "EUR", false),
        Case(Double.MIN_VALUE, "EUR", false),
        Case(0.0, "EUR", true),
        Case(-0.0, "EUR", true),
        Case(-0.85, "EUR", true),
        Case(Double.NaN, "EUR", true),
        Case(Double.POSITIVE_INFINITY, "EUR", true),
        Case(Double.NEGATIVE_INFINITY, "EUR", true),
        Case(0.85, "", true),
        Case(0.85, "   ", true),
        Case(0.0, "", true),
        Case(Double.NaN, "", true)
    )

    private fun exportRow(rate: Double, base: String) = ExportTransaction(
        id = 1L,
        date = 1_700_000_000_000L,
        amount = 50.0,
        merchant = "Cafe",
        notes = null,
        categoryId = null,
        currency = "USD",
        baseAmount = 42.5,
        baseCurrency = base,
        exchangeRateUsed = rate
    )

    private fun expense(rate: Double, base: String) = Expense(
        id = 5L,
        amount = 50.0,
        currency = "USD",
        merchant = "Cafe",
        transactionType = TransactionType.PURCHASE,
        date = 1_700_000_000_000L,
        categoryId = 3L,
        baseAmount = 42.5,
        baseCurrency = base,
        exchangeRateUsed = rate
    )

    @Test
    fun `isNoRateConversionSentinel truth table including non-finite and negative rates and blank base`() {
        for (case in truthTable) {
            assertEquals(
                "rate=${case.rate} base='${case.base}'",
                case.sentinel,
                isNoRateConversionSentinel(case.rate, case.base)
            )
        }
    }

    @Test
    fun `hasNoRateConversion on ExportTransaction follows the same truth table`() {
        for (case in truthTable) {
            assertEquals(
                "rate=${case.rate} base='${case.base}'",
                case.sentinel,
                exportRow(case.rate, case.base).hasNoRateConversion
            )
        }
    }

    @Test
    fun `mapper blanks every conversion field for every sentinel case and never leaks the stored amount`() {
        for (case in truthTable.filter { it.sentinel }) {
            val result = ExpenseExportMapper.map(expense(case.rate, case.base))
            val label = "rate=${case.rate} base='${case.base}'"

            assertTrue(label, result.hasNoRateConversion)
            assertEquals(label, "", result.baseCurrency)
            assertEquals(label, "", result.homeCurrency)
            assertEquals("$label stored 42.5 must not leak", 0.0, result.baseAmount, 0.0)
            assertEquals(label, 0.0, result.exchangeRateUsed, 0.0)
            assertNull("$label conversion rate must be absent, not 0", result.conversionRateUsed)
            assertEquals("the original amount and currency stay intact", 50.0, result.originalAmount!!, 0.0)
            assertEquals(label, "USD", result.originalCurrency)
        }
    }

    @Test
    fun `mapper keeps a real conversion untouched`() {
        val result = ExpenseExportMapper.map(expense(0.85, "EUR"))

        assertFalse(result.hasNoRateConversion)
        assertEquals("EUR", result.baseCurrency)
        assertEquals("EUR", result.homeCurrency)
        assertEquals(42.5, result.baseAmount, 0.0)
        assertEquals(0.85, result.exchangeRateUsed, 0.0)
        assertEquals(0.85, result.conversionRateUsed!!, 0.0)
    }

    @Test
    fun `xero and freshbooks rows write blank conversion cells for every mapped sentinel case`() {
        for (case in truthTable.filter { it.sentinel }) {
            val tx = ExpenseExportMapper.map(expense(case.rate, case.base))
            val label = "rate=${case.rate} base='${case.base}'"
            for ((name, write) in listOf<Pair<String, (Appendable, ExportTransaction, Map<Long, String>) -> Unit>>(
                "xero" to (XeroCSVExporter()::writeExpense),
                "freshbooks" to (FreshBooksExporter()::writeExpense)
            )) {
                val row = StringBuilder().also { write(it, tx, emptyMap()) }.toString().trimEnd('\n')
                val cells = row.split(",")
                // Columns 6..9: originalCurrency, homeCurrency, conversionRate, originalAmount
                assertEquals("$name $label originalCurrency", "USD", cells[6])
                assertEquals("$name $label homeCurrency must be blank", "", cells[7])
                assertEquals("$name $label conversionRate must be blank not 0", "", cells[8])
                assertFalse("$name $label must not contain NaN or Infinity: $row", row.contains("NaN") || row.contains("Infinity"))
            }
        }
    }
}
