package com.yourname.expensetracker.domain.export

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import org.junit.Test

/**
 * MC 1b-3 / AE-25 / G2-12: a row whose conversion snapshot was not stored
 * (no exchange rate) must export an EMPTY base currency and home currency cell —
 * never the legacy "EUR" default. Covers the mapper and the Xero/FreshBooks row
 * writers; the generic CSV/JSON writers are covered in ExportOptionsViewModelTest.
 * Rows with a stored snapshot keep their home currency.
 */
class NoRateExportCellsTest {

    private val noRateExpense = Expense(
        id = 77L,
        amount = 50.0,
        currency = "USD",
        merchant = "Cafe",
        transactionType = TransactionType.PURCHASE,
        date = 1_700_000_000_000L,
        categoryId = 3L,
        // MC INV-14/15: sentinel triple written when no rate was available.
        baseAmount = 0.0,
        baseCurrency = "",
        exchangeRateUsed = 0.0
    )

    private val convertedExpense = noRateExpense.copy(
        id = 78L,
        baseAmount = 45.67,
        baseCurrency = "EUR",
        exchangeRateUsed = 0.9134
    )

    @Test
    fun `mapper exports empty home and base currency for no-rate row`() {
        val result = noRateExpense.toExportTransaction()

        assertThat(result.homeCurrency).isEmpty()
        assertThat(result.baseCurrency).isEmpty()
        assertThat(result.baseAmount).isEqualTo(0.0)
        assertThat(result.exchangeRateUsed).isEqualTo(0.0)
        assertThat(result.conversionRateUsed).isNull()
        assertThat(result.originalCurrency).isEqualTo("USD")
    }

    @Test
    fun `mapWithSourceLinks exports empty home and base currency for no-rate row`() {
        // ExportOptionsViewModel's CSV and JSON writers read these fields verbatim.
        val result = ExpenseExportMapper.mapWithSourceLinks(noRateExpense, emptyList())

        assertThat(result.homeCurrency).isEmpty()
        assertThat(result.baseCurrency).isEmpty()
        assertThat(result.conversionRateUsed).isNull()
    }

    @Test
    fun `mapper keeps home currency for converted row`() {
        val result = convertedExpense.toExportTransaction()

        assertThat(result.homeCurrency).isEqualTo("EUR")
        assertThat(result.baseCurrency).isEqualTo("EUR")
        assertThat(result.baseAmount).isEqualTo(45.67)
    }

    @Test
    fun `xero export writes empty HomeCurrency cell for no-rate row`() {
        val row = rowOf(XeroCSVExporter()::writeExpense, noRateExpense.toExportTransaction())

        val cells = row.split(",")
        // Columns: Date,Description,Amount,Currency,Account,Reference,OriginalCurrency,HomeCurrency,ConversionRate,OriginalAmount
        assertThat(cells[6]).isEqualTo("USD")
        assertThat(cells[7]).isEmpty()
        assertThat(cells[8]).isEmpty()
        assertThat(row).doesNotContain("EUR")
    }

    @Test
    fun `freshbooks export writes empty homeCurrency cell for no-rate row`() {
        val row = rowOf(FreshBooksExporter()::writeExpense, noRateExpense.toExportTransaction())

        val cells = row.split(",")
        // Columns: date,description,amount,currency,category,vendor,originalCurrency,homeCurrency,conversionRate,originalAmount
        assertThat(cells[6]).isEqualTo("USD")
        assertThat(cells[7]).isEmpty()
        assertThat(cells[8]).isEmpty()
        assertThat(row).doesNotContain("EUR")
    }

    @Test
    fun `xero export keeps HomeCurrency for converted row`() {
        val row = rowOf(XeroCSVExporter()::writeExpense, convertedExpense.toExportTransaction())

        val cells = row.split(",")
        assertThat(cells[7]).isEqualTo("EUR")
    }

    private fun rowOf(
        write: (Appendable, ExportTransaction, Map<Long, String>) -> Unit,
        transaction: ExportTransaction
    ): String = StringBuilder().also { write(it, transaction, emptyMap()) }.toString().trimEnd('\n')
}
